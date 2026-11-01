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

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies and rolls back AI patch proposals (plan §§5, 8, 9). The rails:
 * a backup snapshot of every touched file is taken BEFORE anything is
 * written; all edits are verified against the current file contents before
 * any write, so a patch applies completely or not at all; after the user
 * applies a patch, the next build's result is the judge — the build
 * watcher consults [pendingVerdict] and calls [rollback] when it failed.
 *
 * Backups live in filesDir/fable/backups/<proposalId>/ with a manifest of
 * project-relative paths and "created" flags, so rollback can restore
 * edited files and delete created ones.
 */
object FablePatcher {

  /** An applied patch whose build verdict is still outstanding. */
  data class AppliedPatch(
    val proposalId: Long,
    val projectPath: String,
    val backupDir: File,
    val kind: FableAiCallKind,
    val incidentKey: String?,
    val summary: String
  )

  private val applied = mutableMapOf<String, AppliedPatch>()

  // ------------------------------------------------------------ incidents

  private fun incidentsFile(filesDir: File): File {
    val dir = File(filesDir, "fable")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "incidents.json")
  }

  /** AI patch attempts already used for one incident (max 2, plan §8). */
  @Synchronized
  fun attemptsFor(filesDir: File, projectPath: String, incidentKey: String): Int {
    return try {
      val file = incidentsFile(filesDir)
      if (!file.isFile) return 0
      val json = JSONObject(file.readText())
      json.optInt(projectPath + "|" + incidentKey, 0)
    } catch (e: Exception) {
      0
    }
  }

  @Synchronized
  fun recordAttempt(filesDir: File, projectPath: String, incidentKey: String) {
    try {
      val file = incidentsFile(filesDir)
      val json = if (file.isFile) JSONObject(file.readText()) else JSONObject()
      val key = projectPath + "|" + incidentKey
      json.put(key, json.optInt(key, 0) + 1)
      // Keep the store small: only the most recent incidents matter.
      if (json.length() > 40) {
        val trimmed = JSONObject()
        val keys = json.keys().asSequence().toList().takeLast(40)
        for (k in keys) trimmed.put(k, json.optInt(k, 0))
        file.writeText(trimmed.toString())
      } else {
        file.writeText(json.toString())
      }
    } catch (e: Exception) {
      // Attempt bookkeeping must never break the patch flow.
    }
  }

  /** Stable identity of one build-failure incident from its error locations. */
  fun incidentKey(errors: List<FableClassifier.ErrorLocation>): String {
    val canonical = errors
      .map { it.relativePath + ":" + it.message }
      .sorted()
      .joinToString("|")
    return canonical.hashCode().toString()
  }

  // ------------------------------------------------------------ preview

  /**
   * A chat/card-ready diff preview: per file, the removed SEARCH text and
   * the added replacement, capped so a huge patch stays readable.
   */
  fun previewText(proposal: FablePatchProposal): String {
    val out = StringBuilder()
    for (edit in proposal.edits) {
      if (out.isNotEmpty()) out.append("\n")
      out.append("── ").append(edit.relativePath)
      out.append(if (edit.isCreate) " (new file)" else "").append('\n')
      if (edit.isCreate) {
        appendCapped(out, edit.replace, "+ ", MAX_PREVIEW_LINES)
      } else {
        appendCapped(out, edit.search ?: "", "- ", MAX_PREVIEW_LINES / 2)
        appendCapped(out, edit.replace, "+ ", MAX_PREVIEW_LINES / 2)
      }
    }
    return out.toString().trimEnd()
  }

  private fun appendCapped(out: StringBuilder, text: String, prefix: String, maxLines: Int) {
    val lines = text.lines()
    val shown = lines.take(maxLines)
    for (line in shown) {
      out.append(prefix).append(line).append('\n')
    }
    if (lines.size > shown.size) {
      out.append(prefix).append("… (").append(lines.size - shown.size)
        .append(" more lines)").append('\n')
    }
  }

  private const val MAX_PREVIEW_LINES = 60

  // ------------------------------------------------------------ apply

  sealed class ApplyResult {
    data class Applied(val backupDir: File) : ApplyResult()
    data class Rejected(val detail: String) : ApplyResult()
  }

  /**
   * Verifies every edit against the files on disk, snapshots backups, then
   * writes. Any mismatch rejects the whole patch with nothing written.
   * Blocking file IO; call off the UI thread.
   */
  fun apply(filesDir: File, projectDir: File, proposal: FablePatchProposal): ApplyResult {
    // Pass 1: compute every new file content in memory; write nothing yet.
    val newContents = mutableListOf<Pair<File, String>>()
    for (edit in proposal.edits) {
      val target = File(projectDir, edit.relativePath)
      if (!isInside(projectDir, target)) {
        return ApplyResult.Rejected("${edit.relativePath} points outside the project")
      }
      if (edit.isCreate) {
        if (target.exists()) {
          return ApplyResult.Rejected("${edit.relativePath} already exists")
        }
        newContents.add(target to edit.replace)
      } else {
        if (!target.isFile) {
          return ApplyResult.Rejected("${edit.relativePath} no longer exists")
        }
        val current = try {
          target.readText()
        } catch (e: Exception) {
          return ApplyResult.Rejected("cannot read ${edit.relativePath}")
        }
        val search = edit.search ?: ""
        if (search.isEmpty() || !current.contains(search)) {
          return ApplyResult.Rejected(
            "the code in ${edit.relativePath} has changed since the proposal — " +
              "the patch no longer matches"
          )
        }
        newContents.add(target to current.replaceFirst(search, edit.replace))
      }
    }

    // Pass 2: snapshot the originals, then write the new contents.
    val backupDir = File(File(filesDir, "fable/backups"), proposal.id.toString())
    try {
      if (backupDir.exists()) backupDir.deleteRecursively()
      backupDir.mkdirs()
      val manifest = JSONArray()
      for ((index, pair) in newContents.withIndex()) {
        val target = pair.first
        val entry = JSONObject()
        entry.put("path", target.relativeTo(projectDir).path)
        entry.put("created", !target.isFile)
        if (target.isFile) {
          val copy = File(backupDir, "file-$index")
          target.copyTo(copy, overwrite = true)
          entry.put("backup", copy.name)
        }
        manifest.put(entry)
      }
      File(backupDir, "manifest.json").writeText(manifest.toString())

      for (pair in newContents) {
        pair.first.parentFile?.mkdirs()
        pair.first.writeText(pair.second)
      }
    } catch (e: Exception) {
      return ApplyResult.Rejected("failed while writing: ${e.message ?: "IO error"}")
    }

    synchronized(applied) {
      applied[proposal.projectPath] = AppliedPatch(
        proposalId = proposal.id,
        projectPath = proposal.projectPath,
        backupDir = backupDir,
        kind = proposal.kind,
        incidentKey = proposal.incidentKey,
        summary = proposal.summary
      )
    }
    if (proposal.incidentKey != null) {
      recordAttempt(filesDir, proposal.projectPath, proposal.incidentKey)
    }
    return ApplyResult.Applied(backupDir)
  }

  // ------------------------------------------------------------ verdict

  /** The applied patch still waiting for its build verdict, if any. */
  fun pendingVerdict(projectPath: String): AppliedPatch? {
    return synchronized(applied) { applied[projectPath] }
  }

  /** The build passed: the patch stands; forget its rollback snapshot. */
  fun confirmApplied(projectPath: String) {
    val patch = synchronized(applied) { applied.remove(projectPath) } ?: return
    try {
      patch.backupDir.deleteRecursively()
    } catch (e: Exception) {
      // A leftover backup folder is harmless.
    }
  }

  /** The build failed: restore every file from the snapshot. */
  fun rollback(projectPath: String): Boolean {
    val patch = synchronized(applied) { applied.remove(projectPath) } ?: return false
    return try {
      val manifest = JSONArray(File(patch.backupDir, "manifest.json").readText())
      val projectDir = File(patch.projectPath)
      for (i in 0 until manifest.length()) {
        val entry = manifest.optJSONObject(i) ?: continue
        val target = File(projectDir, entry.optString("path"))
        if (entry.optBoolean("created")) {
          if (target.isFile) target.delete()
        } else {
          val backup = File(patch.backupDir, entry.optString("backup"))
          if (backup.isFile) {
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
          }
        }
      }
      patch.backupDir.deleteRecursively()
      true
    } catch (e: Exception) {
      false
    }
  }

  private fun isInside(projectDir: File, target: File): Boolean {
    return try {
      val base = projectDir.canonicalPath
      val path = target.canonicalPath
      path == base || path.startsWith(base + File.separator)
    } catch (e: Exception) {
      false
    }
  }
}
