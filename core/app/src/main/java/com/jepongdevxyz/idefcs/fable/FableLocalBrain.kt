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
 * Fable's local, deterministic brain for Phase 2: it answers questions about
 * the opened project straight from the [KnowledgeIndex] — no network, no API.
 * Anything beyond local knowledge gets an honest answer: the AI chat arrives
 * in the next phase and needs the user's own Gemini key in Settings.
 */
object FableLocalBrain {

  fun reply(userText: String, index: KnowledgeIndex?, lastBuildFailed: Boolean): String {
    val text = userText.lowercase()

    if (index == null) {
      return "I'm still building the project map — try again once the project map " +
        "appears above, or tap Refresh map. For now I can answer " +
        "questions about your project map."
    }

    if (containsAny(text, "module", "modulo")) {
      val names = index.modules.take(12).joinToString(", ") { it.name }
      val more = if (index.modules.size > 12) " and more" else ""
      return "The project ${index.projectName} has ${index.modules.size} modules: " +
        "$names$more."
    }

    if (containsAny(text, "file")) {
      return "${index.projectName} has ${index.totalFileCount} files " +
        "(excluding the build, .gradle, .git and .idea folders)."
    }

    if (containsAny(text, "dependenc", "library", "libraries")) {
      val sample = index.modules.flatMap { it.dependencies }.distinct().take(10)
      val listing = if (sample.isEmpty()) ""
        else "\nExamples: " + sample.joinToString(", ")
      return "The project has ${index.dependencyCount} dependency entries across all modules.$listing"
    }

    if (containsAny(text, "gradle")) {
      return "Toolchain of ${index.projectName}: Gradle ${index.gradleVersion ?: "—"}, " +
        "AGP ${index.agpVersion ?: "—"}, Kotlin ${index.kotlinVersion ?: "—"}."
    }

    if (containsAny(text, "agp", "android gradle plugin")) {
      return "The project's AGP is ${index.agpVersion ?: "not written in the build files"}."
    }

    if (containsAny(text, "kotlin")) {
      return "The project's Kotlin version is ${index.kotlinVersion ?: "not written in the build files"}."
    }

    if (containsAny(text, "sdk", "compile")) {
      val moduleSdk = index.modules.firstOrNull { it.compileSdk != null }
      val modulePart = if (moduleSdk != null)
        " ${moduleSdk.name} uses compileSdk ${moduleSdk.compileSdk}."
        else " No module sets compileSdk directly in its build file."
      return "Installed SDK platforms: ${index.sdkPlatforms.joinToString(", ").ifEmpty { "—" }}." +
        modulePart
    }

    if (containsAny(text, "java", "jdk", "environment", "build tools")) {
      return "Environment: Java ${index.javaVersion ?: "—"}; build tools " +
        "${index.buildToolsVersions.joinToString(", ").ifEmpty { "—" }}; SDK platforms " +
        "${index.sdkPlatforms.joinToString(", ").ifEmpty { "—" }}."
    }

    if (containsAny(text, "build", "status", "error", "pumalya", "failed")) {
      return if (lastBuildFailed) {
        "The last build of this project FAILED. See my notice in the chat " +
          "for the error classification, or open the Build output for the full details."
      } else {
        "No failed build has been recorded for this project since it was last opened. " +
          "If one fails, I'll message you here myself."
      }
    }

    if (containsAny(text, "kaya mo", "help", "tulong", "can you do", "features")) {
      return capabilities()
    }

    if (containsAny(text, "hello", "hi", "kamusta", "kumusta", "hey")) {
      return "Hi! I'm Devxyz Fable, the local assistant of DevxyzIDE Pro for " +
        "this project.\n" + capabilities()
    }

    return "My brain is local-only in this phase, so I can answer " +
      "questions about the project map (modules, files, dependencies, Gradle/AGP/Kotlin, " +
      "SDK, environment), the build status, and reading ZIP/APK/files you attach. " +
      "Free AI chat arrives in the next phase and will need your own " +
      "Gemini key in Settings."
  }

  private fun capabilities(): String {
    return "What I can do right now (all local, no internet):\n" +
      "• Answer questions about the project map — modules, files, dependencies, toolchain versions\n" +
      "• Watch builds and message you right away when one fails, with the error class\n" +
      "• Read a ZIP, APK or text file you attach and summarize its contents\n" +
      "Just ask — for example: \"how many modules does my project have?\""
  }

  private fun containsAny(text: String, vararg needles: String): Boolean {
    return needles.any { text.contains(it) }
  }
}
