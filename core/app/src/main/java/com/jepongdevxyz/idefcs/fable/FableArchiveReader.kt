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
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Fable's local file reader for chat attachments. ZIP/APK/JAR/AAR files are
 * listed (and APKs identified through the PackageManager), text files are
 * previewed, and images/videos are reported with their metadata — visual
 * understanding of media arrives with the AI phase and is never faked here.
 * Everything is read on-device; nothing is uploaded anywhere.
 */
object FableArchiveReader {

  private const val MAX_LISTED_ENTRIES = 40
  private const val MAX_PREVIEW_CHARS = 3000

  private val archiveExtensions = setOf("zip", "jar", "aar", "apk")
  private val textExtensions = setOf(
    "txt", "md", "json", "xml", "kt", "kts", "java", "gradle",
    "properties", "toml", "yaml", "yml", "csv", "html", "css", "js"
  )

  /** A short, chat-ready summary of the attachment at [uri]. Blocking; call off the UI thread. */
  fun summarize(context: Context, uri: Uri): String {
    val name = displayName(context, uri) ?: "attachment"
    val size = displaySize(context, uri)
    val extension = name.substringAfterLast('.', "").lowercase()
    val mime = context.contentResolver.getType(uri) ?: ""

    return try {
      when {
        extension in archiveExtensions -> summarizeArchive(context, uri, name, size, extension)
        extension in textExtensions || mime.startsWith("text/") ->
          summarizeText(context, uri, name, size)
        mime.startsWith("image/") ->
          "Natanggap ko ang larawan na $name (${formatSize(size)}).\n" +
            "Ang pag-intindi sa laman ng larawan ay darating kasama ng AI phase — " +
            "sa ngayon ay metadata lang ang nababasa ko nang lokal."
        mime.startsWith("video/") ->
          "Natanggap ko ang video na $name (${formatSize(size)}).\n" +
            "Ang panonood at pag-intindi ng video ay darating kasama ng AI phase — " +
            "sa ngayon ay metadata lang ang nababasa ko nang lokal."
        else ->
          "Natanggap ko ang file na $name (${formatSize(size)}" +
            (if (mime.isNotEmpty()) ", $mime" else "") + "). " +
            "Hindi ko pa nababasa ang ganitong uri ng file nang lokal."
      }
    } catch (e: Exception) {
      "Hindi ko nabasa ang $name: ${e.message ?: "nagkaroon ng error habang binabasa ito."}"
    }
  }

  private fun summarizeArchive(
    context: Context,
    uri: Uri,
    name: String,
    size: Long,
    extension: String
  ): String {
    val out = StringBuilder()
    out.append("Binasa ko ang ").append(name).append(" (").append(formatSize(size)).append(").")

    if (extension == "apk") {
      val apkInfo = readApkInfo(context, uri)
      if (apkInfo != null) {
        out.append("\nAPK: ").append(apkInfo)
      }
    }

    val entries = mutableListOf<Pair<String, Long>>()
    var totalUncompressed = 0L
    context.contentResolver.openInputStream(uri)?.use { stream ->
      ZipInputStream(stream).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (!entry.isDirectory) {
            entries.add(entry.name to entry.size)
            if (entry.size > 0) totalUncompressed += entry.size
          }
          zip.closeEntry()
        }
      }
    }

    out.append("\nLaman: ").append(entries.size).append(" files")
    if (totalUncompressed > 0) {
      out.append(" (").append(formatSize(totalUncompressed)).append(" pag na-extract)")
    }
    out.append('.')

    val shown = entries.take(MAX_LISTED_ENTRIES)
    for ((entryName, entrySize) in shown) {
      out.append("\n• ").append(entryName)
      if (entrySize > 0) out.append(" — ").append(formatSize(entrySize))
    }
    if (entries.size > shown.size) {
      out.append("\n… at ").append(entries.size - shown.size).append(" pang files.")
    }

    val readme = entries.firstOrNull {
      val lower = it.first.lowercase()
      (lower.endsWith(".md") || lower.endsWith(".txt")) &&
        !lower.contains('/')
    }
    if (readme != null) {
      val preview = readEntryPreview(context, uri, readme.first)
      if (preview != null) {
        out.append("\n\n").append(readme.first).append(":\n").append(preview)
      }
    }
    return out.toString()
  }

  private fun readApkInfo(context: Context, uri: Uri): String? {
    return try {
      val cacheFile = File(context.cacheDir, "fable-apk-preview.apk")
      context.contentResolver.openInputStream(uri)?.use { input ->
        cacheFile.outputStream().use { output -> input.copyTo(output) }
      } ?: return null

      val pm = context.packageManager
      val info = pm.getPackageArchiveInfo(cacheFile.absolutePath, 0) ?: return null
      val appInfo = info.applicationInfo
      appInfo.sourceDir = cacheFile.absolutePath
      appInfo.publicSourceDir = cacheFile.absolutePath
      val label = try {
        pm.getApplicationLabel(appInfo).toString()
      } catch (e: Exception) {
        info.packageName
      }
      "$label · ${info.packageName} · bersyon ${info.versionName} (${info.longVersionCode})"
    } catch (e: Exception) {
      null
    }
  }

  private fun readEntryPreview(context: Context, uri: Uri, entryName: String): String? {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(stream).use { zip ->
          while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == entryName) {
              val text = readStreamText(zip)
              return text.takeIf { it.isNotBlank() }
            }
            zip.closeEntry()
          }
        }
      }
      null
    } catch (e: Exception) {
      null
    }
  }

  private fun summarizeText(context: Context, uri: Uri, name: String, size: Long): String {
    val text = context.contentResolver.openInputStream(uri)?.use { readStreamText(it) }
      ?: return "Hindi ko mabuksan ang $name."
    return "Binasa ko ang $name (${formatSize(size)}):\n$text"
  }

  private fun readStreamText(stream: InputStream): String {
    val buffer = ByteArray(8192)
    val out = StringBuilder()
    while (out.length < MAX_PREVIEW_CHARS) {
      val read = stream.read(buffer)
      if (read <= 0) break
      out.append(String(buffer, 0, read, Charsets.UTF_8))
    }
    val text = out.toString().take(MAX_PREVIEW_CHARS)
    return if (out.length >= MAX_PREVIEW_CHARS) "$text…" else text
  }

  private fun displayName(context: Context, uri: Uri): String? {
    return try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
      }
    } catch (e: Exception) {
      null
    } ?: uri.lastPathSegment
  }

  private fun displaySize(context: Context, uri: Uri): Long {
    return try {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
      } ?: -1L
    } catch (e: Exception) {
      -1L
    }
  }

  fun formatSize(bytes: Long): String {
    if (bytes < 0) return "hindi alam ang laki"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
      value /= 1024.0
      unit++
    }
    return String.format("%.1f %s", value, units[unit])
  }
}
