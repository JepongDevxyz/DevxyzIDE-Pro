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

  data class Result(val kind: Kind, val message: String, val missingPlatformApi: Int? = null)

  private val missingPlatformJarPattern = Regex("""platforms/android-(\d+)/android\.jar does not exist""")

  private val missingPlatformPathPattern = Regex("""platforms;android-(\d+)""")

  private val missingTargetPattern = Regex("""target with hash string 'android-(\d+)'""")

  private val coordinatePattern = Regex("""([\w.\-]+:[\w.\-]+:[\w.\-]+)""")

  private val kotlinErrorPattern = Regex("""^e: (file://)?(.+?):(\d+):(\d+) (.+)$""")

  private val javaErrorPattern = Regex("""^(.+?\.(?:java|kt)):(\d+): error: (.+)$""")

  fun classify(output: String): Result {
    val lines = output.lines()

    if (output.contains("OutOfMemoryError") ||
      output.contains("Java heap space") ||
      output.contains("GC overhead limit")
    ) {
      return Result(
        Kind.OUT_OF_MEMORY,
        "May napansin ako: naubusan ng memory ang build (OutOfMemoryError). " +
          "Karaniwang lunas ay liitan ang heap ng Gradle/Kotlin sa gradle.properties, " +
          "o isara ang ibang mabibigat na project habang nagbu-build."
      )
    }

    if (output.contains("Timeout waiting to lock") ||
      output.contains("Could not acquire lock") ||
      output.contains("It is currently in use by another Gradle instance")
    ) {
      return Result(
        Kind.LOCK_OR_DAEMON,
        "May napansin ako: may lumang Gradle daemon o lock na humahawak sa project, " +
          "kaya hindi makausad ang build. Karaniwang nalilinis ito ng isang restart ng phone; " +
          "huwag mag-Clear Data at huwag burahin ang buong .gradle."
      )
    }

    if (output.contains("Error resolving plugin") || output.contains("plugin [id:")) {
      val coordinate = coordinatePattern.find(output)?.groupValues?.get(1)
      val detail = if (coordinate != null) " ($coordinate)" else ""
      return Result(
        Kind.PLUGIN_RESOLUTION,
        "May napansin ako: hindi naresolba ang isang Gradle plugin$detail noong " +
          "kino-configure ang project. Tiningnan ko ang mga repository na hinanapan — " +
          "kung wala roon ang local plugin repository ng IDE, iyon ang unang dapat ayusin; " +
          "kung naroon naman ito, maaaring network o nawawalang plugin version ang dahilan."
      )
    }

    val missingApi = missingPlatformJarPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
      ?: missingPlatformPathPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
      ?: missingTargetPattern.find(output)?.groupValues?.get(1)?.toIntOrNull()
    if (missingApi != null) {
      return Result(
        Kind.SDK_MISSING,
        "May napansin ako: kulang ang Android $missingApi platform sa SDK ng IDE — " +
          "ito ang kailangan ng project (compileSdk $missingApi), kaya huminto ang " +
          "configuration. Kailangan muna itong ma-install.",
        missingApi
      )
    }

    if (output.contains("SDK location not found") ||
      output.contains("Failed to find Platform SDK") ||
      output.contains("Failed to find target with hash")
    ) {
      return Result(
        Kind.SDK_MISSING,
        "May napansin ako: hindi makita ng build ang Android SDK o ang platform na " +
          "kailangan nito. Suriin kung tapos ang IDE setup at kung naka-install ang " +
          "platform na hinihingi ng project."
      )
    }

    val compileErrors = mutableListOf<String>()
    for (line in lines) {
      val kotlinMatch = kotlinErrorPattern.find(line.trim())
      if (kotlinMatch != null) {
        val file = kotlinMatch.groupValues[2].substringAfterLast('/')
        compileErrors.add("$file:${kotlinMatch.groupValues[3]} — ${kotlinMatch.groupValues[5]}")
        continue
      }
      val javaMatch = javaErrorPattern.find(line.trim())
      if (javaMatch != null) {
        val file = javaMatch.groupValues[1].substringAfterLast('/')
        compileErrors.add("$file:${javaMatch.groupValues[2]} — ${javaMatch.groupValues[3]}")
      }
      if (compileErrors.size >= 3) break
    }
    if (compileErrors.isNotEmpty()) {
      val listing = compileErrors.joinToString("\n") { "• $it" }
      return Result(
        Kind.COMPILE_ERROR,
        "May napansin ako: may error sa code ang build.\n$listing\n" +
          "Iyan ang unang dapat ayusin — nasa file at linya na binanggit ang problema."
      )
    }

    if (output.contains("Could not find") || output.contains("Could not resolve")) {
      val coordinate = coordinatePattern.find(output)?.groupValues?.get(1)
      val detail = if (coordinate != null) ": $coordinate" else ""
      return Result(
        Kind.DEPENDENCY_NOT_FOUND,
        "May napansin ako: may dependency na hindi mahanap$detail. Suriin ang " +
          "eksaktong coordinate at bersyon sa build file, at kung online ang device — " +
          "ang maling bersyon at ang kawalan ng network ang karaniwang dahilan."
      )
    }

    if (output.contains("A problem occurred configuring")) {
      val project = Regex("""configuring project '([^']+)'""").find(output)?.groupValues?.get(1)
      val detail = if (project != null) " ($project)" else ""
      return Result(
        Kind.CONFIGURE_FAILED,
        "May napansin ako: pumalya ang configuration ng project$detail. Ang eksaktong " +
          "sanhi ay nasa \"What went wrong\" na bahagi ng build output — buksan mo iyon at " +
          "ipakita sa akin kung gusto mong tulungan kitang basahin."
      )
    }

    val whatWentWrong = extractWhatWentWrong(lines)
    return if (whatWentWrong != null) {
      Result(
        Kind.UNKNOWN,
        "May napansin ako: pumalya ang build. Ito ang sabi ng Gradle:\n$whatWentWrong\n" +
          "Hindi pa ito klase na kilala ng lokal kong classifier — ang AI analysis ay " +
          "darating sa susunod na phase."
      )
    } else {
      Result(
        Kind.UNKNOWN,
        "May napansin ako: pumalya ang build, pero walang malinaw na sanhi sa huling " +
          "bahagi ng output na nakita ko. Buksan ang Build output at ipakita sa akin ang " +
          "\"What went wrong\" na bahagi."
      )
    }
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
