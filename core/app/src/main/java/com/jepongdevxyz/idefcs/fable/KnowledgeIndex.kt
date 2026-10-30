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

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * One module of the opened project, as known by Devxyz Fable's knowledge index.
 */
data class FableModuleInfo(
  val name: String,
  val path: String,
  val compileSdk: Int?,
  val minSdk: Int?,
  val targetSdk: Int?,
  val dependencies: List<String>,
  val sourceFileCount: Int
)

/**
 * A local, on-device map of the opened project: modules, files, dependencies,
 * toolchain versions and an environment snapshot. Built only from files that are
 * already on the device. Nothing here ever leaves the phone.
 */
data class KnowledgeIndex(
  val projectName: String,
  val projectPath: String,
  val modules: List<FableModuleInfo>,
  val totalFileCount: Int,
  val dependencyCount: Int,
  val gradleVersion: String?,
  val agpVersion: String?,
  val kotlinVersion: String?,
  val sdkPlatforms: List<String>,
  val buildToolsVersions: List<String>,
  val javaVersion: String?,
  val builtAtMillis: Long
) {

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("projectName", projectName)
    json.put("projectPath", projectPath)
    json.put("totalFileCount", totalFileCount)
    json.put("dependencyCount", dependencyCount)
    json.put("gradleVersion", gradleVersion ?: JSONObject.NULL)
    json.put("agpVersion", agpVersion ?: JSONObject.NULL)
    json.put("kotlinVersion", kotlinVersion ?: JSONObject.NULL)
    json.put("javaVersion", javaVersion ?: JSONObject.NULL)
    json.put("builtAtMillis", builtAtMillis)
    json.put("sdkPlatforms", JSONArray(sdkPlatforms))
    json.put("buildToolsVersions", JSONArray(buildToolsVersions))
    val modulesJson = JSONArray()
    for (module in modules) {
      val m = JSONObject()
      m.put("name", module.name)
      m.put("path", module.path)
      m.put("compileSdk", module.compileSdk ?: JSONObject.NULL)
      m.put("minSdk", module.minSdk ?: JSONObject.NULL)
      m.put("targetSdk", module.targetSdk ?: JSONObject.NULL)
      m.put("dependencies", JSONArray(module.dependencies))
      m.put("sourceFileCount", module.sourceFileCount)
      modulesJson.put(m)
    }
    json.put("modules", modulesJson)
    return json
  }

  companion object {

    fun fromJson(json: JSONObject): KnowledgeIndex {
      fun nullableString(key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotEmpty() }

      fun nullableInt(obj: JSONObject, key: String): Int? =
        if (obj.isNull(key)) null else obj.optInt(key)

      fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
          result.add(array.optString(i))
        }
        return result
      }

      val modules = mutableListOf<FableModuleInfo>()
      val modulesJson = json.optJSONArray("modules")
      if (modulesJson != null) {
        for (i in 0 until modulesJson.length()) {
          val m = modulesJson.optJSONObject(i) ?: continue
          modules.add(
            FableModuleInfo(
              name = m.optString("name"),
              path = m.optString("path"),
              compileSdk = nullableInt(m, "compileSdk"),
              minSdk = nullableInt(m, "minSdk"),
              targetSdk = nullableInt(m, "targetSdk"),
              dependencies = stringList(m.optJSONArray("dependencies")),
              sourceFileCount = m.optInt("sourceFileCount")
            )
          )
        }
      }

      return KnowledgeIndex(
        projectName = json.optString("projectName"),
        projectPath = json.optString("projectPath"),
        modules = modules,
        totalFileCount = json.optInt("totalFileCount"),
        dependencyCount = json.optInt("dependencyCount"),
        gradleVersion = nullableString("gradleVersion"),
        agpVersion = nullableString("agpVersion"),
        kotlinVersion = nullableString("kotlinVersion"),
        sdkPlatforms = stringList(json.optJSONArray("sdkPlatforms")),
        buildToolsVersions = stringList(json.optJSONArray("buildToolsVersions")),
        javaVersion = nullableString("javaVersion"),
        builtAtMillis = json.optLong("builtAtMillis")
      )
    }
  }
}

/**
 * Builds a [KnowledgeIndex] from the project directory and the IDE environment.
 * Pure file parsing: defensive, offline, and safe to run on a background thread.
 */
object KnowledgeIndexBuilder {

  private val ignoredDirs = setOf("build", ".gradle", ".git", ".idea")

  private val sdkValuePatterns = mapOf(
    "compileSdk" to Regex("""compileSdk(?:Version)?\s*=?\s*\(?\s*(\d+)"""),
    "minSdk" to Regex("""minSdk(?:Version)?\s*=?\s*\(?\s*(\d+)"""),
    "targetSdk" to Regex("""targetSdk(?:Version)?\s*=?\s*\(?\s*(\d+)""")
  )

  private val dependencyLine = Regex(
    """^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp|classpath)\s*\(?\s*['"]([^'"]+)['"]"""
  )

  private val catalogDependencyLine = Regex(
    """^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\s*\(?\s*(libs\.[\w.]+)"""
  )

  private val projectDependencyLine = Regex(
    """^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\s*\(?\s*project\(\s*['"]([^'"]+)['"]"""
  )

  fun build(projectDir: File, androidHome: File?): KnowledgeIndex {
    val modulePaths = readModulePaths(projectDir)
    val catalogText = readFile(File(projectDir, "gradle/libs.versions.toml"))
    val rootBuildText = readFile(File(projectDir, "build.gradle.kts")) +
      readFile(File(projectDir, "build.gradle"))
    val settingsText = readFile(File(projectDir, "settings.gradle.kts")) +
      readFile(File(projectDir, "settings.gradle"))

    val modules = modulePaths.map { modulePath ->
      buildModuleInfo(projectDir, modulePath)
    }

    return KnowledgeIndex(
      projectName = projectDir.name,
      projectPath = projectDir.absolutePath,
      modules = modules,
      totalFileCount = countFiles(projectDir),
      dependencyCount = modules.sumOf { it.dependencies.size },
      gradleVersion = readGradleVersion(projectDir),
      agpVersion = findAgpVersion(rootBuildText, settingsText, catalogText),
      kotlinVersion = findKotlinVersion(rootBuildText, settingsText, catalogText),
      sdkPlatforms = listSubdirs(androidHome?.let { File(it, "platforms") }),
      buildToolsVersions = listSubdirs(androidHome?.let { File(it, "build-tools") }),
      javaVersion = System.getProperty("java.version"),
      builtAtMillis = System.currentTimeMillis()
    )
  }

  private fun buildModuleInfo(projectDir: File, modulePath: String): FableModuleInfo {
    val moduleDir = if (modulePath.isEmpty()) projectDir else File(projectDir, modulePath)
    val buildText = readFile(File(moduleDir, "build.gradle.kts")) +
      readFile(File(moduleDir, "build.gradle"))

    val dependencies = linkedSetOf<String>()
    for (line in buildText.lineSequence()) {
      dependencyLine.find(line)?.let { dependencies.add(it.groupValues[1]) }
      catalogDependencyLine.find(line)?.let { dependencies.add(it.groupValues[1]) }
      projectDependencyLine.find(line)?.let { dependencies.add("project(" + it.groupValues[1] + ")") }
    }

    val srcDir = File(moduleDir, "src")
    val sourceFiles = if (srcDir.isDirectory) {
      srcDir.walkTopDown().count {
        it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml")
      }
    } else {
      0
    }

    return FableModuleInfo(
      name = if (modulePath.isEmpty()) projectDir.name else modulePath.replace(File.separatorChar, ':'),
      path = modulePath,
      compileSdk = sdkValuePatterns.getValue("compileSdk").find(buildText)?.groupValues?.get(1)?.toIntOrNull(),
      minSdk = sdkValuePatterns.getValue("minSdk").find(buildText)?.groupValues?.get(1)?.toIntOrNull(),
      targetSdk = sdkValuePatterns.getValue("targetSdk").find(buildText)?.groupValues?.get(1)?.toIntOrNull(),
      dependencies = dependencies.toList(),
      sourceFileCount = sourceFiles
    )
  }

  /**
   * Module paths (relative, '/'-separated) from settings.gradle(.kts) include calls.
   * Falls back to the root project as a single module.
   */
  private fun readModulePaths(projectDir: File): List<String> {
    val settingsText = readFile(File(projectDir, "settings.gradle.kts")) +
      readFile(File(projectDir, "settings.gradle"))
    val paths = linkedSetOf<String>()
    val includeToken = Regex("""['"](:[A-Za-z0-9_\-:]+)['"]""")
    for (line in settingsText.lineSequence()) {
      if (!line.contains("include")) continue
      for (match in includeToken.findAll(line)) {
        paths.add(match.groupValues[1].removePrefix(":").replace(':', '/'))
      }
    }
    if (paths.isEmpty()) {
      paths.add("")
    }
    return paths.toList()
  }

  private fun countFiles(projectDir: File): Int {
    var count = 0
    projectDir.walkTopDown()
      .onEnter { dir -> dir.name !in ignoredDirs || dir == projectDir }
      .forEach { if (it.isFile) count++ }
    return count
  }

  private fun readGradleVersion(projectDir: File): String? {
    val props = readFile(File(projectDir, "gradle/wrapper/gradle-wrapper.properties"))
    return Regex("""gradle-([0-9]+(?:\.[0-9]+)*)-""").find(props)?.groupValues?.get(1)
  }

  private fun findAgpVersion(rootBuild: String, settings: String, catalog: String): String? {
    Regex("""com\.android\.tools\.build:gradle:([0-9][0-9A-Za-z.\-]*)""")
      .find(rootBuild)?.let { return it.groupValues[1] }
    Regex("""com\.android\.application["']?\s*(?:,\s*|version\s+)["']?version\s*=\s*["']([0-9][^"']*)["']""")
      .find(settings)?.let { return it.groupValues[1] }
    Regex("""(?m)^\s*agp\s*=\s*["']([0-9][^"']*)["']""")
      .find(catalog)?.let { return it.groupValues[1] }
    Regex("""com\.android\.application.*?version\s*["']([0-9][0-9A-Za-z.\-]*)["']""")
      .find(rootBuild)?.let { return it.groupValues[1] }
    return null
  }

  private fun findKotlinVersion(rootBuild: String, settings: String, catalog: String): String? {
    Regex("""org\.jetbrains\.kotlin:kotlin-gradle-plugin:([0-9][0-9A-Za-z.\-]*)""")
      .find(rootBuild)?.let { return it.groupValues[1] }
    Regex("""(?m)^\s*kotlin\s*=\s*["']([0-9][^"']*)["']""")
      .find(catalog)?.let { return it.groupValues[1] }
    Regex("""org\.jetbrains\.kotlin\.android.*?version\s*["']([0-9][0-9A-Za-z.\-]*)["']""")
      .find(settings + rootBuild)?.let { return it.groupValues[1] }
    return null
  }

  private fun listSubdirs(dir: File?): List<String> {
    if (dir == null || !dir.isDirectory) return emptyList()
    return dir.listFiles()
      ?.filter { it.isDirectory }
      ?.map { it.name }
      ?.sorted()
      ?: emptyList()
  }

  private fun readFile(file: File): String {
    return try {
      if (file.isFile) file.readText() else ""
    } catch (e: Exception) {
      ""
    }
  }
}

/**
 * Persists one [KnowledgeIndex] snapshot per project inside the app's private
 * storage, so the panel can show the last known map instantly.
 */
object FableIndexStore {

  private fun storeFile(filesDir: File, projectPath: String): File {
    val dir = File(filesDir, "fable")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "index-" + projectPath.hashCode().toString() + ".json")
  }

  fun save(filesDir: File, index: KnowledgeIndex) {
    try {
      storeFile(filesDir, index.projectPath).writeText(index.toJson().toString())
    } catch (e: Exception) {
      // A failed snapshot write must never break the panel.
    }
  }

  fun load(filesDir: File, projectPath: String): KnowledgeIndex? {
    return try {
      val file = storeFile(filesDir, projectPath)
      if (!file.isFile) return null
      KnowledgeIndex.fromJson(JSONObject(file.readText()))
    } catch (e: Exception) {
      null
    }
  }
}
