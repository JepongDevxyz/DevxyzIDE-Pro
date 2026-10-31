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
 * code; its one action is installing a missing SDK platform in-app (with a
 * chat report) when a build fails for exactly that reason. Every entry
 * point is failure-proof so it can never break the build service.
 */
object FableBuildWatcher {

  private const val MAX_TAIL_LINES = 500

  private val tail = ArrayDeque<String>()

  /** (project path + api) pairs Fable already tried to auto-install for. */
  private val installAttempted: MutableSet<String> =
    Collections.synchronizedSet(mutableSetOf<String>())

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
        synchronized(installAttempted) {
          installAttempted.removeAll { it.startsWith("$projectPath:") }
        }
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
   * Direct-in-app fix for a missing SDK platform: Fable installs it with the
   * IDE's own command-line tools, reports the outcome in the chat, and
   * restarts the build once on success. Attempted once per project+platform
   * until a build succeeds, so a broken install can never loop forever.
   */
  private fun maybeAutoInstallPlatform(
    context: Context,
    filesDir: java.io.File,
    projectPath: String,
    api: Int
  ) {
    try {
      if (!installAttempted.add("$projectPath:$api")) {
        return
      }
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          role = FableRole.FABLE,
          text = "Ini-install ko na ang Android $api platform dito mismo sa app — " +
            "hindi mo na kailangang mag-Terminal. Aabisuhan kita pag tapos.",
          timeMillis = System.currentTimeMillis()
        ),
        countUnread = true
      )
      FableSdkInstaller.installPlatform(api) { success, detail ->
        try {
          if (success) {
            FableChatStore.append(
              filesDir,
              projectPath,
              FableChatMessage(
                role = FableRole.FABLE,
                text = "Tapos na — naka-install na ang Android $api platform. " +
                  "Sisimulan ko ulit ang build (assembleDebug).",
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
            // Let the NEXT failed build try the install again: a transient
            // failure (network, timeout) must not disable the fix for the
            // whole session. Still at most one attempt per failed build.
            synchronized(installAttempted) {
              installAttempted.remove("$projectPath:$api")
            }
            FableChatStore.append(
              filesDir,
              projectPath,
              FableChatMessage(
                role = FableRole.NOTICE,
                text = "Sinubukan kong i-install ang Android $api platform pero hindi " +
                  "natuloy ($detail). Manual na paraan sa Terminal ng IDE: " +
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
      // The auto-install is best-effort only.
    }
  }
}
