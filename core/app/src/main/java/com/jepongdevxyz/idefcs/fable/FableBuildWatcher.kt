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
import com.jepongdevxyz.idefcs.projects.builder.BuildService
import com.jepongdevxyz.idefcs.lookup.Lookup
import java.util.Collections

/**
 * Watches Gradle builds from inside GradleBuildService. Keeps a bounded tail
 * of the build output; when a build finishes it classifies failures with
 * [FableClassifier] and posts a proactive message into the project's Fable
 * chat (which is what makes the chat head appear). It never edits project
 * code. When a build fails because an SDK platform is missing, it does NOT
 * install silently: it raises an Allow/Deny card (FablePendingInstall) and
 * only an explicit Allow runs the in-app install. Every entry point is
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
      val projectPath = projectDir

      if (success) {
        val wasFailed = FableChatStore.setLastBuildFailed(filesDir, projectPath, false)
        if (wasFailed) {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              role = FableRole.FABLE,
              text = "All good — the build passes again. Nothing is left blocking it from the last failure.",
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
        val missingApi = result.missingPlatformApi
        if (result.kind == FableClassifier.Kind.SDK_MISSING && missingApi != null) {
          maybeAutoInstallPlatform(context, filesDir, projectPath, missingApi)
        }
      }
    } catch (e: Exception) {
      // The watcher must never break the build service.
    }
  }

  /**
   * Missing SDK platform: do NOT install silently. Raise an Allow/Deny
   * request ([FablePendingInstall]) and post a chat note pointing at the
   * card; the panel renders the card and only an Allow runs the install
   * via [runApprovedInstall]. One card per project at a time; a denial
   * dismisses the card and the next failed build asks again.
   */
  private fun maybeAutoInstallPlatform(
    context: Context,
    filesDir: java.io.File,
    projectPath: String,
    api: Int
  ) {
    try {
      if (!FablePendingInstall.request(projectPath, api)) {
        return
      }
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          role = FableRole.FABLE,
          text = "There's a card in my chat: Allow to let me install the Android $api " +
            "platform right here in the app, Deny if you don't want to. When you tap Allow, " +
            "I'll restart the build myself.",
          timeMillis = System.currentTimeMillis()
        ),
        countUnread = true
      )
    } catch (e: Exception) {
      // Raising the request is best-effort only.
    }
  }

  /**
   * Runs an install the user explicitly allowed from the card; reports in
   * the project chat and restarts the build on success.
   */
  fun runApprovedInstall(filesDir: java.io.File, projectPath: String, api: Int) {
    try {
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          role = FableRole.FABLE,
          text = "I'm installing the Android $api platform right here in the app — " +
            "no need to open a Terminal. I'll let you know when it's done.",
          timeMillis = System.currentTimeMillis()
        ),
        countUnread = true
      )
      FableInstallProgress.begin(api)
      FableSdkInstaller.installPlatform(
        api,
        onProgress = { percent, status -> FableInstallProgress.update(percent, status) }
      ) { success, detail ->
        FableInstallProgress.end()
        try {
          if (success) {
            FableChatStore.append(
              filesDir,
              projectPath,
              FableChatMessage(
                role = FableRole.FABLE,
                text = "Done — the Android $api platform is installed. " +
                  "I'll restart the build (assembleDebug).",
                timeMillis = System.currentTimeMillis()
              ),
              countUnread = true
            )
            try {
              Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                ?.executeTasks("assembleDebug")
            } catch (e: Exception) {
              // The user can start the build manually; the platform is in.
            }
          } else {
            FableChatStore.append(
              filesDir,
              projectPath,
              FableChatMessage(
                role = FableRole.NOTICE,
                text = "I tried to install the Android $api platform but it " +
                  "didn't finish ($detail). Manual way, in the IDE's Terminal: " +
                  FableSdkInstaller.manualCommand(api),
                timeMillis = System.currentTimeMillis()
              ),
              countUnread = true
            )
          }
        } catch (e: Exception) {
          // Reporting must never break anything.
        }
      }
    } catch (e: Exception) {
      // The approved install is best-effort only.
    }
  }
}
