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
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** Which door an AI call came through; the two budgets are counted apart. */
enum class FableAiCallKind {
  AUTO_FIX,
  USER_REQUESTED
}

/**
 * Devxyz Fable's daily AI budget (plan §8). Gemini's free tier gives each
 * Flash model 20 requests/day per project, resetting at midnight Pacific
 * (≈ 3:00 PM Manila), so the counter is keyed by the Pacific date and kept
 * per model. Auto-fix calls and user-requested calls are counted separately,
 * as the plan requires. A model that answers 429 is marked limited for the
 * rest of the day — Fable stops calling it instead of burning attempts.
 *
 * Persisted as a small JSON file under filesDir/fable, the same pattern as
 * the chat and index stores. All access is synchronized and failure-proof.
 */
object FableAiBudget {

  /** Known free-tier quota per model per day (AI Studio, checked 2026-10-29). */
  const val QUOTA_PER_MODEL_PER_DAY = 20

  /** The model chain, best first (model test 2026-10-29; lite tier is out). */
  val MODEL_CHAIN = listOf("gemini-3.6-flash", "gemini-3-flash-preview")

  private val pacific: ZoneId = ZoneId.of("America/Los_Angeles")

  data class ModelUsage(
    val autoFixCalls: Int,
    val userCalls: Int,
    val rateLimited: Boolean
  ) {
    val total: Int get() = autoFixCalls + userCalls
    val remaining: Int get() = (QUOTA_PER_MODEL_PER_DAY - total).coerceAtLeast(0)
  }

  data class Snapshot(
    val day: String,
    val perModel: Map<String, ModelUsage>
  ) {
    fun usage(model: String): ModelUsage =
      perModel[model] ?: ModelUsage(0, 0, false)

    val totalAutoFix: Int get() = perModel.values.sumOf { it.autoFixCalls }
    val totalUser: Int get() = perModel.values.sumOf { it.userCalls }

    /** A model that can still take a call right now, best first. */
    fun firstUsableModel(): String? =
      MODEL_CHAIN.firstOrNull { model ->
        val usage = usage(model)
        !usage.rateLimited && usage.remaining > 0
      }

    fun isUsable(model: String): Boolean {
      val usage = usage(model)
      return !usage.rateLimited && usage.remaining > 0
    }
  }

  fun todayKey(): String = LocalDate.now(pacific).toString()

  private fun storeFile(filesDir: File): File {
    val dir = File(filesDir, "fable")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "ai-budget.json")
  }

  @Synchronized
  fun load(filesDir: File): Snapshot {
    val today = todayKey()
    return try {
      val file = storeFile(filesDir)
      if (!file.isFile) return Snapshot(today, emptyMap())
      val json = JSONObject(file.readText())
      if (json.optString("day") != today) {
        // A new Pacific day: the quota has reset.
        return Snapshot(today, emptyMap())
      }
      val models = mutableMapOf<String, ModelUsage>()
      val modelsJson = json.optJSONObject("models")
      if (modelsJson != null) {
        for (key in modelsJson.keys()) {
          val entry = modelsJson.optJSONObject(key) ?: continue
          models[key] = ModelUsage(
            autoFixCalls = entry.optInt("auto"),
            userCalls = entry.optInt("user"),
            rateLimited = entry.optBoolean("limited")
          )
        }
      }
      Snapshot(today, models)
    } catch (e: Exception) {
      Snapshot(today, emptyMap())
    }
  }

  @Synchronized
  private fun save(filesDir: File, snapshot: Snapshot) {
    try {
      val modelsJson = JSONObject()
      for ((model, usage) in snapshot.perModel) {
        val entry = JSONObject()
        entry.put("auto", usage.autoFixCalls)
        entry.put("user", usage.userCalls)
        entry.put("limited", usage.rateLimited)
        modelsJson.put(model, entry)
      }
      val json = JSONObject()
      json.put("day", snapshot.day)
      json.put("models", modelsJson)
      storeFile(filesDir).writeText(json.toString())
    } catch (e: Exception) {
      // A failed budget write must never break a build or the chat.
    }
  }

  /** Records one real API call (counted when the request is actually sent). */
  @Synchronized
  fun recordCall(filesDir: File, model: String, kind: FableAiCallKind) {
    val snapshot = load(filesDir)
    val usage = snapshot.usage(model)
    val updated = when (kind) {
      FableAiCallKind.AUTO_FIX -> usage.copy(autoFixCalls = usage.autoFixCalls + 1)
      FableAiCallKind.USER_REQUESTED -> usage.copy(userCalls = usage.userCalls + 1)
    }
    save(filesDir, snapshot.copy(perModel = snapshot.perModel + (model to updated)))
  }

  /** Marks a model rate-limited (HTTP 429) for the rest of the Pacific day. */
  @Synchronized
  fun markRateLimited(filesDir: File, model: String) {
    val snapshot = load(filesDir)
    val usage = snapshot.usage(model)
    if (usage.rateLimited) return
    save(
      filesDir,
      snapshot.copy(perModel = snapshot.perModel + (model to usage.copy(rateLimited = true)))
    )
  }
}
