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

package com.jepongdevxyz.idefcs.plugins

import com.jepongdevxyz.idefcs.plugins.util.DownloadUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Gradle plugin which downloads the bootstrap packages for the terminal.
 *
 * The upstream bootstrap binaries, scripts and apt data are hardcoded to the
 * old Termux prefix, so after the sha256 check each downloaded ZIP is unzipped,
 * patched to the new install prefix, re-zipped, and only the patched ZIP is
 * embedded in the app. This runs for all three architectures.
 *
 * @author Akash Yadav
 */
class TerminalBootstrapPackagesPlugin : Plugin<Project> {

  companion object {

    /**
     * The bootstrap packages, mapped with the CPU ABI as the key and the ZIP file's sha256sum as the value.
     */
    private val BOOTSTRAP_PACKAGES = mapOf(
      "aarch64" to "68da03ed270d59cafcd37981b00583c713b42cb440adf03d1bf980f39a55181d",
      "arm" to "f3d9f2da7338bd00b02a8df192bdc22ad431a5eef413cecf4cd78d7a54ffffbf",
      "x86_64" to "6e4e50a206c3384c36f141b2496c1a7c69d30429e4e20268c51a84143530af67"
    )

    /**
     * The bootstrap packages version, basically the tag name of the GitHub release.
     */
    private const val BOOTSTRAP_PACKAGES_VERSION = "16.12.2023"

    private const val PACKAGES_DOWNLOAD_URL =
      "https://github.com/AndroidIDEOfficial/terminal-packages/releases/download/bootstrap-%1\$s/bootstrap-%2\$s.zip"

    // Old upstream Termux prefix (32 bytes) hardcoded in the bootstrap files.
    private const val OLD_TERMUX_PREFIX = "/data/data/com.itsaky.androidide"

    // New install prefix (31 bytes). This must match applicationId
    // (com.jepongdevxyz.pro), NOT BuildConfig.packageName (com.jepongdevxyz.idefcs).
    private const val NEW_TERMUX_PREFIX = "/data/data/com.jepongdevxyz.pro"

    private const val OLD_PACKAGE_NAME = "com.itsaky.androidide"
    private const val NEW_PACKAGE_NAME = "com.jepongdevxyz.pro"
  }

  override fun apply(target: Project) {
    target.run {

      val bootstrapOut = project.layout.buildDirectory.dir("bootstrap-packages")
        .get().asFile

      val files = BOOTSTRAP_PACKAGES.map { (arch, sha256) ->
        val file = File(bootstrapOut, "bootstrap-${arch}.zip")
        file.parentFile.mkdirs()

        DownloadUtils.doDownload(
          file = file,
          remoteUrl = PACKAGES_DOWNLOAD_URL.format(BOOTSTRAP_PACKAGES_VERSION, arch),
          expectedChecksum = sha256,
          logger = logger
        )

        // Patch only after the sha256 verification above, then embed the patched ZIP.
        val patchedFile = File(bootstrapOut, "bootstrap-${arch}-patched.zip")
        patchBootstrapZip(file, patchedFile)
        verifyPatchedBootstrapZip(patchedFile, arch)

        return@map arch to patchedFile
      }.toMap()

      project.file("src/main/cpp/termux-bootstrap-zip.S").writeText(
        """
             .global blob
             .global blob_size
             .section .rodata
         blob:
        #if defined __aarch64__
             .incbin "${escapePathOnWindows(files["aarch64"]!!.absolutePath)}"
         #elif defined __arm__
             .incbin "${escapePathOnWindows(files["arm"]!!.absolutePath)}"
         #elif defined __x86_64__
             .incbin "${escapePathOnWindows(files["x86_64"]!!.absolutePath)}"
         #else
         # error Unsupported arch
         #endif
         1:
         blob_size:
             .int 1b - blob
         
      """.trimIndent()
      )
    }
  }

  private fun escapePathOnWindows(path: String): String {
    if (OperatingSystem.current().isWindows) {
      // escape backslashes when building on Windows
      return path.replace("\\", "\\\\")
    }

    return path
  }

  private data class BootstrapEntry(
    val name: String,
    val time: Long,
    val bytes: ByteArray,
    val isDirectory: Boolean
  )

  /**
   * Unzip [inputFile], patch every entry, regenerate dpkg md5sums from the
   * patched files where a matching entry exists, and write [outputFile].
   *
   * SYMLINKS.txt is patched as text. The bare package replacement only matches
   * the full "com.itsaky.androidide" string, so plain filenames such as
   * "itsaky.gpg" in SYMLINKS.txt are left unchanged.
   */
  private fun patchBootstrapZip(inputFile: File, outputFile: File) {
    val entries = mutableListOf<BootstrapEntry>()
    ZipInputStream(FileInputStream(inputFile)).use { zipInput ->
      var entry = zipInput.nextEntry
      while (entry != null) {
        val isDirectory = entry.isDirectory
        val bytes = if (isDirectory) ByteArray(0) else zipInput.readBytes()
        entries.add(BootstrapEntry(entry.name, entry.time, bytes, isDirectory))
        entry = zipInput.nextEntry
      }
    }

    val patched = linkedMapOf<String, ByteArray>()
    for (entry in entries) {
      if (!entry.isDirectory) {
        patched[entry.name] = patchEntryBytes(entry.bytes)
      }
    }

    // Regenerate var/lib/dpkg/info/*.md5sums from the patched files. A line
    // whose file has no matching patched entry is left unchanged; such a hash
    // may then be stale, and that limitation is disclosed here rather than
    // silently pretending every hash was regenerated.
    for (entry in entries) {
      if (entry.isDirectory) continue
      if (!entry.name.startsWith("var/lib/dpkg/info/") || !entry.name.endsWith(".md5sums")) continue
      val original = String(patched[entry.name]!!, Charsets.UTF_8)
      val rebuilt = StringBuilder()
      val lines = original.split("\n")
      for ((index, line) in lines.withIndex()) {
        if (index > 0) rebuilt.append("\n")
        if (line.isBlank()) {
          rebuilt.append(line)
          continue
        }
        val firstSpace = line.indexOfFirst { it == ' ' || it == '\t' }
        if (firstSpace < 0) {
          rebuilt.append(line)
          continue
        }
        val fileName = line.substring(firstSpace).trim()
        val candidate = md5sumsCandidate(fileName)
        val targetBytes = patched[candidate]
        if (targetBytes == null || candidate == entry.name) {
          rebuilt.append(line)
        } else {
          rebuilt.append(md5Hex(targetBytes))
          rebuilt.append(line.substring(firstSpace))
        }
      }
      patched[entry.name] = rebuilt.toString().toByteArray(Charsets.UTF_8)
    }

    outputFile.parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(outputFile)).use { zipOutput ->
      for (entry in entries) {
        val zipEntry = ZipEntry(entry.name)
        zipEntry.time = entry.time
        zipOutput.putNextEntry(zipEntry)
        if (!entry.isDirectory) {
          zipOutput.write(patched[entry.name]!!)
        }
        zipOutput.closeEntry()
      }
    }
  }

  private fun md5sumsCandidate(fileName: String): String {
    var name = fileName.trim()
    if (name.contains("/files/usr/")) {
      name = name.substringAfter("/files/usr/")
    }
    name = name.trimStart('/')
    if (name.startsWith("./")) {
      name = name.substring(2)
    }
    return name
  }

  private fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun patchEntryBytes(bytes: ByteArray): ByteArray {
    // ELF files, and any other binary containing null bytes, must keep their
    // exact length, so they are patched string-aware. Pure text has no null
    // bytes and can be replaced at variable length.
    if (isElf(bytes) || bytes.any { it == 0.toByte() }) {
      return patchStringAwareBytes(bytes)
    }
    return patchTextBytes(bytes)
  }

  private fun patchTextBytes(bytes: ByteArray): ByteArray {
    var result = replaceAllBytes(
      bytes,
      OLD_TERMUX_PREFIX.toByteArray(Charsets.UTF_8),
      NEW_TERMUX_PREFIX.toByteArray(Charsets.UTF_8)
    )
    // Bare package name, for example the termux-am socket paths under
    // .../files/apps/com.itsaky.androidide/termux-am/...
    result = replaceAllBytes(
      result,
      OLD_PACKAGE_NAME.toByteArray(Charsets.UTF_8),
      NEW_PACKAGE_NAME.toByteArray(Charsets.UTF_8)
    )
    return result
  }

  /**
   * String-aware replacement for binaries: for each occurrence, replace inside
   * the whole null-terminated string that contains it, then pad with nulls at
   * the end of that string back to its original length. A null is never
   * inserted in the middle of the string, and the file length never changes.
   */
  private fun patchStringAwareBytes(bytes: ByteArray): ByteArray {
    val result = bytes.copyOf()
    val oldPrefix = OLD_TERMUX_PREFIX.toByteArray(Charsets.UTF_8)
    val newPrefix = NEW_TERMUX_PREFIX.toByteArray(Charsets.UTF_8)
    val oldPackage = OLD_PACKAGE_NAME.toByteArray(Charsets.UTF_8)
    val newPackage = NEW_PACKAGE_NAME.toByteArray(Charsets.UTF_8)
    var i = 0
    while (i < result.size) {
      val matched = matchesAt(result, oldPrefix, i) || matchesAt(result, oldPackage, i)
      if (!matched) {
        i++
        continue
      }
      var start = i
      while (start > 0 && result[start - 1] != 0.toByte()) {
        start--
      }
      var end = i
      while (end < result.size && result[end] != 0.toByte()) {
        end++
      }
      val original = result.copyOfRange(start, end)
      var patched = replaceAllBytes(original, oldPrefix, newPrefix)
      patched = replaceAllBytes(patched, oldPackage, newPackage)
      for (index in start until end) {
        result[index] = if (index - start < patched.size) patched[index - start] else 0
      }
      i = end + 1
    }
    return result
  }

  private fun isElf(bytes: ByteArray): Boolean {
    return bytes.size >= 4 &&
      bytes[0] == 0x7f.toByte() &&
      bytes[1] == 'E'.code.toByte() &&
      bytes[2] == 'L'.code.toByte() &&
      bytes[3] == 'F'.code.toByte()
  }

  private fun matchesAt(bytes: ByteArray, needle: ByteArray, index: Int): Boolean {
    if (index < 0 || index + needle.size > bytes.size) return false
    for (offset in needle.indices) {
      if (bytes[index + offset] != needle[offset]) return false
    }
    return true
  }

  private fun replaceAllBytes(bytes: ByteArray, oldBytes: ByteArray, newBytes: ByteArray): ByteArray {
    if (oldBytes.isEmpty()) return bytes
    val output = ByteArrayOutputStream()
    var i = 0
    while (i < bytes.size) {
      if (matchesAt(bytes, oldBytes, i)) {
        output.write(newBytes)
        i += oldBytes.size
      } else {
        output.write(bytes[i].toInt())
        i++
      }
    }
    return output.toByteArray()
  }

  /**
   * Build-time self-check. Fails the build if the patched bin/bash RUNPATH
   * does not start with the new prefix lib directory, if any old prefix
   * remains in bin/bash, or if the patched bin/pkg shebang is wrong.
   */
  private fun verifyPatchedBootstrapZip(patchedFile: File, arch: String) {
    ZipFile(patchedFile).use { zip ->
      val bashEntry = zip.getEntry("bin/bash")
        ?: error("Patched Termux bootstrap for $arch is missing bin/bash")
      val bashText = String(zip.getInputStream(bashEntry).readBytes(), Charsets.ISO_8859_1)
      check(!bashText.contains(OLD_TERMUX_PREFIX)) {
        "Patched Termux bootstrap for $arch still contains the old prefix $OLD_TERMUX_PREFIX in bin/bash"
      }
      val runpathOk = bashText.split('\u0000').any {
        it.startsWith("$NEW_TERMUX_PREFIX/files/usr/lib")
      }
      check(runpathOk) {
        "Patched Termux bootstrap for $arch: bin/bash RUNPATH does not start with $NEW_TERMUX_PREFIX/files/usr/lib"
      }
      val pkgEntry = zip.getEntry("bin/pkg")
        ?: error("Patched Termux bootstrap for $arch is missing bin/pkg")
      val shebang = zip.getInputStream(pkgEntry).bufferedReader(Charsets.UTF_8).readLine()
      check(shebang == "#!$NEW_TERMUX_PREFIX/files/usr/bin/bash") {
        "Patched Termux bootstrap for $arch: bin/pkg shebang is '$shebang', expected '#!$NEW_TERMUX_PREFIX/files/usr/bin/bash'"
      }
    }
  }
}
