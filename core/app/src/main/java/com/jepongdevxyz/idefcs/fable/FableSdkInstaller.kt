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

import com.jepongdevxyz.idefcs.tasks.TaskExecutor
import com.jepongdevxyz.idefcs.utils.Environment
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Installs a missing Android SDK platform from inside the app, using the
 * command-line tools that the IDE setup already installed — the user never
 * has to open a terminal for this. The install is verified by checking that
 * the platform's android.jar actually exists afterwards.
 */
object FableSdkInstaller {

  private const val INSTALL_TIMEOUT_MINUTES = 10L

  /** The command a user can run manually, used as the honest fallback message. */
  fun manualCommand(api: Int): String {
    val manager = findSdkManager()
    return if (manager != null) {
      "yes | ${manager.absolutePath} --sdk_root=${Environment.ANDROID_HOME.absolutePath} " +
        "\"platforms;android-$api\""
    } else {
      "sdkmanager --sdk_root=${Environment.ANDROID_HOME.absolutePath} \"platforms;android-$api\""
    }
  }

  fun isPlatformInstalled(api: Int): Boolean {
    return try {
      File(Environment.ANDROID_HOME, "platforms/android-$api/android.jar").isFile
    } catch (e: Exception) {
      false
    }
  }

  /** Finds the sdkmanager executable installed by the IDE setup, if any. */
  fun findSdkManager(): File? {
    val toolsRoot = File(Environment.ANDROID_HOME, "cmdline-tools")
    if (!toolsRoot.isDirectory) {
      return null
    }
    val candidates = listOf(
      File(toolsRoot, "latest/bin/sdkmanager"),
      File(toolsRoot, "tools/bin/sdkmanager"),
      File(toolsRoot, "bin/sdkmanager")
    )
    for (candidate in candidates) {
      if (candidate.isFile) {
        return candidate
      }
    }
    return toolsRoot.walkTopDown().maxDepth(4).firstOrNull {
      it.isFile && it.name == "sdkmanager"
    }
  }

  /**
   * Installs `platforms;android-<api>` in the background. [onDone] receives
   * whether the platform is verifiably installed afterwards, plus a short
   * detail string for failure cases.
   */
  fun installPlatform(
    api: Int,
    onProgress: (percent: Int, status: String) -> Unit = { _, _ -> },
    onDone: (Boolean, String) -> Unit
  ) {
    TaskExecutor.executeAsync(
      Callable { installBlocking(api, onProgress) }
    ) { result ->
      val (success, detail) = result ?: (false to "the installer returned no result")
      onDone(success, detail)
    }
  }

  private fun installBlocking(
    api: Int,
    onProgress: (percent: Int, status: String) -> Unit
  ): Pair<Boolean, String> {
    if (isPlatformInstalled(api)) {
      return true to "already installed"
    }

    val manager = findSdkManager()
      ?: return false to "sdkmanager was not found in the IDE's cmdline-tools"

    return try {
      writeLicenses()

      val androidHome = Environment.ANDROID_HOME.absolutePath
      val processBuilder = ProcessBuilder(
        "sh", manager.absolutePath,
        "--sdk_root=$androidHome",
        "platforms;android-$api"
      )
      val env = processBuilder.environment()
      env["JAVA_HOME"] = Environment.JAVA_HOME.absolutePath
      env["ANDROID_HOME"] = androidHome
      env["ANDROID_SDK_ROOT"] = androidHome
      env["HOME"] = Environment.HOME.absolutePath
      // The bundled JDK is a Termux build: its java cannot even start unless
      // the linker can see the prefix libraries (libz.so.1 for libjli.so) —
      // "CANNOT LINK EXECUTABLE ... library libz.so.1 not found". Mirror the
      // environment the IDE's own ToolingServerRunner gives its processes.
      env["LD_LIBRARY_PATH"] = Environment.PREFIX.absolutePath + "/lib"
      env["TMPDIR"] = Environment.TMP_DIR.absolutePath
      env["CURL_CA_BUNDLE"] = Environment.PREFIX.absolutePath + "/etc/tls/cert.pem"
      env["SSL_CERT_FILE"] = Environment.PREFIX.absolutePath + "/etc/tls/cert.pem"
      env["PATH"] = Environment.JAVA_HOME.absolutePath + "/bin:" +
        Environment.BIN_DIR.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
      processBuilder.directory(Environment.ANDROID_HOME)
      processBuilder.redirectErrorStream(true)

      val process = processBuilder.start()

      // Answer "y" to any license prompt the pre-written hashes do not cover.
      val stdinThread = Thread {
        try {
          process.outputStream.bufferedWriter().use { writer ->
            while (process.isAlive) {
              writer.write("y\n")
              writer.flush()
              Thread.sleep(500)
            }
          }
        } catch (e: Exception) {
          // The process ended; nothing more to answer.
        }
      }
      stdinThread.isDaemon = true
      stdinThread.start()

      val output = StringBuilder()
      val percentPattern = Regex("(\\d{1,3})%")
      var lastPercent = -2
      var lastPhase = ""
      val stdoutThread = Thread {
        try {
          process.inputStream.bufferedReader().use { reader ->
            while (true) {
              val line = reader.readLine() ?: break
              synchronized(output) {
                if (output.length < 8000) output.append(line).append('\n')
              }
              // sdkmanager renders its progress as "[===>  ] NN% <phase>"
              // lines; forward real percentages (and phase changes) so the
              // UI progress bar tracks the install instead of guessing.
              val pct = percentPattern.find(line)?.groupValues?.get(1)
                ?.toIntOrNull()?.coerceIn(0, 100) ?: -1
              val phase = when {
                line.contains("Download", ignoreCase = true) ->
                  "Downloading the Android $api platform…"
                line.contains("Unzip", ignoreCase = true) ||
                  line.contains("Install", ignoreCase = true) ->
                  "Installing the Android $api platform…"
                line.contains("Loading", ignoreCase = true) ->
                  "Preparing the installer…"
                else -> lastPhase
              }
              if (pct != lastPercent || phase != lastPhase) {
                lastPercent = pct
                lastPhase = phase
                try {
                  onProgress(pct, phase.ifEmpty { "Running the installer…" })
                } catch (e: Exception) {
                  // Progress reporting must never break the install.
                }
              }
            }
          }
        } catch (e: Exception) {
          // Stream closed.
        }
      }
      stdoutThread.isDaemon = true
      stdoutThread.start()

      val finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
      if (!finished) {
        process.destroyForcibly()
        return false to "the install timed out after $INSTALL_TIMEOUT_MINUTES minutes"
      }
      stdoutThread.join(2000)

      if (isPlatformInstalled(api)) {
        true to "installed"
      } else {
        val tail = synchronized(output) { output.toString() }.trim().lines().lastOrNull { it.isNotBlank() }
        false to "exit ${process.exitValue()}" + (if (tail != null) ": $tail" else "")
      }
    } catch (e: Exception) {
      false to (e.message ?: "the installer hit an error")
    }
  }

  /**
   * Stops the Gradle daemon(s) for this IDE's Gradle user home. The
   * daemon loads the Android SDK once and caches the platform list for
   * its whole lifetime: a platform installed while a daemon from the
   * failed build is still alive stays invisible to the next build
   * ("android.jar does not exist" even though the file is on disk).
   * Stopping the daemon forces the restarted build to rescan the SDK.
   * Best-effort: uses the project's own gradlew when present, else the
   * downloaded distribution's gradle; silently skips if neither exists.
   */
  fun stopGradleDaemons(projectPath: String) {
    try {
      val projectDir = File(projectPath)
      val gradlew = File(projectDir, "gradlew")
      val command: List<String>? = when {
        gradlew.isFile -> listOf("sh", gradlew.absolutePath, "--stop")
        else -> findGradleDistribution()?.let {
          listOf("sh", File(it, "bin/gradle").absolutePath, "--stop")
        }
      }
      if (command == null) {
        return
      }
      val processBuilder = ProcessBuilder(command)
      processBuilder.directory(projectDir)
      val env = processBuilder.environment()
      env["JAVA_HOME"] = Environment.JAVA_HOME.absolutePath
      env["ANDROID_HOME"] = Environment.ANDROID_HOME.absolutePath
      env["ANDROID_SDK_ROOT"] = Environment.ANDROID_HOME.absolutePath
      env["GRADLE_USER_HOME"] = Environment.GRADLE_USER_HOME.absolutePath
      env["HOME"] = Environment.HOME.absolutePath
      env["LD_LIBRARY_PATH"] = Environment.PREFIX.absolutePath + "/lib"
      env["TMPDIR"] = Environment.TMP_DIR.absolutePath
      env["PATH"] = Environment.JAVA_HOME.absolutePath + "/bin:" +
        Environment.BIN_DIR.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
      processBuilder.redirectErrorStream(true)
      val process = processBuilder.start()
      try {
        process.outputStream.close()
      } catch (e: Exception) {
        // No input is needed for --stop.
      }
      // Output of --stop is a few lines; drain it so the pipe cannot fill.
      Thread {
        try {
          process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
          // Stream closed.
        }
      }.apply { isDaemon = true }.start()
      val finished = process.waitFor(60, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
      }
    } catch (e: Exception) {
      // Best-effort only: the restart proceeds either way.
    }
  }

  /** Finds an unpacked Gradle distribution in the IDE's Gradle user home. */
  private fun findGradleDistribution(): File? {
    return try {
      val dists = File(Environment.GRADLE_USER_HOME, "wrapper/dists")
      if (!dists.isDirectory) {
        return null
      }
      dists.walkTopDown().maxDepth(4).firstOrNull {
        it.isDirectory && File(it, "bin/gradle").isFile
      }
    } catch (e: Exception) {
      null
    }
  }

  /** Pre-accepts the standard Android SDK license hashes so no prompt appears. */
  private fun writeLicenses() {
    val licensesDir = File(Environment.ANDROID_HOME, "licenses")
    if (!licensesDir.exists()) {
      licensesDir.mkdirs()
    }
    val sdkLicense = File(licensesDir, "android-sdk-license")
    if (!sdkLicense.exists()) {
      sdkLicense.writeText(
        "\n8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
          "d56f5187479451eabf01fb78af6dfcb131a6481e\n" +
          "24333f8a63b6825ea9c5514f83c2829b004d1fee\n"
      )
    }
  }
}
