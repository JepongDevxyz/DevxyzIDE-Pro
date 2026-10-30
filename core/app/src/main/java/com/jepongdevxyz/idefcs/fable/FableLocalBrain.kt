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
      return "Binubuo ko pa ang mapa ng project — subukan mo ulit pag lumabas na ang " +
        "project map sa itaas, o i-tap ang Refresh map. Ang kaya ko munang sagutin " +
        "ay mga tanong tungkol sa mapa ng project mo."
    }

    if (containsAny(text, "module", "modulo")) {
      val names = index.modules.take(12).joinToString(", ") { it.name }
      val more = if (index.modules.size > 12) " at iba pa" else ""
      return "Ang project na ${index.projectName} ay may ${index.modules.size} modules: " +
        "$names$more."
    }

    if (containsAny(text, "file")) {
      return "May ${index.totalFileCount} files ang ${index.projectName} " +
        "(hindi kasama ang build, .gradle, .git at .idea folders)."
    }

    if (containsAny(text, "dependenc", "library", "libraries")) {
      val sample = index.modules.flatMap { it.dependencies }.distinct().take(10)
      val listing = if (sample.isEmpty()) ""
        else "\nHalimbawa: " + sample.joinToString(", ")
      return "May ${index.dependencyCount} dependency entries ang project sa lahat ng modules.$listing"
    }

    if (containsAny(text, "gradle")) {
      return "Toolchain ng ${index.projectName}: Gradle ${index.gradleVersion ?: "—"}, " +
        "AGP ${index.agpVersion ?: "—"}, Kotlin ${index.kotlinVersion ?: "—"}."
    }

    if (containsAny(text, "agp", "android gradle plugin")) {
      return "Ang AGP ng project ay ${index.agpVersion ?: "hindi nakasulat sa build files"}."
    }

    if (containsAny(text, "kotlin")) {
      return "Ang Kotlin version ng project ay ${index.kotlinVersion ?: "hindi nakasulat sa build files"}."
    }

    if (containsAny(text, "sdk", "compile")) {
      val moduleSdk = index.modules.firstOrNull { it.compileSdk != null }
      val modulePart = if (moduleSdk != null)
        " Ang ${moduleSdk.name} ay naka-compileSdk ${moduleSdk.compileSdk}."
        else " Walang module na direktang nagtakda ng compileSdk sa build file nito."
      return "Naka-install na SDK platforms: ${index.sdkPlatforms.joinToString(", ").ifEmpty { "—" }}." +
        modulePart
    }

    if (containsAny(text, "java", "jdk", "environment", "build tools")) {
      return "Environment: Java ${index.javaVersion ?: "—"}; build tools " +
        "${index.buildToolsVersions.joinToString(", ").ifEmpty { "—" }}; SDK platforms " +
        "${index.sdkPlatforms.joinToString(", ").ifEmpty { "—" }}."
    }

    if (containsAny(text, "build", "status", "error", "pumalya", "failed")) {
      return if (lastBuildFailed) {
        "Ang huling build ng project na ito ay PUMALYA. Tingnan ang notice ko sa chat " +
          "para sa klasipikasyon ng error, o buksan ang Build output para sa buong detalye."
      } else {
        "Walang naitalang pumalyang build sa project na ito mula nang huli itong nabuksan. " +
          "Pag may pumalya, ako mismo ang magme-message sa iyo rito."
      }
    }

    if (containsAny(text, "kaya mo", "help", "tulong", "can you do", "features")) {
      return capabilities()
    }

    if (containsAny(text, "hello", "hi", "kamusta", "kumusta", "hey")) {
      return "Kamusta! Ako si Devxyz Fable, ang lokal na katulong ng DevxyzIDE Pro para " +
        "sa project na ito.\n" + capabilities()
    }

    return "Lokal pa lang ang utak ko sa phase na ito, kaya ang kaya kong sagutin ay mga " +
      "tanong tungkol sa mapa ng project (modules, files, dependencies, Gradle/AGP/Kotlin, " +
      "SDK, environment), ang status ng build, at ang pagbasa ng ZIP/APK/files na ia-attach mo. " +
      "Ang malayang AI chat ay darating sa susunod na phase at mangangailangan ng sarili " +
      "mong Gemini key sa Settings."
  }

  private fun capabilities(): String {
    return "Ang kaya ko sa ngayon (lahat lokal, walang internet):\n" +
      "• Sumagot tungkol sa mapa ng project — modules, files, dependencies, bersyon ng toolchain\n" +
      "• Magbantay ng build at mag-message agad pag may pumalya, kasama ang klase ng error\n" +
      "• Magbasa ng ia-attach mong ZIP, APK o text file at ibuod ang laman nito\n" +
      "Magtanong ka lang, halimbawa: \"ilang modules ang project ko?\""
  }

  private fun containsAny(text: String, vararg needles: String): Boolean {
    return needles.any { text.contains(it) }
  }
}
