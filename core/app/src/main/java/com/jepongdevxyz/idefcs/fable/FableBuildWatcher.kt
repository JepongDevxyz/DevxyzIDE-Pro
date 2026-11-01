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
import com.jepongdevxyz.idefcs.tasks.TaskExecutor
import java.util.concurrent.Callable
import com.jepongdevxyz.idefcs.lookup.Lookup
import com.jepongdevxyz.idefcs.services.builder.gradleDistributionParams
import com.jepongdevxyz.idefcs.tooling.api.messages.AndroidInitializationParams
import com.jepongdevxyz.idefcs.tooling.api.messages.InitializeProjectParams
import java.util.Collections

/**
 * Watches Gradle builds from inside GradleBuildService. Keeps a bounded tail
 * of the build output; when a build finishes it classifies failures with
 * [FableClassifier] and posts a proactive message into the project's Fable
 * chat (which is what makes the chat head appear). When a build fails
 * because an SDK platform is missing, it does NOT install silently: it
 * raises an Allow/Deny card (FablePendingInstall) and only an explicit
 * Allow runs the in-app install. In the AI phase it also judges applied
 * AI patches (a failed build rolls the patch back; a passing build
 * confirms it) and raises the ask-Gemini card for real code errors —
 * it still never edits project code on its own. Every entry point is
 * failure-proof so it can never break the build service.
 */
object FableBuildWatcher {

  private const val MAX_TAIL_LINES = 500

  private val tail = ArrayDeque<String>()

  /** When the watcher last saw a build finish; used to verify restarts. */
  @Volatile
  private var lastBuildFinishAt = 0L

  /**
   * True while a Fable-triggered re-initialization (forced fresh Gradle
   * connection after an SDK install) is in flight. The init's success
   * callback is a project sync, not a build outcome, so onBuildFinished
   * ignores it; no real build can finish in that window because the
   * build service rejects concurrent work.
   */
  @Volatile
  private var reinitializing = false

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
    if (reinitializing) {
      // A Fable-triggered re-initialization reporting back; not a build.
      synchronized(tail) { tail.clear() }
      return
    }
    lastBuildFinishAt = System.currentTimeMillis()
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
        val appliedPatch = FablePatcher.pendingVerdict(projectPath)
        if (appliedPatch != null) {
          // This build judged an applied AI patch — and it passed.
          FablePatcher.confirmApplied(projectPath)
          FableChatStore.setLastBuildFailed(filesDir, projectPath, false)
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              role = FableRole.FABLE,
              text = "The build passes with my fix applied — it's confirmed " +
                "working now, so I removed the backup snapshot.",
              timeMillis = System.currentTimeMillis()
            ),
            countUnread = true
          )
        } else {
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
        }
      } else {
        FableChatStore.setLastBuildFailed(filesDir, projectPath, true)
        // If this build was judging an applied AI patch, the patch did
        // not hold: roll the user's files back before anything else.
        var justRolledBack = false
        val appliedPatch = FablePatcher.pendingVerdict(projectPath)
        if (appliedPatch != null) {
          val restored = FablePatcher.rollback(projectPath)
          justRolledBack = true
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              role = FableRole.NOTICE,
              text = if (restored) {
                "My fix didn't hold — the build still fails, so I rolled " +
                  "your files back to exactly how they were before the " +
                  "patch. Nothing is lost. Build again when you're ready " +
                  "and I can try once more."
              } else {
                "My fix didn't hold and I could NOT restore every file " +
                  "from the backup snapshot. The snapshot is kept in " +
                  "fable/backups inside the app's files — tell me and " +
                  "we'll sort it out."
              },
              timeMillis = System.currentTimeMillis()
            ),
            countUnread = true
          )
        }
        val result = FableClassifier.classify(outputTail, projectPath)
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
        if (!justRolledBack) {
          maybeRaiseAiFix(filesDir, projectPath, result, outputTail)
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
   * The AI gate (plan §8, WIDENED 2026-11-01 on Jepong's order — "itulad
   * mo sayo, kahit ano na fix, kahit anong issue o problema": Fable must
   * be able to go after ANY project problem a file change can fix, the
   * way Spidey does). Any build failure whose class a patch could cure
   * raises the ask-Gemini card: compile/resource errors, dependency and
   * plugin resolution, configuration/script failures, out-of-memory
   * (the cure is a gradle.properties edit), and unknown failures. The
   * classes with a deterministic cure stay out: a missing SDK platform
   * has the installer card, and a daemon lock has no file fix at all.
   * The gate stays shut when no key is set, when today's AI budget is
   * used up or rate-limited, or when the incident already used its 2
   * patch attempts. Nothing is sent anywhere here; the card is what asks.
   */
  private fun maybeRaiseAiFix(
    filesDir: java.io.File,
    projectPath: String,
    result: FableClassifier.Result,
    outputTail: String
  ) {
    try {
      val aiKinds = setOf(
        FableClassifier.Kind.COMPILE_ERROR,
        FableClassifier.Kind.DEPENDENCY_NOT_FOUND,
        FableClassifier.Kind.PLUGIN_RESOLUTION,
        FableClassifier.Kind.CONFIGURE_FAILED,
        FableClassifier.Kind.OUT_OF_MEMORY,
        FableClassifier.Kind.UNKNOWN
      )
      if (result.kind !in aiKinds) return
      if (result.kind == FableClassifier.Kind.COMPILE_ERROR &&
        result.errorLocations.isEmpty()
      ) return
      if (!FableAi.isConfigured()) return
      if (FableAiBudget.load(filesDir).firstUsableModel() == null) return
      val keyErrors = if (result.errorLocations.isNotEmpty()) {
        result.errorLocations
      } else {
        listOf(
          FableClassifier.ErrorLocation(
            path = "",
            relativePath = "",
            line = 0,
            message = result.kind.name + ": " +
              result.message.lineSequence().firstOrNull().orEmpty().take(160)
          )
        )
      }
      val incidentKey = FablePatcher.incidentKey(keyErrors)
      val attempts = FablePatcher.attemptsFor(filesDir, projectPath, incidentKey)
      if (attempts >= 2) return
      val request = FableAiFixRequest(
        projectPath = projectPath,
        incidentKey = incidentKey,
        errors = result.errorLocations,
        buildTail = outputTail,
        attempt = attempts + 1,
        contextFiles = contextFilesFor(result, java.io.File(projectPath))
      )
      if (!FablePendingAi.requestFix(request)) return
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          role = FableRole.FABLE,
          text = "I can ask Gemini for a fix for this one — there's a card " +
            "in my chat listing the files I'd send. Nothing leaves your " +
            "phone unless you tap Allow.",
          timeMillis = System.currentTimeMillis()
        ),
        countUnread = true
      )
    } catch (e: Exception) {
      // Raising the AI request is best-effort only.
    }
  }

  /**
   * The standard evidence files for a failure class, filtered to the
   * ones that actually exist in this project, on top of the files the
   * build output itself named ([FableClassifier.Result.contextFiles]).
   */
  private fun contextFilesFor(
    result: FableClassifier.Result,
    projectDir: java.io.File
  ): List<String> {
    val candidates = linkedSetOf<String>()
    candidates.addAll(result.contextFiles)
    when (result.kind) {
      FableClassifier.Kind.DEPENDENCY_NOT_FOUND,
      FableClassifier.Kind.PLUGIN_RESOLUTION -> {
        candidates.add("settings.gradle")
        candidates.add("settings.gradle.kts")
        candidates.add("build.gradle")
        candidates.add("build.gradle.kts")
        candidates.add("gradle.properties")
        candidates.add("gradle/libs.versions.toml")
        candidates.add("app/build.gradle")
        candidates.add("app/build.gradle.kts")
      }
      FableClassifier.Kind.CONFIGURE_FAILED -> {
        candidates.add("settings.gradle")
        candidates.add("settings.gradle.kts")
        candidates.add("gradle.properties")
      }
      FableClassifier.Kind.OUT_OF_MEMORY -> {
        candidates.add("gradle.properties")
      }
      else -> {}
    }
    return candidates
      .filter { java.io.File(projectDir, it).isFile }
      .take(6)
  }

  /**
   * Starts assembleDebug for a project — used after the user applies an
   * AI patch, so the build can judge it. Reports honestly when the build
   * service cannot be reached.
   */
  fun rebuildProject(filesDir: java.io.File, projectPath: String) {
    TaskExecutor.executeAsync(
      Callable {
        var service: BuildService? = null
        var tries = 0
        while (service == null && tries < 4) {
          try {
            service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
          } catch (e: Exception) {
            // Service not ready yet; retry below.
          }
          if (service == null) {
            tries++
            if (tries < 4) {
              try {
                Thread.sleep(1500)
              } catch (e: Exception) {
                // Interrupted; keep going.
              }
            }
          }
        }
        val buildService = service ?: return@Callable false
        try {
          buildService.executeTasks("assembleDebug")
          true
        } catch (e: Exception) {
          false
        }
      }
    ) { started ->
      if (started != true) {
        try {
          FableChatStore.append(
            filesDir,
            projectPath,
            FableChatMessage(
              role = FableRole.NOTICE,
              text = "The change is applied, but I couldn't start the " +
                "rebuild myself. Tap Run to build — the result will " +
                "judge the fix.",
              timeMillis = System.currentTimeMillis()
            ),
            countUnread = true
          )
        } catch (e: Exception) {
          // Reporting must never break anything.
        }
      }
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
                text = "Done — the Android $api platform is installed.",
                timeMillis = System.currentTimeMillis()
              ),
              countUnread = true
            )
            restartBuild(filesDir, projectPath, api)
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

  /**
   * Restarts assembleDebug after an approved install — and VERIFIES it.
   * The old code fired executeTasks once and claimed success even when
   * the build service was not registered (fresh installs) or the call
   * failed asynchronously, so the restart silently never happened.
   * Now: retry the service lookup a few times; only claim the restart
   * once the service has accepted the tasks; if the execution future
   * fails and no build finish is seen afterwards, say so honestly.
   *
   * Before executing, the project is re-initialized with
   * forceNewConnection: the tooling server then drops its Gradle
   * connection and connects again, spawning a fresh daemon that rescans
   * the SDK and sees the just-installed platform. An external
   * `gradlew --stop` was NOT enough on-device (phone-proven 2026-11-01:
   * the restarted build still read the SDK as missing the platform,
   * while a full app restart — new server, new connection — saw it).
   * If the re-initialization fails, the daemon stop remains as fallback.
   */
  private fun restartBuild(filesDir: java.io.File, projectPath: String, api: Int) {
    val attemptAt = System.currentTimeMillis()
    TaskExecutor.executeAsync(
      Callable {
        var service: BuildService? = null
        var tries = 0
        while (service == null && tries < 4) {
          try {
            service = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
          } catch (e: Exception) {
            // Service not ready yet; retry below.
          }
          if (service == null) {
            tries++
            if (tries < 4) {
              try {
                Thread.sleep(1500)
              } catch (e: Exception) {
                // Interrupted; keep the flow moving.
              }
            }
          }
        }
        val buildService = service ?: return@Callable null
        // Force a fresh Gradle connection so the new daemon rescans the
        // SDK. The init's own callbacks are suppressed via [reinitializing].
        reinitializing = true
        var reconnected = false
        try {
          val params = InitializeProjectParams(
            directory = projectPath,
            gradleDistribution = gradleDistributionParams,
            androidParams = AndroidInitializationParams.DEFAULT,
            forceNewConnection = true
          )
          buildService.initializeProject(params)
            .get(5, java.util.concurrent.TimeUnit.MINUTES)
          reconnected = true
        } catch (e: Exception) {
          reconnected = false
        }
        if (!reconnected) {
          // Fallback: stop the daemon externally, then build anyway.
          reinitializing = false
          try {
            FableSdkInstaller.stopGradleDaemons(projectPath)
          } catch (e: Exception) {
            // Best-effort; the restart proceeds either way.
          }
        }
        try {
          buildService.executeTasks("assembleDebug")
        } catch (e: Exception) {
          reinitializing = false
          null
        }
      }
    ) { future ->
      reinitializing = false
      try {
        if (future == null) {
          appendRestartFailedNotice(filesDir, projectPath, api)
          return@executeAsync
        }
        FableChatStore.append(
          filesDir,
          projectPath,
          FableChatMessage(
            role = FableRole.FABLE,
            text = "Restarting the build (assembleDebug) now.",
            timeMillis = System.currentTimeMillis()
          ),
          countUnread = true
        )
        future.whenComplete { _, error ->
          if (error != null) {
            // A real build outcome also completes the watcher hook; only
            // report when no build finished after this restart attempt.
            TaskExecutor.executeAsync(
              Callable {
                try {
                  // Generous margin: a real build's finish hook can land
                  // a moment after its future completes.
                  Thread.sleep(8000)
                } catch (e: Exception) {
                  // Keep going; the check below is what matters.
                }
                lastBuildFinishAt < attemptAt
              }
            ) { stale ->
              if (stale == true) {
                appendRestartFailedNotice(filesDir, projectPath, api)
              }
            }
          }
        }
      } catch (e: Exception) {
        // Verification is best-effort; the install itself already succeeded.
      }
    }
  }

  private fun appendRestartFailedNotice(
    filesDir: java.io.File,
    projectPath: String,
    api: Int
  ) {
    try {
      FableChatStore.append(
        filesDir,
        projectPath,
        FableChatMessage(
          role = FableRole.NOTICE,
          text = "The Android $api platform is installed, but I couldn't restart " +
            "the build automatically. Please tap Run to build again.",
          timeMillis = System.currentTimeMillis()
        ),
        countUnread = true
      )
    } catch (e: Exception) {
      // Reporting must never break anything.
    }
  }
}
