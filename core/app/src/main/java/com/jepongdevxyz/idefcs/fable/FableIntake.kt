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
import android.net.Uri
import com.jepongdevxyz.idefcs.utils.Environment
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Fable's project intake: the user sends a ZIP in chat, Fable checks that it
 * really is a Gradle project, extracts it into the IDE projects folder,
 * builds its knowledge index, and marks it for an automatic first build
 * (assembleDebug) when the editor opens it. The build outcome itself is
 * reported by the build watcher, exactly like a manual build.
 */
object FableIntake {

  private const val MAX_ENTRIES = 20_000
  private const val MAX_TOTAL_BYTES = 800L * 1024 * 1024

  /** Project path waiting for its automatic first build, consumed by the editor. */
  @Volatile
  var pendingAutoBuildPath: String? = null

  data class Result(val projectDir: File?, val message: String)

  /** True when the ZIP at [uri] contains a settings.gradle(.kts) with a root build file. */
  fun looksLikeProjectZip(context: Context, uri: Uri): Boolean {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(stream).use { zip ->
          var hasSettings = false
          var prefix = ""
          var hasRootBuild = false
          while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.replace('\\', '/')
            if (name == "settings.gradle.kts" || name == "settings.gradle") {
              hasSettings = true
              prefix = ""
            } else if (!hasSettings &&
              (name.endsWith("/settings.gradle.kts") || name.endsWith("/settings.gradle")) &&
              name.count { it == '/' } == 1
            ) {
              hasSettings = true
              prefix = name.substringBefore('/') + "/"
            }
            if (hasSettings &&
              (name == prefix + "build.gradle.kts" || name == prefix + "build.gradle")
            ) {
              hasRootBuild = true
            }
            zip.closeEntry()
          }
          hasSettings && hasRootBuild
        }
      } ?: false
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Extracts the project ZIP into the projects folder and indexes it.
   * Blocking; call off the UI thread.
   */
  fun importZip(context: Context, uri: Uri, zipDisplayName: String): Result {
    val tempDir = File(context.cacheDir, "fable-intake")
    return try {
      if (tempDir.exists()) tempDir.deleteRecursively()
      tempDir.mkdirs()

      val projectRoot = extractProject(context, uri, tempDir)
        ?: return Result(
          null,
          "This doesn't look like a complete Gradle project: there is no settings.gradle(.kts) " +
            "together with a root build.gradle(.kts) inside the ZIP."
        )

      val settingsText = readFile(File(projectRoot, "settings.gradle.kts")) +
        readFile(File(projectRoot, "settings.gradle"))
      val nameFromSettings = Regex("""rootProject\.name\s*=\s*['"]([^'"]+)['"]""")
        .find(settingsText)?.groupValues?.get(1)
      val projectName = sanitizeName(
        nameFromSettings ?: zipDisplayName.removeSuffix(".zip").removeSuffix(".ZIP")
      )

      val projectsDir = Environment.PROJECTS_DIR
      if (!projectsDir.exists()) projectsDir.mkdirs()
      var target = File(projectsDir, projectName)
      var suffix = 2
      while (target.exists()) {
        target = File(projectsDir, "$projectName-$suffix")
        suffix++
      }

      if (!projectRoot.renameTo(target)) {
        projectRoot.copyRecursively(target, overwrite = false)
        projectRoot.deleteRecursively()
      }

      val index = KnowledgeIndexBuilder.build(
        target, Environment.ANDROID_HOME, Environment.JAVA_HOME
      )
      FableIndexStore.save(context.filesDir, index)
      pendingAutoBuildPath = target.absolutePath

      Result(
        target,
        "I imported the project ${index.projectName} into DevxyzIDEProjects: " +
          "${index.modules.size} modules, ${index.totalFileCount} files, " +
          "Gradle ${index.gradleVersion ?: "—"} / AGP ${index.agpVersion ?: "—"}. " +
          "I'm opening it now and starting the first build (assembleDebug) — " +
          "I'll report the result in the project's chat."
      )
    } catch (e: Exception) {
      Result(null, "I couldn't import the ZIP: ${e.message ?: "an error occurred."}")
    } finally {
      try {
        if (tempDir.exists()) tempDir.deleteRecursively()
      } catch (e: Exception) {
        // A leftover cache dir is harmless.
      }
    }
  }

  /** Extracts into [tempDir] and returns the directory that holds settings.gradle. */
  private fun extractProject(context: Context, uri: Uri, tempDir: File): File? {
    var entryCount = 0
    var totalBytes = 0L
    var settingsDir: File? = null

    context.contentResolver.openInputStream(uri)?.use { stream ->
      ZipInputStream(stream).use { zip ->
        val tempCanonical = tempDir.canonicalPath
        while (true) {
          val entry = zip.nextEntry ?: break
          entryCount++
          if (entryCount > MAX_ENTRIES) {
            throw IllegalStateException("The ZIP has too many files (more than $MAX_ENTRIES).")
          }
          val name = entry.name.replace('\\', '/')
          val outFile = File(tempDir, name)
          if (!outFile.canonicalPath.startsWith(tempCanonical)) {
            throw IllegalStateException("There's a suspicious entry in the ZIP: $name")
          }
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { output ->
              val buffer = ByteArray(64 * 1024)
              while (true) {
                val read = zip.read(buffer)
                if (read <= 0) break
                totalBytes += read
                if (totalBytes > MAX_TOTAL_BYTES) {
                  throw IllegalStateException("The ZIP is too large when extracted (more than 800 MB).")
                }
                output.write(buffer, 0, read)
              }
            }
            if (name == "settings.gradle.kts" || name == "settings.gradle") {
              settingsDir = tempDir
            } else if (settingsDir == null &&
              (name.endsWith("/settings.gradle.kts") || name.endsWith("/settings.gradle")) &&
              name.count { it == '/' } == 1
            ) {
              settingsDir = File(tempDir, name.substringBefore('/'))
            }
          }
          zip.closeEntry()
        }
      }
    } ?: return null

    val root = settingsDir ?: return null
    val hasBuild = File(root, "build.gradle.kts").isFile || File(root, "build.gradle").isFile
    return if (hasBuild) root else null
  }

  private fun sanitizeName(name: String): String {
    val cleaned = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
    return cleaned.ifEmpty { "ImportedProject" }
  }

  private fun readFile(file: File): String {
    return try {
      if (file.isFile) file.readText() else ""
    } catch (e: Exception) {
      ""
    }
  }
}
