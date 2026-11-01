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
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
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
          "I received the image $name (${formatSize(size)}).\n" +
            "To let me actually look inside it, set your Gemini key in " +
            "Settings → Devxyz Fable and send it again — without a key I " +
            "can only read its metadata locally."
        mime.startsWith("video/") ->
          "I received the video $name (${formatSize(size)}).\n" +
            "To let me watch it (sampled frames), set your Gemini key in " +
            "Settings → Devxyz Fable and send it again — without a key I " +
            "can only read its metadata locally."
        else ->
          "I received the file $name (${formatSize(size)}" +
            (if (mime.isNotEmpty()) ", $mime" else "") + "). " +
            "I can't read this kind of file locally yet."
      }
    } catch (e: Exception) {
      "I couldn't read $name: ${e.message ?: "an error occurred while reading it."}"
    }
  }

  /**
   * Loads an attachment as Gemini vision parts (AI phase): the image
   * itself for image files (capped at ~4.5 MB), or up to three sampled
   * frames for a video. Empty when the file cannot be read as visual
   * input. Blocking; call off the UI thread.
   */
  fun loadVisionImages(context: Context, uri: Uri): List<FableGeminiClient.Part.Image> {
    val mime = context.contentResolver.getType(uri) ?: ""
    return try {
      when {
        mime.startsWith("image/") -> {
          val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()
          if (bytes.isEmpty() || bytes.size > 4_500_000) return emptyList()
          listOf(
            FableGeminiClient.Part.Image(
              Base64.encodeToString(bytes, Base64.NO_WRAP), mime
            )
          )
        }
        mime.startsWith("video/") -> sampleVideoFrames(context, uri)
        else -> emptyList()
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  private fun sampleVideoFrames(
    context: Context,
    uri: Uri
  ): List<FableGeminiClient.Part.Image> {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val durationMs = retriever
        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
      val timesUs = if (durationMs > 0) {
        listOf(1_000_000L, durationMs * 500L, (durationMs - 1) * 1000L)
          .map { it.coerceIn(0L, durationMs * 1000L) }
          .distinct()
      } else {
        listOf(0L)
      }
      val images = mutableListOf<FableGeminiClient.Part.Image>()
      for (timeUs in timesUs) {
        val frame = retriever.getFrameAtTime(
          timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: continue
        val scaled = scaleDown(frame, 1024)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val bytes = stream.toByteArray()
        if (bytes.isNotEmpty() && bytes.size <= 1_500_000) {
          images.add(
            FableGeminiClient.Part.Image(
              Base64.encodeToString(bytes, Base64.NO_WRAP), "image/jpeg"
            )
          )
        }
        if (scaled !== frame) scaled.recycle()
        frame.recycle()
      }
      images
    } catch (e: Exception) {
      emptyList()
    } finally {
      try {
        retriever.release()
      } catch (e: Exception) {
        // Nothing to clean up beyond this.
      }
    }
  }

  private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / largest.toFloat()
    return Bitmap.createScaledBitmap(
      bitmap,
      (bitmap.width * ratio).toInt().coerceAtLeast(1),
      (bitmap.height * ratio).toInt().coerceAtLeast(1),
      true
    )
  }

  private fun summarizeArchive(
    context: Context,
    uri: Uri,
    name: String,
    size: Long,
    extension: String
  ): String {
    val out = StringBuilder()
    out.append("I read ").append(name).append(" (").append(formatSize(size)).append(").")

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

    out.append("\nContents: ").append(entries.size).append(" files")
    if (totalUncompressed > 0) {
      out.append(" (").append(formatSize(totalUncompressed)).append(" when extracted)")
    }
    out.append('.')

    val shown = entries.take(MAX_LISTED_ENTRIES)
    for ((entryName, entrySize) in shown) {
      out.append("\n• ").append(entryName)
      if (entrySize > 0) out.append(" — ").append(formatSize(entrySize))
    }
    if (entries.size > shown.size) {
      out.append("\n… and ").append(entries.size - shown.size).append(" more files.")
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
      ?: return "I couldn't open $name."
    return "I read $name (${formatSize(size)}):\n$text"
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
    if (bytes < 0) return "unknown size"
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
