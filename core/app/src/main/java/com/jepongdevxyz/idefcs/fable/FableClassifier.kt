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

/**
 * Deterministic, offline classifier for failed Gradle builds. It reads the
 * tail of the build output and produces the notice Fable posts in chat.
 * No guessing: when nothing known matches, it reports the build's own
 * "What went wrong" section verbatim instead of inventing a cause.
 */
object FableClassifier {

  enum class Kind {
    PLUGIN_RESOLUTION,
    DEPENDENCY_NOT_FOUND,
    COMPILE_ERROR,
    SDK_MISSING,
    OUT_OF_MEMORY,
    LOCK_OR_DAEMON,
    CONFIGURE_FAILED,
    UNKNOWN
  }

  data class Result(
    val kind: Kind,
    val message: String,
    val missingPlatformApi: Int? = null,
    /** Structured code/resource error locations, for the AI gate (§8). */
    val errorLocations: List<ErrorLocation> = emptyList(),
    /**
     * Project files the output itself names (e.g. "Build file '...'
     * line: 12"), made relative to the project root. The AI gate sends
     * these as context for non-compile failures.
     */
    val contextFiles: List<String> = emptyList()
  )

  /**
   * One concrete compiler/resource error. [path] is the path exactly as
   * printed in the build output; [relativePath] is the same path made
   * relative to the project root when the root is known.
   */
  data class ErrorLocation(
    val path: String,
    val relativePath: String,
    val line: Int,
    val message: String
  )

  private val missingPlatformJarPattern = Regex("""platforms/android-(\d+)/android\.jar does not exist""")

  private val missingPlatformPathPattern = Regex("""platforms;android-(\d+)""")

  private val missingTargetPattern = Regex("""target with hash string 'android-(\d+)'""")

  private val coordinatePattern = Regex("""([\w.\-]+:[\w.\-]+:[\w.\-]+)""")

  private val kotlinErrorPattern = Regex("""^e: (file://)?(.+?):(\d+):(\d+) (.+)$""")

  private val javaErrorPattern = Regex("""^(.+?\.(?:java|kt|xml)):(\d+): error: (.+)$""")

  private val buildFilePattern = Regex("""Build file '([^']+)' line: \d+""")

  fun classify(output: String, projectPath: String? = null): Result {
    val lines = output.lines()

    if (output.contains("OutOfMemoryError") ||
      output.contains("Java heap space") ||
      output.contains("GC overhead limit")
    ) {
      return Result(
        Kind.OUT_OF_MEMORY,
        "I noticed something: the build ran out of memory (OutOfMemoryError). " +
          "The usual fix is to lower the Gradle/Kotlin heap in gradle.properties, " +
          "or close other heavy projects while building."
      )
    }

    if (output.contains("Timeout waiting to lock") ||
      output.contains("Could not acquire lock") ||
      output.contains("It is currently in use by another Gradle instance")
    ) {
      return Result(
        Kind.LOCK_OR_DAEMON,
        "I noticed something: a stale Gradle daemon or lock is holding the project, " +
          "so the build cannot proceed. A phone restart usually clears this; " +
          "do not Clear Data and do not delete the whole .gradle."
      )
    }

    if (output.contains("Error resolving plugin") || output.contains("plugin [id:")) {
      val coordinate = coordinatePattern.find(output)?.groupValues?.get(1)
      val detail = if (coordinate != null) " ($coordinate)" else ""
      return Result(
        Kind.PLUGIN_RESOLUTION,
        "I noticed something: a Gradle plugin$detail could not be resolved while " +
          "configuring the project. I checked the repositories that were searched — " +
          "if the IDE's local plugin repository is not among them, that is the first thing to fix; " +
          "if it is there, the cause may be the network or a missing plugin version."
      )
    }

    val missingApi = missingPlatformJarPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
      ?: missingPlatformPathPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
      ?: missingTargetPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
    if (missingApi != null) {
      return Result(
        Kind.SDK_MISSING,
        "I noticed something: the Android $missingApi platform is missing from the IDE's SDK — " +
          "the project needs it (compileSdk $missingApi), so the " +
          "configuration stopped. It has to be installed first.",
        missingApi
      )
    }

    if (output.contains("SDK location not found") ||
      output.contains("Failed to find Platform SDK") ||
      output.contains("Failed to find target with hash")
    ) {
      return Result(
        Kind.SDK_MISSING,
        "I noticed something: the build cannot find the Android SDK or the platform it " +
          "needs. Check that the IDE setup finished and that the platform " +
          "the project asks for is installed."
      )
    }

    val compileErrors = mutableListOf<String>()
    val locations = mutableListOf<ErrorLocation>()
    for (line in lines) {
      val kotlinMatch = kotlinErrorPattern.find(line.trim())
      if (kotlinMatch != null) {
        val path = kotlinMatch.groupValues[2]
        val file = path.substringAfterLast('/')
        if (compileErrors.size < 3) {
          compileErrors.add("$file:${kotlinMatch.groupValues[3]} — ${kotlinMatch.groupValues[5]}")
        }
        if (locations.size < 8) {
          locations.add(
            ErrorLocation(
              path = path,
              relativePath = relativize(path, projectPath),
              line = kotlinMatch.groupValues[3].toIntOrNull() ?: 0,
              message = kotlinMatch.groupValues[5]
            )
          )
        }
        continue
      }
      val javaMatch = javaErrorPattern.find(line.trim())
      if (javaMatch != null) {
        val path = javaMatch.groupValues[1]
        val file = path.substringAfterLast('/')
        if (compileErrors.size < 3) {
          compileErrors.add("$file:${javaMatch.groupValues[2]} — ${javaMatch.groupValues[3]}")
        }
        if (locations.size < 8) {
          locations.add(
            ErrorLocation(
              path = path,
              relativePath = relativize(path, projectPath),
              line = javaMatch.groupValues[2].toIntOrNull() ?: 0,
              message = javaMatch.groupValues[3]
            )
          )
        }
      }
    }
    if (compileErrors.isNotEmpty()) {
      val listing = compileErrors.joinToString("\n") { "• $it" }
      return Result(
        Kind.COMPILE_ERROR,
        "I noticed something: the build has a code error.\n$listing\n" +
          "That is the first thing to fix — the problem is at the file and line mentioned.",
        errorLocations = locations
      )
    }

    if (output.contains("Could not find") || output.contains("Could not resolve")) {
      val coordinate = coordinatePattern.find(output)?.groupValues?.get(1)
      val detail = if (coordinate != null) ": $coordinate" else ""
      return Result(
        Kind.DEPENDENCY_NOT_FOUND,
        "I noticed something: a dependency could not be found$detail. Check the " +
          "exact coordinate and version in the build file, and whether the device is online — " +
          "a wrong version and no network are the usual causes.",
        contextFiles = mentionedBuildFiles(output, projectPath)
      )
    }

    if (output.contains("A problem occurred configuring")) {
      val project = Regex("""configuring project '([^']+)'""").find(output)?.groupValues?.get(1)
      val detail = if (project != null) " ($project)" else ""
      return Result(
        Kind.CONFIGURE_FAILED,
        "I noticed something: project configuration failed$detail. The exact " +
          "cause is in the \"What went wrong\" section of the build output — open it and " +
          "show it to me if you want help reading it.",
        contextFiles = mentionedBuildFiles(output, projectPath)
      )
    }

    val whatWentWrong = extractWhatWentWrong(lines)
    return if (whatWentWrong != null) {
      Result(
        Kind.UNKNOWN,
        "I noticed something: the build failed. Gradle says:\n$whatWentWrong\n" +
          "This is not a class my local classifier knows yet. Show it to me " +
          "in the chat and I'll help read it.",
        contextFiles = mentionedBuildFiles(output, projectPath)
      )
    } else {
      Result(
        Kind.UNKNOWN,
        "I noticed something: the build failed, but there is no clear cause in the last " +
          "part of the output I saw. Open the Build output and show me the " +
          "\"What went wrong\" section."
      )
    }
  }

  /** Files named by "Build file '<path>' line: N" lines, relativized. */
  private fun mentionedBuildFiles(output: String, projectPath: String?): List<String> {
    val found = linkedSetOf<String>()
    for (match in buildFilePattern.findAll(output)) {
      found.add(relativize(match.groupValues[1], projectPath))
      if (found.size >= 4) break
    }
    return found.toList()
  }

  private fun relativize(path: String, projectPath: String?): String {
    if (projectPath.isNullOrEmpty()) return path
    val prefix = projectPath.trimEnd('/') + "/"
    return if (path.startsWith(prefix)) path.substring(prefix.length) else path
  }

  private fun extractWhatWentWrong(lines: List<String>): String? {
    val start = lines.indexOfFirst { it.contains("* What went wrong:") }
    if (start == -1) return null
    val snippet = StringBuilder()
    for (i in start + 1 until minOf(lines.size, start + 5)) {
      val line = lines[i].trim()
      if (line.startsWith("* Try:")) break
      if (line.isNotEmpty()) {
        if (snippet.isNotEmpty()) snippet.append('\n')
        snippet.append(line)
      }
    }
    return snippet.toString().takeIf { it.isNotEmpty() }
  }
}
