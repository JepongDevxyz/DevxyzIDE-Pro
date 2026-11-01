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

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal client for the Gemini API (v1beta generateContent), used only by
 * Devxyz Fable's AI phase and only with the user's own API key (BYOK).
 * Plain HttpURLConnection + org.json — no new dependencies. Every call is
 * blocking; callers run it off the UI thread.
 */
object FableGeminiClient {

  sealed class Part {
    data class Text(val text: String) : Part()

    /** A base64-encoded image (JPEG/PNG) for vision calls. */
    data class Image(val base64: String, val mimeType: String) : Part()
  }

  data class Turn(val role: String, val parts: List<Part>)

  sealed class Result {
    data class Ok(val text: String) : Result()
    object RateLimited : Result()
    data class Failed(val detail: String) : Result()
  }

  private const val ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/"

  /**
   * One generateContent call. [turns] are the conversation contents in
   * order (roles "user" / "model"); [systemPrompt] becomes the system
   * instruction. Returns the model's text, a rate-limit signal on HTTP
   * 429, or an honest failure detail — never throws.
   */
  fun generate(
    apiKey: String,
    model: String,
    systemPrompt: String,
    turns: List<Turn>,
    temperature: Double = 0.4
  ): Result {
    return try {
      val body = buildRequestBody(systemPrompt, turns, temperature)
      val url = URL(ENDPOINT + model + ":generateContent")
      val connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.connectTimeout = 30_000
      connection.readTimeout = 120_000
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("x-goog-api-key", apiKey)
      connection.outputStream.use { stream ->
        stream.write(body.toString().toByteArray(Charsets.UTF_8))
      }

      val code = connection.responseCode
      if (code == 429) {
        return Result.RateLimited
      }
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      if (code !in 200..299) {
        return Result.Failed("HTTP $code: ${errorMessage(responseText)}")
      }
      val text = extractText(responseText)
      if (text.isNullOrBlank()) {
        Result.Failed("the model returned an empty reply")
      } else {
        Result.Ok(text)
      }
    } catch (e: Exception) {
      Result.Failed(e.message ?: "network error")
    }
  }

  private fun buildRequestBody(
    systemPrompt: String,
    turns: List<Turn>,
    temperature: Double
  ): JSONObject {
    val body = JSONObject()

    val systemParts = JSONArray()
    val systemText = JSONObject()
    systemText.put("text", systemPrompt)
    systemParts.put(systemText)
    val systemInstruction = JSONObject()
    systemInstruction.put("parts", systemParts)
    body.put("systemInstruction", systemInstruction)

    val contents = JSONArray()
    for (turn in turns) {
      val parts = JSONArray()
      for (part in turn.parts) {
        val partJson = JSONObject()
        when (part) {
          is Part.Text -> partJson.put("text", part.text)
          is Part.Image -> {
            val inline = JSONObject()
            inline.put("mime_type", part.mimeType)
            inline.put("data", part.base64)
            partJson.put("inline_data", inline)
          }
        }
        parts.put(partJson)
      }
      val content = JSONObject()
      content.put("role", turn.role)
      content.put("parts", parts)
      contents.put(content)
    }
    body.put("contents", contents)

    val generationConfig = JSONObject()
    generationConfig.put("temperature", temperature)
    body.put("generationConfig", generationConfig)
    return body
  }

  private fun extractText(responseText: String): String? {
    return try {
      val json = JSONObject(responseText)
      val candidates = json.optJSONArray("candidates") ?: return null
      if (candidates.length() == 0) return null
      val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
      val parts = content.optJSONArray("parts") ?: return null
      val out = StringBuilder()
      for (i in 0 until parts.length()) {
        val text = parts.optJSONObject(i)?.optString("text") ?: continue
        if (out.isNotEmpty()) out.append('\n')
        out.append(text)
      }
      out.toString()
    } catch (e: Exception) {
      null
    }
  }

  private fun errorMessage(responseText: String): String {
    return try {
      val json = JSONObject(responseText)
      json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
        ?: "no detail"
    } catch (e: Exception) {
      responseText.take(160).ifEmpty { "no detail" }
    }
  }
}
