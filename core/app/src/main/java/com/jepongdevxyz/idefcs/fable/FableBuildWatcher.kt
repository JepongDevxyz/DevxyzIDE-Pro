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
import com.jepongdevxyz.idefcs.projects.IProjectManager

/**
 * Watches Gradle builds from inside GradleBuildService. Keeps a bounded tail
 * of the build output; when a build finishes it classifies failures with
 * [FableClassifier] and posts a proactive message into the project's Fable
 * chat (which is what makes the chat head appear). Strictly observe-only:
 * it never changes, retries or cancels a build, and every entry point is
 * failure-proof so it can never break the build service.
 */
object FableBuildWatcher {

  private const val MAX_TAIL_LINES = 500

  private val tail = ArrayDeque<String>()

  fun onOutputLine(line: String) {
    try {
      synchronized(tail) {
        tail.addLast(line)
        while (tail.size > MAX_TAIL_LINES) {
          tail.removeFirst()
        }
      }
    } catch (e: Exception) {
      // The watcher must never break the build output stream.
    }
  }

  fun onBuildFinished(context: Context, success: Boolean) {
    try {
      val outputTail = synchronized(tail) {
        val joined = tail.joinToString("\n")
        tail.clear()
        joined
      }

      if (!GeneralPreferences.fableEnabled) {
        return
      }

      val projectDir = try {
        IProjectManager.getInstance().projectDirPath
      } catch (e: Exception) {
        null
      } ?: return

      val filesDir = context.filesDir
      val projectPath = projectDir.absolutePath

      if (success) {
        val wasFailed = FableChatStore.setLastBuildFailed(filesDir, projectPath, false)
        if (wasFailed) {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              role = FableRole.FABLE,
              text = "Ayos — pumasa na ulit ang build. Wala nang nakabara mula sa huling failure.",
              timeMillis = System.currentTimeMillis()
            ),
            countUnread = true
          )
        }
      } else {
        FableChatStore.setLastBuildFailed(filesDir, projectPath, true)
        val result = FableClassifier.classify(outputTail)
        FableChatStore.append(
          filesDir,
          projectPath,
          FableChatMessage(
            role = FableRole.NOTICE,
            text = result.message,
            timeMillis = System.currentTimeMillis()
          ),
          countUnread = true
        )
      }
    } catch (e: Exception) {
      // The watcher must never break the build service.
    }
  }
}
