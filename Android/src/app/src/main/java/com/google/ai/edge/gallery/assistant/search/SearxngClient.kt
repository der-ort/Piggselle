/*
 * Copyright 2026 Daniel (der-ort)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.assistant.search

import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val TIMEOUT_MS = 15_000
private const val MAX_RESPONSE_BYTES = 2_000_000

class SearchException(message: String) : IOException(message)

/**
 * Minimal SearXNG JSON API client. Only the query (plus fixed format/safesearch/language
 * parameters) is sent; no cookies, no Accept-Language, a neutral User-Agent (set by NetworkGate),
 * and no pages are ever fetched — only the instance's result list.
 */
object SearxngClient {
  private val json = Json { ignoreUnknownKeys = true }

  fun buildUrl(baseUrl: String, query: String, language: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val q = URLEncoder.encode(query, "UTF-8")
    val lang = language.trim().ifEmpty { "auto" }.let { URLEncoder.encode(it, "UTF-8") }
    return "$base/search?q=$q&format=json&safesearch=1&language=$lang"
  }

  /** Blocking. Call from a background thread. */
  fun search(baseUrl: String, query: String, language: String): SearchResponse {
    val url = buildUrl(baseUrl, query, language)
    val connection =
      NetworkGate.openFollowingRedirects(url, NetPurpose.WEB_SEARCH) { conn, _ ->
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
      }
    try {
      when (val code = connection.responseCode) {
        200 -> {}
        403 ->
          throw SearchException(
            "The SearXNG instance refused JSON output (HTTP 403). Enable it in settings.yml: " +
              "search → formats: [html, json]."
          )
        429 -> throw SearchException("The SearXNG instance is rate limiting (HTTP 429).")
        else -> throw SearchException("SearXNG returned HTTP $code.")
      }
      val body =
        connection.inputStream.use { input ->
          val bytes = input.readNBytesCompat(MAX_RESPONSE_BYTES)
          bytes.decodeToString()
        }
      return parse(body)
    } finally {
      connection.disconnect()
    }
  }

  fun parse(body: String): SearchResponse {
    val root =
      try {
        json.parseToJsonElement(body).jsonObject
      } catch (e: Exception) {
        throw SearchException("SearXNG didn't return JSON. Is the URL right?")
      }
    val hits =
      (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        SearchHit(
          title = o.str("title"),
          url = o.str("url"),
          snippet = o.str("content"),
        )
      }
    val answers =
      (root["answers"] as? JsonArray).orEmpty().mapNotNull { el ->
        when (el) {
          is JsonPrimitive -> el.content
          is JsonObject -> el.str("answer").ifBlank { null }
          else -> null
        }
      } +
        (root["infoboxes"] as? JsonArray).orEmpty().mapNotNull { el ->
          (el as? JsonObject)?.str("content")?.ifBlank { null }
        }
    return SearchResponse(answers = answers, hits = hits)
  }

  private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
      val n = read(buf, 0, minOf(buf.size, limit - total))
      if (n < 0) break
      out.write(buf, 0, n)
      total += n
    }
    return out.toByteArray()
  }
}
