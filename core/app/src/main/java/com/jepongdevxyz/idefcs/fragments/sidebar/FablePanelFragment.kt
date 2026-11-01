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

package com.jepongdevxyz.idefcs.fragments.sidebar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.jepongdevxyz.idefcs.activities.editor.EditorActivityKt
import com.jepongdevxyz.idefcs.databinding.FragmentFablePanelBinding
import com.jepongdevxyz.idefcs.fable.FableAi
import com.jepongdevxyz.idefcs.fable.FableAiBudget
import com.jepongdevxyz.idefcs.fable.FableAiCallKind
import com.jepongdevxyz.idefcs.fable.FableAiFixRequest
import com.jepongdevxyz.idefcs.fable.FableArchiveReader
import com.jepongdevxyz.idefcs.fable.FableBuildWatcher
import com.jepongdevxyz.idefcs.fable.FableInstallProgress
import com.jepongdevxyz.idefcs.fable.FableChatMessage
import com.jepongdevxyz.idefcs.fable.FableChatStore
import com.jepongdevxyz.idefcs.fable.FableChatUi
import com.jepongdevxyz.idefcs.fable.FableIndexStore
import com.jepongdevxyz.idefcs.fable.FableIntake
import com.jepongdevxyz.idefcs.fable.FableLocalBrain
import com.jepongdevxyz.idefcs.fable.FablePatcher
import com.jepongdevxyz.idefcs.fable.FablePendingAi
import com.jepongdevxyz.idefcs.fable.FablePendingInstall
import com.jepongdevxyz.idefcs.fable.FableRole
import com.jepongdevxyz.idefcs.fable.KnowledgeIndex
import com.jepongdevxyz.idefcs.fable.KnowledgeIndexBuilder
import com.jepongdevxyz.idefcs.fragments.FragmentWithBinding
import com.jepongdevxyz.idefcs.preferences.internal.GeneralPreferences
import com.jepongdevxyz.idefcs.projects.IProjectManager
import com.jepongdevxyz.idefcs.resources.R
import com.jepongdevxyz.idefcs.tasks.TaskExecutor
import com.jepongdevxyz.idefcs.utils.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable

/**
 * The Devxyz Fable panel. Phase 1 built the read-only project map; Phase 2
 * adds the local chat (deterministic answers from the map, attachment
 * reading, project ZIP intake) and renders the build watcher's proactive
 * notices. Fable is OFF by default; when off, nothing here runs.
 */
class FablePanelFragment : FragmentWithBinding<FragmentFablePanelBinding>(
  FragmentFablePanelBinding::inflate
) {

  private val pendingListener: () -> Unit = {
    _binding?.root?.post { renderActionCard() }
  }

  private val progressListener: () -> Unit = {
    _binding?.root?.post { renderProgress() }
  }

  private val chatListener: () -> Unit = {
    _binding?.root?.post { renderChat() }
  }

  private val aiListener: () -> Unit = {
    _binding?.root?.post { renderAiCards() }
  }

  private val attachmentLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        onAttachmentPicked(uri)
      }
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.fableSwitch.isChecked = GeneralPreferences.fableEnabled
    binding.fableSwitch.setOnCheckedChangeListener { _, checked ->
      GeneralPreferences.fableEnabled = checked
      render()
    }
    binding.fableRefresh.setOnClickListener { rebuildIndex() }
    binding.fableChatSend.setOnClickListener { sendChatMessage() }
    binding.fableActionAllow.setOnClickListener { onInstallCardAllow() }
    binding.fableActionDeny.setOnClickListener { onInstallCardDeny() }
    binding.fableAiAllow.setOnClickListener { onAiCardAllow() }
    binding.fableAiDeny.setOnClickListener { onAiCardDeny() }
    binding.fablePatchApply.setOnClickListener { onPatchApply() }
    binding.fablePatchDeny.setOnClickListener { onPatchDeny() }
    binding.fableChatAttach.setOnClickListener {
      attachmentLauncher.launch(arrayOf("*/*"))
    }

    FableChatStore.addListener(chatListener)
    FablePendingInstall.addListener(pendingListener)
    FablePendingAi.addListener(aiListener)
    FableInstallProgress.addListener(progressListener)
    setupKeyboardLift(view)
    registerImeFirstBack(viewLifecycleOwner, view)
    render()
  }

  override fun onDestroyView() {
    removeKeyboardLift()
    FableChatStore.removeListener(chatListener)
    FablePendingInstall.removeListener(pendingListener)
    FablePendingAi.removeListener(aiListener)
    FableInstallProgress.removeListener(progressListener)
    super.onDestroyView()
  }

  private fun render() {
    val binding = _binding ?: return
    renderActionCard()
    renderAiCards()
    renderAiStatus()
    renderProgress()
    val enabled = GeneralPreferences.fableEnabled
    binding.fableSwitch.isChecked = enabled
    binding.fableRefresh.isEnabled = enabled

    if (!enabled) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_off)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      renderChat()
      return
    }

    val projectDir = currentProjectDir()
    if (projectDir == null) {
      binding.fableStatus.text = ""
      binding.fableMapBody.text = getString(R.string.msg_fable_no_project)
      binding.fableEnvBody.text = ""
      binding.fableUpdated.text = ""
      renderChat()
      return
    }

    val cached = FableIndexStore.load(requireContext().filesDir, projectDir.absolutePath)
    if (cached != null) {
      showIndex(cached)
    } else {
      rebuildIndex()
    }
    renderChat()
  }

  private fun rebuildIndex() {
    val binding = _binding ?: return
    if (!GeneralPreferences.fableEnabled) {
      return
    }

    val projectDir = currentProjectDir()
    if (projectDir == null) {
      render()
      return
    }

    binding.fableStatus.text = getString(R.string.msg_fable_indexing)
    val filesDir = requireContext().filesDir
    TaskExecutor.executeAsync(
      Callable {
        val index = KnowledgeIndexBuilder.build(projectDir, Environment.ANDROID_HOME, Environment.JAVA_HOME)
        FableIndexStore.save(filesDir, index)
        index
      }
    ) { result ->
      if (result != null) {
        showIndex(result)
      }
    }
  }

  private fun showIndex(index: KnowledgeIndex) {
    val binding = _binding ?: return

    binding.fableStatus.text = index.projectName

    val map = StringBuilder()
    map.append(index.projectName).append('\n')
    map.append("Modules: ").append(index.modules.size)
      .append(" · Files: ").append(index.totalFileCount)
      .append(" · Dependencies: ").append(index.dependencyCount)
      .append('\n')
    map.append("Gradle ").append(index.gradleVersion ?: "—")
      .append(" · AGP ").append(index.agpVersion ?: "—")
      .append(" · Kotlin ").append(index.kotlinVersion ?: "—")
    for (module in index.modules) {
      map.append('\n').append('\n')
      map.append(module.name).append('\n')
      map.append("compileSdk ").append(module.compileSdk?.toString() ?: "—")
        .append(" · minSdk ").append(module.minSdk?.toString() ?: "—")
        .append(" · targetSdk ").append(module.targetSdk?.toString() ?: "—")
        .append('\n')
      map.append(module.dependencies.size).append(" dependencies · ")
        .append(module.sourceFileCount).append(" source files")
    }
    binding.fableMapBody.text = map.toString()

    val env = StringBuilder()
    env.append("SDK platforms: ")
      .append(if (index.sdkPlatforms.isEmpty()) "—" else index.sdkPlatforms.joinToString(", "))
      .append('\n')
    env.append("Build tools: ")
      .append(if (index.buildToolsVersions.isEmpty()) "—" else index.buildToolsVersions.joinToString(", "))
      .append('\n')
    env.append("Java: ").append(index.javaVersion ?: "—")
    binding.fableEnvBody.text = env.toString()

    val updated = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
      .format(Date(index.builtAtMillis))
    binding.fableUpdated.text = getString(R.string.msg_fable_updated, updated)
  }

  // ------------------------------------------------------- approval card

  /** Shows the Allow/Deny install card while a request is pending. */
  private fun renderActionCard() {
    val binding = _binding ?: return
    val projectDir = if (GeneralPreferences.fableEnabled) currentProjectDir() else null
    val api = projectDir?.let { FablePendingInstall.pendingApi(it.absolutePath) }
    if (api == null) {
      binding.fableActionCard.visibility = View.GONE
      return
    }
    binding.fableActionTitle.text =
      getString(R.string.msg_fable_install_card_title, api)
    binding.fableActionBody.text =
      getString(R.string.msg_fable_install_card_body, api)
    binding.fableActionCard.visibility = View.VISIBLE
  }

  /**
   * Registers a back-press callback for this Fable chat surface: while the
   * keyboard (IME) is visible, the system navigation Back closes the
   * keyboard first (Jepong's order); otherwise the press passes through
   * untouched.
   */
  private fun registerImeFirstBack(owner: androidx.lifecycle.LifecycleOwner, root: android.view.View) {
    val host = activity ?: return
    val callback = object : androidx.activity.OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(host.window.decorView)
        val imeVisible = insets?.isVisible(
          androidx.core.view.WindowInsetsCompat.Type.ime()
        ) == true
        if (imeVisible && root.isShown) {
          androidx.core.view.WindowInsetsControllerCompat(
            host.window, host.window.decorView
          ).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        } else {
          isEnabled = false
          host.onBackPressedDispatcher.onBackPressed()
          isEnabled = true
        }
      }
    }
    host.onBackPressedDispatcher.addCallback(owner, callback)
  }

  /**
   * Lifts this chat surface above the keyboard: edge-to-edge windows do
   * not resize for the IME, so track the visible display frame and pad
   * the bottom by the keyboard height while it is open.
   */
  private var keyboardListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
  private var keyboardRoot: android.view.View? = null

  private fun setupKeyboardLift(root: android.view.View) {
    var lift = 0
    val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
      val rect = android.graphics.Rect()
      root.getWindowVisibleDisplayFrame(rect)
      val diff = root.rootView.height - rect.bottom
      val threshold = (100 * root.resources.displayMetrics.density).toInt()
      val newLift = if (diff > threshold) diff else 0
      if (newLift != lift) {
        lift = newLift
        root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, newLift)
      }
    }
    keyboardListener = listener
    keyboardRoot = root
    root.viewTreeObserver.addOnGlobalLayoutListener(listener)
  }

  /** Detaches the keyboard-lift listener so dead panel views are not kept. */
  private fun removeKeyboardLift() {
    try {
      val listener = keyboardListener
      if (listener != null) {
        keyboardRoot?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
      }
    } catch (e: Exception) {
      // The view may already be gone; nothing else to clean up.
    }
    keyboardListener = null
    keyboardRoot = null
  }

  /** Live install progress (real % parsed from sdkmanager's own output). */
  private fun renderProgress() {
    val binding = _binding ?: return
    val progress = FableInstallProgress.state
    if (!progress.running) {
      binding.fableProgressBlock.visibility = View.GONE
      return
    }
    binding.fableProgressBlock.visibility = View.VISIBLE
    if (progress.percent >= 0) {
      binding.fableProgressBar.isIndeterminate = false
      binding.fableProgressBar.setProgressCompat(progress.percent, true)
      binding.fableProgressStatus.text =
        "${progress.status} ${progress.percent}%"
    } else {
      binding.fableProgressBar.isIndeterminate = true
      binding.fableProgressStatus.text = progress.status
    }
  }

  private fun onInstallCardAllow() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    val api = FablePendingInstall.consumeAllowed(projectPath) ?: run {
      renderActionCard()
      return
    }
    renderActionCard()
    FableBuildWatcher.runApprovedInstall(
      requireContext().filesDir, projectPath, api)
  }

  private fun onInstallCardDeny() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    FablePendingInstall.deny(projectPath)
    FableChatStore.append(
      requireContext().filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE,
        "Okay, I won't install the platform. Just build again " +
          "when you need it — the card will show up again.",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderActionCard()
  }

  // ------------------------------------------------------------------ chat

  private fun renderChat() {
    val binding = _binding ?: return
    val projectDir = currentProjectDir()

    if (!GeneralPreferences.fableEnabled || projectDir == null) {
      binding.fableChatList.removeAllViews()
      binding.fableChatEmpty.visibility = View.VISIBLE
      binding.fableChatEmpty.text = getString(
        if (!GeneralPreferences.fableEnabled) R.string.msg_fable_chat_off
        else R.string.msg_fable_no_project
      )
      return
    }

    val log = FableChatStore.load(requireContext().filesDir, projectDir.absolutePath)
    FableChatUi.renderMessages(binding.fableChatList, log)
    binding.fableChatEmpty.visibility = if (log.messages.isEmpty()) View.VISIBLE else View.GONE
    binding.fableChatEmpty.text = getString(R.string.msg_fable_chat_empty)
    if (isPanelOnScreen()) {
      FableChatStore.markAllRead(requireContext().filesDir, projectDir.absolutePath)
    }
  }

  /**
   * The panel lives inside the editor's start drawer: its view exists (and
   * re-renders on every chat change) even while the drawer is closed and the
   * user cannot see it. Marking messages read in that state kills the chat
   * head's badge the instant a message arrives, so only mark read while the
   * drawer is genuinely open.
   */
  private fun isPanelOnScreen(): Boolean {
    var v: View? = view
    while (v != null) {
      if (v is androidx.drawerlayout.widget.DrawerLayout) {
        return v.isDrawerOpen(androidx.core.view.GravityCompat.START)
      }
      v = v.parent as? View
    }
    return false
  }

  private fun sendChatMessage() {
    val binding = _binding ?: return
    val projectDir = currentProjectDir() ?: return
    val text = binding.fableChatInput.text?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    binding.fableChatInput.text = null

    val filesDir = requireContext().filesDir
    val projectPath = projectDir.absolutePath
    // History for the AI is read BEFORE the new message is appended, so
    // the current question is not sent twice.
    val history = FableChatStore.load(filesDir, projectPath)
    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(FableRole.USER, text, System.currentTimeMillis()),
      countUnread = false
    )

    val index = FableIndexStore.load(filesDir, projectPath)
    val brain = FableLocalBrain.reply(text, index, history.lastBuildFailed)
    if (brain.answeredLocally || !FableAi.isConfigured()) {
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(FableRole.FABLE, brain.text, System.currentTimeMillis()),
        countUnread = false
      )
      renderChat()
      return
    }

    // Beyond local knowledge and a Gemini key is set: ask the AI. The
    // pending bubble is replaced by the real reply when it lands.
    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE, FableAi.PENDING_MARKER + " Asking Gemini…",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderChat()

    val appContext = requireContext().applicationContext
    TaskExecutor.executeAsync(
      Callable {
        FableAi.chat(appContext, filesDir, projectPath, index, history.messages, text)
      }
    ) { outcome ->
      deliverAiOutcome(filesDir, projectPath, outcome)
    }
  }

  /** Posts an AI chat/vision outcome into the chat, replacing the bubble. */
  private fun deliverAiOutcome(
    filesDir: File,
    projectPath: String,
    outcome: FableAi.Outcome?
  ) {
    val replyText: String = when (outcome) {
      is FableAi.Outcome.Reply -> {
        outcome.proposal?.let { FablePendingAi.propose(it) }
        outcome.text
      }
      is FableAi.Outcome.Failed -> outcome.message
      null -> "Something went wrong while asking Gemini. Nothing was changed."
    }
    val message = FableChatMessage(
      FableRole.FABLE, replyText, System.currentTimeMillis()
    )
    val replaced = FableChatStore.replacePending(
      filesDir, projectPath, FableAi.PENDING_MARKER, message
    )
    if (!replaced) {
      FableChatStore.append(filesDir, projectPath, message, countUnread = false)
    }
    renderChat()
    renderAiStatus()
  }

  private fun onAttachmentPicked(uri: Uri) {
    val projectDir = currentProjectDir() ?: return
    val context = requireContext()
    val filesDir = context.filesDir
    val projectPath = projectDir.absolutePath
    val name = uri.lastPathSegment ?: "attachment"

    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(FableRole.USER, "", System.currentTimeMillis(), attachmentName = name),
      countUnread = false
    )
    renderChat()

    val appContext = context.applicationContext

    // Images and videos go to Gemini vision when a key is set (plan §12).
    val mime = context.contentResolver.getType(uri) ?: ""
    if ((mime.startsWith("image/") || mime.startsWith("video/")) && FableAi.isConfigured()) {
      val log = FableChatStore.load(filesDir, projectPath)
      val question = log.messages.lastOrNull {
        it.role == FableRole.USER && it.text.isNotBlank()
      }?.text ?: ""
      val index = FableIndexStore.load(filesDir, projectPath)
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          FableRole.FABLE, FableAi.PENDING_MARKER + " Looking at that with Gemini…",
          System.currentTimeMillis()
        ),
        countUnread = false
      )
      renderChat()
      TaskExecutor.executeAsync(
        Callable {
          val images = FableArchiveReader.loadVisionImages(appContext, uri)
          FableAi.vision(filesDir, projectPath, index, images, question)
        }
      ) { outcome ->
        deliverAiOutcome(filesDir, projectPath, outcome)
      }
      return
    }

    TaskExecutor.executeAsync(
      Callable {
        if (name.lowercase().endsWith(".zip") && FableIntake.looksLikeProjectZip(appContext, uri)) {
          val result = FableIntake.importZip(appContext, uri, name)
          result.projectDir to result.message
        } else {
          null to FableArchiveReader.summarize(appContext, uri)
        }
      }
    ) { outcome ->
      val message = outcome?.second ?: "I couldn't read that file."
      val importedDir = outcome?.first
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(FableRole.FABLE, message, System.currentTimeMillis()),
        countUnread = false
      )
      renderChat()
      if (importedDir != null) {
        IProjectManager.getInstance().openProject(importedDir)
        startActivity(Intent(requireContext(), EditorActivityKt::class.java))
      }
    }
  }

  // ------------------------------------------------------------- AI phase

  /** The AI status line: off without a key, or today's budget snapshot. */
  private fun renderAiStatus() {
    val binding = _binding ?: return
    if (!FableAi.isConfigured()) {
      binding.fableAiStatus.text =
        "AI is off. Add your Gemini key in Settings → Devxyz Fable to " +
          "unlock free AI chat in your language, image reading and fix " +
          "proposals for real build errors. Without a key, I never make " +
          "a network call — everything stays local."
      return
    }
    val snapshot = FableAiBudget.load(requireContext().filesDir)
    val totalQuota = FableAiBudget.QUOTA_PER_MODEL_PER_DAY * FableAiBudget.MODEL_CHAIN.size
    val limited = FableAiBudget.MODEL_CHAIN.filter { snapshot.usage(it).rateLimited }
    val text = StringBuilder()
    text.append("AI is on — models: ")
      .append(FableAiBudget.MODEL_CHAIN.joinToString(" → ")).append(".\n")
    text.append("Today: ").append(snapshot.totalAutoFix).append(" auto-fix + ")
      .append(snapshot.totalUser).append(" by request, of ").append(totalQuota)
      .append(" free calls. Resets around 3:00 PM.")
    if (limited.isNotEmpty()) {
      text.append("\nRate-limited today: ").append(limited.joinToString(", ")).append(".")
    }
    binding.fableAiStatus.text = text.toString()
  }

  /** Renders the ask-Gemini card and the patch proposal card. */
  private fun renderAiCards() {
    val binding = _binding ?: return
    val projectDir = if (GeneralPreferences.fableEnabled) currentProjectDir() else null

    val ask = projectDir?.let { FablePendingAi.pendingFix(it.absolutePath) }
    if (ask == null) {
      binding.fableAiCard.visibility = View.GONE
    } else {
      binding.fableAiBody.text = askCardBody(ask)
      binding.fableAiCard.visibility = View.VISIBLE
    }

    val proposal = projectDir?.let { FablePendingAi.pendingProposal(it.absolutePath) }
    if (proposal == null) {
      binding.fablePatchCard.visibility = View.GONE
    } else {
      binding.fablePatchTitle.text = getString(
        if (proposal.kind == FableAiCallKind.AUTO_FIX) {
          R.string.msg_fable_patch_card_title_fix
        } else {
          R.string.msg_fable_patch_card_title_change
        }
      )
      binding.fablePatchBody.text =
        proposal.summary + "\n\n" + FablePatcher.previewText(proposal)
      binding.fablePatchCard.visibility = View.VISIBLE
    }
  }

  private fun askCardBody(ask: FableAiFixRequest): String {
    val files = (ask.errors.map { it.relativePath } + ask.contextFiles)
      .filter { it.isNotEmpty() }
      .distinct()
    val listing = if (files.isEmpty()) {
      "• (no project files — just the end of the build output)"
    } else {
      files.joinToString("\n") { "• $it" }
    }
    return "This is attempt ${ask.attempt} of 2 for this error. If you tap " +
      "Allow, I'll send these files to Gemini together with the error " +
      "and the end of the build output:\n$listing\n" +
      "Nothing is sent if you tap Deny — and Gemini only proposes a fix; " +
      "the actual change needs your approval on a separate card."
  }

  private fun onAiCardAllow() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    val request = FablePendingAi.consumeFix(projectPath) ?: run {
      renderAiCards()
      return
    }
    renderAiCards()
    val filesDir = requireContext().filesDir
    FableChatStore.append(
      filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE,
        FableAi.PENDING_MARKER + " Asking Gemini for a fix, with the files you approved…",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderChat()

    val appContext = requireContext().applicationContext
    val index = FableIndexStore.load(filesDir, projectPath)
    TaskExecutor.executeAsync(
      Callable {
        FableAi.proposeFix(appContext, filesDir, projectDir, request, index)
      }
    ) { outcome ->
      deliverAiOutcome(filesDir, projectPath, outcome)
    }
  }

  private fun onAiCardDeny() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    FablePendingAi.denyFix(projectPath)
    FableChatStore.append(
      requireContext().filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE,
        "Okay — I didn't send anything to Gemini. The error is still " +
          "there whenever you want to build again.",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderAiCards()
  }

  private fun onPatchApply() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    val proposal = FablePendingAi.consumeProposal(projectPath) ?: run {
      renderAiCards()
      return
    }
    renderAiCards()
    val filesDir = requireContext().filesDir
    TaskExecutor.executeAsync(
      Callable {
        FablePatcher.apply(filesDir, projectDir, proposal)
      }
    ) { result ->
      when (result) {
        is FablePatcher.ApplyResult.Applied -> {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              FableRole.FABLE,
              "Applied — I saved a backup snapshot of your files first. " +
                "Rebuilding now: if the build passes, the fix stands; " +
                "if it fails, I'll roll everything back automatically.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
          FableBuildWatcher.rebuildProject(filesDir, projectPath)
        }
        is FablePatcher.ApplyResult.Rejected -> {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              FableRole.NOTICE,
              "I didn't apply that change: ${result.detail}. Your files " +
                "are untouched.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
        }
        null -> {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              FableRole.NOTICE,
              "Something went wrong while applying the change — nothing " +
                "was written.",
              System.currentTimeMillis()
            ),
            countUnread = false
          )
        }
      }
      renderChat()
    }
  }

  private fun onPatchDeny() {
    val projectDir = currentProjectDir() ?: return
    val projectPath = projectDir.absolutePath
    FablePendingAi.denyProposal(projectPath)
    FableChatStore.append(
      requireContext().filesDir,
      projectPath,
      FableChatMessage(
        FableRole.FABLE,
        "Okay, I left your files exactly as they are.",
        System.currentTimeMillis()
      ),
      countUnread = false
    )
    renderAiCards()
  }

  private fun currentProjectDir(): File? {
    val path = try {
      IProjectManager.getInstance().projectDirPath
    } catch (e: Exception) {
      null
    }
    if (path.isNullOrBlank()) {
      return null
    }
    val dir = File(path)
    return if (dir.isDirectory) dir else null
  }
}
