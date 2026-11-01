/*
 *  This file is part of DevxyzIDE.
 *
 *  DevxyzIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DevxyzIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with DevxyzIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jepongdevxyz.idefcs.fable

import android.content.Context
import com.jepongdevxyz.idefcs.preferences.internal.GeneralPreferences
import java.io.File

/**
 * Devxyz Fable's AI phase (plan §§5, 7–9, 12, 13): Gemini behind the
 * user's own key. DEFAULT = NO API — with no key set, nothing here runs
 * and Fable stays fully local. When a key is set:
 *
 *  - chat the local brain cannot answer goes to Gemini (user-requested
 *    budget), which replies in the language the user writes in;
 *  - images and sampled video frames go to Gemini vision;
 *  - a real code error, after the deterministic playbook is ruled out and
 *    the user allows it on a card, gets ONE bundled fix request
 *    (auto-fix budget, max 2 applied attempts per incident);
 *  - any file change the model proposes is parsed into a
 *    [FablePatchProposal] and only ever applied from the Apply card,
 *    with a backup snapshot, a rebuild as judge, and auto-rollback.
 *
 * All functions are blocking; call them off the UI thread. Nothing here
 * throws: failures come back as honest messages.
 */
object FableAi {

  /** Prefix of the temporary chat bubble shown while a call is running. */
  const val PENDING_MARKER = "⏳"

  fun isConfigured(): Boolean = GeneralPreferences.fableGeminiApiKey.isNotBlank()

  sealed class Outcome {
    data class Reply(
      val text: String,
      val proposal: FablePatchProposal?,
      val modelUsed: String?,
      val note: String?
    ) : Outcome()

    data class Failed(val message: String) : Outcome()
  }

  private sealed class ChainOutcome {
    data class Ok(val text: String, val model: String, val note: String?) : ChainOutcome()
    data class Failed(val message: String) : ChainOutcome()
  }

  // ------------------------------------------------------------ chat

  /**
   * A user-requested chat call. [history] is the project's chat log; the
   * reply may carry a patch proposal parsed from the model's edit blocks.
   */
  fun chat(
    context: Context,
    filesDir: File,
    projectPath: String,
    index: KnowledgeIndex?,
    history: List<FableChatMessage>,
    userText: String
  ): Outcome {
    val turns = mutableListOf<FableGeminiClient.Turn>()
    val recent = history.takeLast(12)
    for (message in recent) {
      if (message.text.isBlank()) continue
      val role = when (message.role) {
        FableRole.USER -> "user"
        FableRole.FABLE -> "model"
        FableRole.NOTICE -> continue
      }
      turns.add(
        FableGeminiClient.Turn(
          role, listOf(FableGeminiClient.Part.Text(message.text.take(1500)))
        )
      )
    }
    while (turns.isNotEmpty() && turns.first().role != "user") {
      turns.removeAt(0)
    }
    turns.add(
      FableGeminiClient.Turn(
        "user", listOf(FableGeminiClient.Part.Text(userText.take(4000)))
      )
    )

    val system = systemPrompt(index) + creationGuidance(context, userText)
    return when (val result = callChain(filesDir, FableAiCallKind.USER_REQUESTED, system, turns, 0.5)) {
      is ChainOutcome.Failed -> Outcome.Failed(result.message)
      is ChainOutcome.Ok -> {
        val (cleanText, rawProposal) = parseProposal(
          result.text, projectPath, FableAiCallKind.USER_REQUESTED, null
        )
        val proposal = groundProposal(
          filesDir, File(projectPath), FableAiCallKind.USER_REQUESTED, null, rawProposal
        )
        val text = if (rawProposal != null && proposal == null) {
          cleanText + "\n\nI checked that change against your actual files " +
            "and it doesn't line up, so I didn't prepare a change card. " +
            "Nothing in your project was changed."
        } else {
          withProposalNote(cleanText, proposal)
        }
        Outcome.Reply(text, proposal, result.model, result.note)
      }
    }
  }

  // ------------------------------------------------------------ vision

  /**
   * A user-requested vision call: attached images (or frames sampled from
   * a video) plus the user's question, in the open project's context.
   */
  fun vision(
    filesDir: File,
    projectPath: String,
    index: KnowledgeIndex?,
    images: List<FableGeminiClient.Part.Image>,
    question: String
  ): Outcome {
    if (images.isEmpty()) {
      return Outcome.Failed("I couldn't read any image from that attachment.")
    }
    val parts = mutableListOf<FableGeminiClient.Part>()
    parts.addAll(images)
    parts.add(
      FableGeminiClient.Part.Text(
        question.ifBlank {
          "Look at this and help me with it in the context of my Android project. " +
            "If it shows an error, quote the error text exactly. If it is a UI " +
            "reference, describe what would need to be built."
        }.take(2000)
      )
    )
    val turns = listOf(FableGeminiClient.Turn("user", parts))
    return when (val result = callChain(
      filesDir, FableAiCallKind.USER_REQUESTED, systemPrompt(index), turns, 0.4
    )) {
      is ChainOutcome.Failed -> Outcome.Failed(result.message)
      is ChainOutcome.Ok -> {
        val (cleanText, rawProposal) = parseProposal(
          result.text, projectPath, FableAiCallKind.USER_REQUESTED, null
        )
        val proposal = groundProposal(
          filesDir, File(projectPath), FableAiCallKind.USER_REQUESTED, null, rawProposal
        )
        val text = if (rawProposal != null && proposal == null) {
          cleanText + "\n\nI checked that change against your actual files " +
            "and it doesn't line up, so I didn't prepare a change card. " +
            "Nothing in your project was changed."
        } else {
          withProposalNote(cleanText, proposal)
        }
        Outcome.Reply(text, proposal, result.model, result.note)
      }
    }
  }

  // ------------------------------------------------------------ auto-fix

  /**
   * The auto-fix call, made only after the user allowed the files to be
   * sent. Bundles ALL of the build's real code errors into one request
   * (plan §8) together with the error files' contents.
   */
  fun proposeFix(
    context: Context,
    filesDir: File,
    projectDir: File,
    request: FableAiFixRequest,
    index: KnowledgeIndex?
  ): Outcome {
    val prompt = StringBuilder()
    if (request.errors.isNotEmpty()) {
      prompt.append("The build of my Android project failed with these code errors:\n")
      for (error in request.errors) {
        prompt.append("- ").append(error.relativePath).append(":").append(error.line)
          .append(" — ").append(error.message).append('\n')
      }
    } else {
      prompt.append(
        "The build of my Android project failed. There is no single " +
          "code location for this failure — diagnose the root cause " +
          "from the build output below and the project files that " +
          "follow it.\n"
      )
    }
    prompt.append("\nEnd of the build output:\n")
    prompt.append(request.buildTail.takeLast(6000)).append('\n')

    val sentFiles = linkedSetOf<String>()
    var charBudget = 36_000
    for (pathToSend in request.errors.map { it.relativePath } + request.contextFiles) {
      if (!sentFiles.add(pathToSend)) continue
      if (charBudget <= 0) break
      val file = File(projectDir, pathToSend)
      val content = try {
        if (file.isFile) file.readText() else ""
      } catch (e: Exception) {
        ""
      }
      if (content.isEmpty()) continue
      val capped = content.take(minOf(12_000, charBudget))
      charBudget -= capped.length
      prompt.append("\n===== FILE: ").append(pathToSend).append(" =====\n")
      prompt.append(capped).append('\n')
    }
    prompt.append(
      "\nFix the errors with the smallest possible changes. Explain briefly " +
        "what was wrong, then give the edits in the block format from your " +
        "instructions. Touch only the files that must change."
    )

    var system = systemPrompt(index) +
      "\nYou are fixing a failed build. Be precise and minimal."
    if (request.attempt >= 2) {
      // A first fix already failed: switch to root-cause methodology.
      system += skillGuidance(context, "systematic-debugging")
    }

    val turns = listOf(
      FableGeminiClient.Turn("user", listOf(FableGeminiClient.Part.Text(prompt.toString())))
    )
    return when (val result = callChain(filesDir, FableAiCallKind.AUTO_FIX, system, turns, 0.2)) {
      is ChainOutcome.Failed -> Outcome.Failed(result.message)
      is ChainOutcome.Ok -> {
        val (cleanText, rawProposal) = parseProposal(
          result.text, request.projectPath, FableAiCallKind.AUTO_FIX, request.incidentKey
        )
        val proposal = groundProposal(
          filesDir, projectDir, FableAiCallKind.AUTO_FIX, request.incidentKey, rawProposal
        )
        if (proposal == null) {
          Outcome.Reply(
            cleanText.ifBlank { "Gemini answered but did not include an applicable patch." },
            null, result.model, result.note
          )
        } else {
          Outcome.Reply(
            withProposalNote(cleanText, proposal), proposal, result.model, result.note
          )
        }
      }
    }
  }

  // ------------------------------------------------------------ the chain

  /**
   * Walks the model chain (plan §7): skips models that are rate-limited or
   * out of today's quota, counts every real call in the budget, stops a
   * model for the day on 429 and falls through to the next one once. Any
   * other failure stops the chain — retrying would fail the same way.
   */
  private fun callChain(
    filesDir: File,
    kind: FableAiCallKind,
    systemPrompt: String,
    turns: List<FableGeminiClient.Turn>,
    temperature: Double
  ): ChainOutcome {
    val apiKey = GeneralPreferences.fableGeminiApiKey.trim()
    if (apiKey.isEmpty()) {
      return ChainOutcome.Failed(
        "No Gemini API key is set. Add yours in Settings → Devxyz Fable " +
          "and I'll be able to use AI. Until then I stay fully local."
      )
    }
    var snapshot = FableAiBudget.load(filesDir)
    var limitedNote: String? = null
    for (model in FableAiBudget.MODEL_CHAIN) {
      if (!snapshot.isUsable(model)) {
        continue
      }
      // A 503 ("model overloaded") is a temporary spike, not a verdict:
      // retry the same model with a short backoff (max 3 attempts)
      // before judging the call failed. Every attempt is a real call
      // and counts in the budget.
      var overloaded = false
      var attempt = 0
      while (attempt < 3) {
        attempt++
        if (attempt > 1) {
          try {
            Thread.sleep(2_000L * attempt)
          } catch (e: InterruptedException) {
            break
          }
          FableAiBudget.recordCall(filesDir, model, kind)
        }
        when (val result = FableGeminiClient.generate(apiKey, model, systemPrompt, turns, temperature)) {
          is FableGeminiClient.Result.Ok -> {
            return ChainOutcome.Ok(result.text, model, limitedNote)
          }
          is FableGeminiClient.Result.RateLimited -> {
            FableAiBudget.markRateLimited(filesDir, model)
            limitedNote = "$model is rate-limited today, so I used the next model."
            snapshot = FableAiBudget.load(filesDir)
            overloaded = false
            break
          }
          is FableGeminiClient.Result.Overloaded -> {
            overloaded = true
          }
          is FableGeminiClient.Result.Failed -> {
            return ChainOutcome.Failed("Gemini call failed ($model): ${result.detail}")
          }
        }
      }
      if (overloaded) {
        return ChainOutcome.Failed(
          "Gemini is overloaded right now (HTTP 503 — a temporary spike " +
            "in demand on Google's side). I retried and it is still busy. " +
            "Wait a minute, then tell me \"Ayusin mo\" again and I'll " +
            "pick this problem up where we left it — you don't need to " +
            "explain it again."
        )
      }
    }
    return ChainOutcome.Failed(
      "Both AI models are rate-limited or out of today's free quota " +
        "(20 requests/day each). The quota resets around 3:00 PM Manila. " +
        "Everything local keeps working in the meantime."
    )
  }

  // ------------------------------------------------------------ prompts

  private fun systemPrompt(index: KnowledgeIndex?): String {
    val out = StringBuilder()
    out.append(
      "You are Devxyz Fable, the built-in assistant of DevxyzIDE Pro, an " +
        "Android IDE. You live inside this app and you work ONLY on the " +
        "project the user has open in it: its code, files, builds and " +
        "settings. If the user asks for something unrelated to this app " +
        "or the open project (school assignments, general errands, other " +
        "apps), politely decline in one short line and point back to what " +
        "you can do here.\n" +
        "Always reply in the same language the user writes in.\n" +
        "Be honest and concrete. Never claim a fix works before a build " +
        "has proven it — after a change is applied, the build is the judge.\n" +
        "Base every diagnosis on the evidence in this conversation — the " +
        "project map, the build output, the screenshots. If the output " +
        "shows a step succeeded (a module configured, variants found, " +
        "CONFIGURE SUCCESSFUL), do not claim that step is missing or " +
        "broken.\n" +
        "Never ask the user to paste the current contents of project " +
        "files, and never ask them to make an edit themselves. If a file " +
        "must change, propose it with edit blocks — the app verifies " +
        "every block against the real file before anything is applied, " +
        "so copy text only from files you were actually given; when you " +
        "were not given a file a fix needs, say which file and why in " +
        "one line and the app will fetch it for the next pass. Your job " +
        "is to fix the problem, not to hand the work back.\n"
    )
    if (index != null) {
      out.append(
        "\nThe open project is ${index.projectName} at ${index.projectPath}: " +
          "${index.modules.size} modules, ${index.totalFileCount} files, " +
          "${index.dependencyCount} dependency entries. Toolchain: Gradle " +
          "${index.gradleVersion ?: "?"}, AGP ${index.agpVersion ?: "?"}, " +
          "Kotlin ${index.kotlinVersion ?: "?"}.\n" +
          "Modules: " +
          index.modules.take(20).joinToString(", ") { it.name } + "\n"
      )
    }
    out.append(
      "\nWhen — and only when — a change is needed, propose it with edit " +
        "blocks in EXACTLY this format (paths relative to the project root):\n" +
        "FILE: path/to/File.kt\n" +
        "<<<<<<< SEARCH\n" +
        "<exact existing text, copied character for character>\n" +
        "=======\n" +
        "<replacement text>\n" +
        ">>>>>>> REPLACE\n" +
        "For a brand-new file:\n" +
        "NEW FILE: path/to/NewFile.kt\n" +
        "<<<<<<< CONTENT\n" +
        "<the whole file content>\n" +
        ">>>>>>> END\n" +
        "Prefer the smallest edit that solves the problem. The user sees " +
        "a diff of every block and must tap Apply before anything changes; " +
        "never say you already changed a file.\n"
    )
    return out.toString()
  }

  /** Superpowers guidance for "make me a page/feature" requests (§13). */
  private fun creationGuidance(context: Context, userText: String): String {
    val text = userText.lowercase()
    val asksCreate = listOf("create", "make", "build", "gawa", "gawan", "add")
      .any { text.contains(it) }
    val isPageOrFeature = listOf(
      "page", "screen", "activity", "fragment", "feature", "layout", "ui"
    ).any { text.contains(it) }
    if (!asksCreate || !isPageOrFeature) return ""
    return skillGuidance(context, "brainstorming") + skillGuidance(context, "writing-plans")
  }

  /**
   * Reads a bundled Superpowers skill (assets/fable/superpowers/<name>)
   * and returns a capped excerpt for the prompt. Skills cost no API calls
   * by themselves; they only guide a call that is happening anyway.
   */
  private fun skillGuidance(context: Context, skillName: String): String {
    return try {
      val text = context.assets.open("fable/superpowers/$skillName/SKILL.md")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
      "\n--- Skill guidance: $skillName ---\n" + text.take(3000) + "\n"
    } catch (e: Exception) {
      ""
    }
  }

  // ------------------------------------------------------------ grounding

  /**
   * Grounds a proposal in the project's REAL files before any card is
   * shown. In chat and vision the model never saw the target files, so
   * its SEARCH text is a guess from a template — and a guessed SEARCH
   * can never apply (the patcher demands an exact match, by design).
   * Every edit whose SEARCH is absent from the actual file (or whose
   * create-target already exists) is sent back in ONE extra call with
   * the files' true contents, and only edits that then genuinely match
   * survive into the card. If the extra call fails, the original
   * proposal is returned unchanged — the patcher stays the last line of
   * defence either way. The extra call counts in the daily budget like
   * any other call of the same kind.
   */
  private fun groundProposal(
    filesDir: File,
    projectDir: File,
    kind: FableAiCallKind,
    incidentKey: String?,
    proposal: FablePatchProposal?
  ): FablePatchProposal? {
    if (proposal == null || !projectDir.isDirectory) return proposal

    fun editMatches(edit: FableFileEdit): Boolean {
      val file = File(projectDir, edit.relativePath)
      if (edit.isCreate) return !file.exists()
      val content = try {
        if (file.isFile) file.readText() else null
      } catch (e: Exception) {
        null
      }
      return content != null && edit.search != null && content.contains(edit.search)
    }

    val mismatched = proposal.edits.filter { !editMatches(it) }
    if (mismatched.isEmpty()) return proposal
    val badPaths = mismatched.map { it.relativePath }.toSet()

    val prompt = StringBuilder()
    prompt.append(
      "Your edit blocks for the files below do NOT match the files' " +
        "actual current contents, so they cannot be applied. Here are " +
        "the actual contents. Re-issue edit blocks ONLY for these " +
        "files, copying every SEARCH text character-for-character from " +
        "the actual content below. If a change is already present or " +
        "not needed, reply with one line saying so and no blocks.\n"
    )
    var charBudget = 30_000
    for (path in badPaths) {
      if (charBudget <= 0) break
      val file = File(projectDir, path)
      val content = try {
        if (file.isFile) file.readText() else ""
      } catch (e: Exception) {
        ""
      }
      if (content.isEmpty()) continue
      val capped = content.take(minOf(12_000, charBudget))
      charBudget -= capped.length
      prompt.append("\n===== ACTUAL FILE: ").append(path).append(" =====\n")
      prompt.append(capped).append('\n')
    }
    val system = "You are Devxyz Fable, the built-in assistant of " +
      "DevxyzIDE Pro. Correct the edit blocks so they match the user's " +
      "actual files exactly, using the edit block format: FILE: <path>, " +
      "<<<<<<< SEARCH, the exact existing text, =======, the replacement " +
      "text, >>>>>>> REPLACE. Reply in the same language the user " +
      "writes in."
    val turns = listOf(
      FableGeminiClient.Turn("user", listOf(FableGeminiClient.Part.Text(prompt.toString())))
    )
    val result = callChain(filesDir, kind, system, turns, 0.2)
    if (result !is ChainOutcome.Ok) return proposal
    val (_, regrounded) = parseProposal(result.text, proposal.projectPath, kind, incidentKey)
    val fixedOk = (regrounded?.edits ?: emptyList())
      .filter { it.relativePath in badPaths }
      .filter { editMatches(it) }
    val kept = proposal.edits.filter { editMatches(it) }
    val merged = (kept + fixedOk).take(MAX_EDITS)
    if (merged.isEmpty()) return null
    return proposal.copy(edits = merged)
  }

  // ------------------------------------------------------------ parsing

  /**
   * Splits a model reply into display text and, when valid edit blocks
   * are present, a [FablePatchProposal]. Malformed blocks stay visible as
   * plain text instead of being silently dropped.
   */
  private fun parseProposal(
    reply: String,
    projectPath: String,
    kind: FableAiCallKind,
    incidentKey: String?
  ): Pair<String, FablePatchProposal?> {
    val lines = reply.lines()
    val display = StringBuilder()
    val edits = mutableListOf<FableFileEdit>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i].trim()
      val isEditHeader = line.startsWith("FILE: ")
      val isCreateHeader = line.startsWith("NEW FILE: ")
      if ((isEditHeader || isCreateHeader) && edits.size < MAX_EDITS) {
        val path = line.removePrefix("FILE: ").removePrefix("NEW FILE: ").trim()
        val block = tryParseBlock(lines, i + 1, isCreateHeader)
        if (block != null && isValidRelativePath(path)) {
          edits.add(
            FableFileEdit(
              relativePath = path,
              isCreate = isCreateHeader,
              search = block.first,
              replace = block.second
            )
          )
          i = block.third
          continue
        }
      }
      display.append(lines[i]).append('\n')
      i++
    }
    val cleanText = display.toString()
      .replace(Regex("""\n{3,}"""), "\n\n")
      .trim()
    if (edits.isEmpty()) {
      return cleanText to null
    }
    val summary = cleanText.take(400).ifBlank { "AI-proposed change" }
    val proposal = FablePatchProposal(
      id = System.currentTimeMillis(),
      projectPath = projectPath,
      kind = kind,
      summary = summary,
      edits = edits,
      incidentKey = incidentKey
    )
    return cleanText to proposal
  }

  /**
   * Parses one block starting at [start]; returns (search, replace,
   * nextLineIndex) or null when the markers are not exactly as specified.
   */
  private fun tryParseBlock(
    lines: List<String>,
    start: Int,
    isCreate: Boolean
  ): Triple<String?, String, Int>? {
    var i = start
    while (i < lines.size && lines[i].isBlank()) i++
    if (i >= lines.size) return null
    val openMarker = if (isCreate) "<<<<<<< CONTENT" else "<<<<<<< SEARCH"
    if (lines[i].trim() != openMarker) return null
    i++
    val first = StringBuilder()
    val second = StringBuilder()
    var inSecond = isCreate
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      if (!isCreate && !inSecond && trimmed == "=======") {
        inSecond = true
        i++
        continue
      }
      if (inSecond && (trimmed == ">>>>>>> REPLACE" || trimmed == ">>>>>>> END")) {
        val searchText = if (isCreate) null
        else first.toString().trimEnd('\n')
        return Triple(searchText, second.toString().trimEnd('\n'), i + 1)
      }
      val target = if (inSecond) second else first
      if (target.isNotEmpty()) target.append('\n')
      target.append(lines[i])
      i++
    }
    return null
  }

  private fun isValidRelativePath(path: String): Boolean {
    if (path.isEmpty() || path.length > 200) return false
    if (path.startsWith("/") || path.startsWith("~")) return false
    if (path.contains("..")) return false
    return path.none { it == '\\' || it == '\n' }
  }

  private fun withProposalNote(text: String, proposal: FablePatchProposal?): String {
    if (proposal == null) return text
    val intro = text.ifBlank { "I've prepared a change from what you asked." }
    return intro + "\n\nI've put the exact changes in a card here — check the " +
      "diff and tap Apply if it looks right. Nothing in your project " +
      "changes until you do."
  }

  private const val MAX_EDITS = 6
}
