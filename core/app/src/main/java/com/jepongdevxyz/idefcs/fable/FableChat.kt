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
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** Who sent a chat message: the user, Fable itself, or Fable's build watcher. */
enum class FableRole {
  USER,
  FABLE,
  NOTICE
}

/** One message in a project's Fable chat log. */
data class FableChatMessage(
  val role: FableRole,
  val text: String,
  val timeMillis: Long,
  val attachmentName: String? = null
) {

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("role", role.name)
    json.put("text", text)
    json.put("time", timeMillis)
    json.put("attachment", attachmentName ?: JSONObject.NULL)
    return json
  }

  companion object {

    fun fromJson(json: JSONObject): FableChatMessage {
      val role = try {
        FableRole.valueOf(json.optString("role"))
      } catch (e: Exception) {
        FableRole.FABLE
      }
      return FableChatMessage(
        role = role,
        text = json.optString("text"),
        timeMillis = json.optLong("time"),
        attachmentName = if (json.isNull("attachment")) null else json.optString("attachment")
      )
    }
  }
}

/** The persisted chat state of one project. */
data class FableChatLog(
  val messages: List<FableChatMessage>,
  val unread: Int,
  val lastBuildFailed: Boolean
)

/**
 * Per-project Fable chat storage: a small JSON file per project under
 * filesDir/fable, the same pattern as [FableIndexStore]. All access is
 * synchronized; listeners are notified after every change so the panel
 * and the chat head can refresh live.
 */
object FableChatStore {

  private const val MAX_MESSAGES = 200

  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  private fun notifyChanged() {
    for (listener in listeners) {
      try {
        listener()
      } catch (e: Exception) {
        // A broken listener must never break a build or the chat itself.
      }
    }
  }

  private fun storeFile(filesDir: File, projectPath: String): File {
    val dir = File(filesDir, "fable")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "chat-" + projectPath.hashCode().toString() + ".json")
  }

  @Synchronized
  fun load(filesDir: File, projectPath: String): FableChatLog {
    return try {
      val file = storeFile(filesDir, projectPath)
      if (!file.isFile) return FableChatLog(emptyList(), 0, false)
      val json = JSONObject(file.readText())
      val messages = mutableListOf<FableChatMessage>()
      val array = json.optJSONArray("messages")
      if (array != null) {
        for (i in 0 until array.length()) {
          val item = array.optJSONObject(i) ?: continue
          messages.add(FableChatMessage.fromJson(item))
        }
      }
      FableChatLog(
        messages = messages,
        unread = json.optInt("unread"),
        lastBuildFailed = json.optBoolean("lastBuildFailed")
      )
    } catch (e: Exception) {
      FableChatLog(emptyList(), 0, false)
    }
  }

  @Synchronized
  private fun save(filesDir: File, projectPath: String, log: FableChatLog) {
    try {
      val json = JSONObject()
      json.put("unread", log.unread)
      json.put("lastBuildFailed", log.lastBuildFailed)
      val array = JSONArray()
      for (message in log.messages) {
        array.put(message.toJson())
      }
      json.put("messages", array)
      storeFile(filesDir, projectPath).writeText(json.toString())
    } catch (e: Exception) {
      // A failed chat write must never break anything else.
    }
  }

  /** Appends a message; returns the updated log. */
  @Synchronized
  fun append(
    filesDir: File,
    projectPath: String,
    message: FableChatMessage,
    countUnread: Boolean
  ): FableChatLog {
    val current = load(filesDir, projectPath)
    val messages = (current.messages + message).takeLast(MAX_MESSAGES)
    val updated = current.copy(
      messages = messages,
      unread = if (countUnread) current.unread + 1 else current.unread
    )
    save(filesDir, projectPath, updated)
    notifyChanged()
    return updated
  }

  @Synchronized
  fun markAllRead(filesDir: File, projectPath: String) {
    val current = load(filesDir, projectPath)
    if (current.unread == 0) return
    save(filesDir, projectPath, current.copy(unread = 0))
    notifyChanged()
  }

  /** Sets the last-build-failed flag; returns its previous value. */
  @Synchronized
  fun setLastBuildFailed(filesDir: File, projectPath: String, failed: Boolean): Boolean {
    val current = load(filesDir, projectPath)
    val previous = current.lastBuildFailed
    if (previous != failed) {
      save(filesDir, projectPath, current.copy(lastBuildFailed = failed))
    }
    return previous
  }

  @Synchronized
  fun unreadCount(filesDir: File, projectPath: String): Int {
    return load(filesDir, projectPath).unread
  }
}
