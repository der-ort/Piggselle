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

package com.google.ai.edge.gallery.assistant.net

import java.net.URI

/** Why the app wants to talk to the network. Every outgoing request must name one. */
enum class NetPurpose(val label: String, val userToggleable: Boolean) {
  /** Filtered web search through the user's own SearXNG instance. */
  WEB_SEARCH("Web search (SearXNG)", true),
  /** Downloading model files the user picked. Sends no personal data. */
  MODEL_DOWNLOAD("Model downloads (Hugging Face)", true),
  /** Browsing the Hugging Face model catalog. Sends search terms to Hugging Face. */
  MODEL_CATALOG("Model catalog browsing (Hugging Face)", true),
  /** Anything not explicitly classified. Always blocked. */
  UNCLASSIFIED("Unclassified", false),
}

/** Pure, Android-free policy decisions, so they can be unit tested on the JVM. */
object HostPolicy {
  private val MODEL_HOSTS = setOf("huggingface.co")
  private val MODEL_HOST_SUFFIXES = listOf(".huggingface.co", ".hf.co")

  /**
   * Returns null if [url] may be requested for [purpose], or a human-readable reason why not.
   *
   * @param searxngBaseUrl the configured SearXNG base URL; the only host web searches may reach.
   */
  fun check(url: String, purpose: NetPurpose, searxngBaseUrl: String): String? {
    val uri =
      try {
        URI(url)
      } catch (e: Exception) {
        return "Malformed URL"
      }
    val scheme = uri.scheme?.lowercase() ?: return "Missing scheme"
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return "Missing host"
    if (uri.rawUserInfo != null) return "Credentials in URL are not allowed"
    if (scheme != "https" && scheme != "http") return "Scheme '$scheme' is not allowed"
    if (scheme == "http" && !isPrivateHost(host)) {
      return "Plain http is only allowed inside your LAN or tailnet"
    }

    return when (purpose) {
      NetPurpose.UNCLASSIFIED -> "Unclassified traffic is always blocked"
      NetPurpose.MODEL_DOWNLOAD,
      NetPurpose.MODEL_CATALOG ->
        if (scheme == "https" && isModelHost(host)) null
        else "Host '$host' is not an allowed model source"
      NetPurpose.WEB_SEARCH -> {
        val configured = parseBase(searxngBaseUrl) ?: return "No SearXNG instance configured"
        val samePort = effectivePort(uri) == effectivePort(configured)
        if (
          host == configured.host?.lowercase()?.trimEnd('.') &&
            scheme == configured.scheme?.lowercase() &&
            samePort
        ) {
          null
        } else {
          "Web search may only reach ${configured.host}"
        }
      }
    }
  }

  fun isModelHost(host: String): Boolean =
    host in MODEL_HOSTS || MODEL_HOST_SUFFIXES.any { host.endsWith(it) }

  /** Validates a user-entered SearXNG base URL. Returns null if fine, or a reason. */
  fun validateSearxngBase(base: String): String? {
    if (base.isBlank()) return "Enter the URL of your SearXNG instance"
    val uri = parseBase(base) ?: return "That doesn't look like a URL"
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return "Use http:// or https://"
    val host = uri.host?.lowercase() ?: return "Missing host"
    if (uri.rawUserInfo != null) return "Don't put credentials in the URL"
    if (scheme == "http" && !isPrivateHost(host)) {
      return "Plain http is only allowed for LAN or Tailscale addresses"
    }
    if (isModelHost(host)) return "That's a model host, not a SearXNG instance"
    return null
  }

  fun parseBase(base: String): URI? =
    try {
      URI(base.trim()).takeIf { it.host != null && it.scheme != null }
    } catch (e: Exception) {
      null
    }

  private fun effectivePort(uri: URI): Int =
    when {
      uri.port != -1 -> uri.port
      uri.scheme.equals("https", ignoreCase = true) -> 443
      else -> 80
    }

  /**
   * True for loopback, RFC 1918, link-local, CGNAT/Tailscale (100.64.0.0/10), IPv6 ULA/link-local,
   * and well-known private DNS suffixes such as Tailscale MagicDNS (*.ts.net).
   */
  fun isPrivateHost(rawHost: String): Boolean {
    val host = rawHost.lowercase().trim('[', ']').trimEnd('.')
    if (host == "localhost") return true
    val privateSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net")
    if (privateSuffixes.any { host.endsWith(it) }) return true

    parseIpv4(host)?.let { o ->
      return when {
        o[0] == 10 -> true
        o[0] == 127 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        else -> false
      }
    }

    if (host.contains(':')) {
      if (host == "::1") return true
      val first = host.substringBefore(':')
      val value = first.toIntOrNull(16) ?: return false
      if (value and 0xfe00 == 0xfc00) return true // fc00::/7 unique local
      if (value and 0xffc0 == 0xfe80) return true // fe80::/10 link local
      if (host.startsWith("fd7a:115c:a1e0")) return true // Tailscale IPv6 range
    }
    return false
  }

  private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
      val v = parts[i].toIntOrNull() ?: return null
      if (v !in 0..255) return null
      octets[i] = v
    }
    return octets
  }
}
