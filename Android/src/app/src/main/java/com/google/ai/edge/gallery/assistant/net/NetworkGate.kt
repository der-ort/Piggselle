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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.ai.edge.gallery.assistant.storage.SecureStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "PGNetworkGate"
private const val PREFS = "piggselle_network_policy"
private const val LOG_FILE = "network_log.bin"
private const val MAX_LOG_ENTRIES = 500
private const val MAX_REDIRECTS = 5

/** Thrown when the gate refuses a request. It is an [IOException] so callers' error paths apply. */
class BlockedRequestException(message: String) : IOException(message)

@Serializable
data class NetLogEntry(
  val timeMs: Long,
  val purpose: String,
  val method: String,
  val host: String,
  /** Path plus query, exactly as sent. For web searches this includes the query text. */
  val target: String,
  val allowed: Boolean,
  val reason: String = "",
  val status: Int = -1,
)

/**
 * The single choke point for outgoing HTTP(S) in Piggselle.
 *
 * Every connection in the app is opened through [open] or [openFollowingRedirects] with a
 * [NetPurpose]. The gate checks the global kill switch, whether that purpose is enabled, and
 * [HostPolicy] for the target host, then logs the attempt (allowed or blocked) to an encrypted local
 * log the user can review in Settings.
 *
 * Redirects are never followed automatically: each hop is re-checked against the policy, and
 * Authorization headers are not forwarded to a different host. Requests carry a neutral
 * User-Agent instead of Android's default, which includes the device model and build.
 *
 * As a second line of defence, GrapheneOS users can additionally revoke the Network permission for
 * the app whenever they don't need web search.
 */
object NetworkGate {
  const val USER_AGENT = "Piggselle"

  private lateinit var appContext: Context
  private lateinit var prefs: SharedPreferences
  private val logWriter = Executors.newSingleThreadExecutor()

  private val _log = MutableStateFlow<List<NetLogEntry>>(emptyList())
  val log: StateFlow<List<NetLogEntry>> = _log.asStateFlow()

  private val _policy = MutableStateFlow(Policy())
  val policy: StateFlow<Policy> = _policy.asStateFlow()

  data class Policy(
    val killSwitch: Boolean = false,
    val enabled: Map<NetPurpose, Boolean> = defaultEnabled(),
    val searxngBaseUrl: String = "",
  ) {
    fun isEnabled(purpose: NetPurpose): Boolean =
      !killSwitch && purpose.userToggleable && (enabled[purpose] ?: false)
  }

  private fun defaultEnabled() =
    mapOf(
      NetPurpose.WEB_SEARCH to false,
      NetPurpose.MODEL_DOWNLOAD to true,
      NetPurpose.MODEL_CATALOG to false,
      NetPurpose.UNCLASSIFIED to false,
    )

  @Volatile private var initialized = false

  fun init(context: Context) {
    if (initialized) return
    appContext = context.applicationContext
    prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    _policy.value =
      Policy(
        killSwitch = prefs.getBoolean("kill_switch", false),
        enabled =
          NetPurpose.entries.associateWith { p ->
            p.userToggleable && prefs.getBoolean("enabled_${p.name}", defaultEnabled()[p] ?: false)
          },
        searxngBaseUrl = prefs.getString("searxng_base_url", "") ?: "",
      )
    logWriter.execute {
      val saved =
        SecureStore.read(appContext, LOG_FILE, ListSerializer(NetLogEntry.serializer()))
          ?: emptyList()
      _log.update { current -> (current + saved).sortedByDescending { it.timeMs }.take(MAX_LOG_ENTRIES) }
    }
    initialized = true
  }

  // ---- Policy setters -------------------------------------------------------------------------

  fun setKillSwitch(on: Boolean) {
    prefs.edit().putBoolean("kill_switch", on).apply()
    _policy.update { it.copy(killSwitch = on) }
  }

  fun setPurposeEnabled(purpose: NetPurpose, on: Boolean) {
    if (!purpose.userToggleable) return
    prefs.edit().putBoolean("enabled_${purpose.name}", on).apply()
    _policy.update { it.copy(enabled = it.enabled + (purpose to on)) }
  }

  fun setSearxngBaseUrl(url: String) {
    val cleaned = url.trim().trimEnd('/')
    prefs.edit().putString("searxng_base_url", cleaned).apply()
    _policy.update { it.copy(searxngBaseUrl = cleaned) }
  }

  fun clearLog() {
    _log.value = emptyList()
    logWriter.execute { SecureStore.delete(appContext, LOG_FILE) }
  }

  // ---- Checks and connections -----------------------------------------------------------------

  /** Returns null if allowed, otherwise the reason. Does not log. */
  fun check(url: String, purpose: NetPurpose): String? {
    val p = _policy.value
    if (!initialized) return "Network gate not initialized"
    if (p.killSwitch) return "Offline mode is on"
    if (!p.isEnabled(purpose)) return "${purpose.label} is turned off in Privacy settings"
    return HostPolicy.check(url, purpose, p.searxngBaseUrl)
  }

  /**
   * Opens a connection without following redirects. Throws [BlockedRequestException] if the gate
   * refuses. The caller configures and connects it.
   */
  fun open(url: String, purpose: NetPurpose, method: String = "GET"): HttpURLConnection {
    val reason = check(url, purpose)
    record(url, purpose, method, allowed = reason == null, reason = reason ?: "")
    if (reason != null) {
      Log.w(TAG, "Blocked ${purpose.name} request to ${hostOf(url)}: $reason")
      throw BlockedRequestException(reason)
    }
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.requestMethod = method
    connection.setRequestProperty("User-Agent", USER_AGENT)
    return connection
  }

  /**
   * Opens [url] and follows up to [MAX_REDIRECTS] redirects, re-checking every hop. [configure] is
   * called for each hop to set headers; `sameHostAsFirst` is false once a redirect left the original
   * host, so callers must not attach credentials then. Returns a connection whose response code has
   * been read and is not a redirect.
   */
  fun openFollowingRedirects(
    url: String,
    purpose: NetPurpose,
    method: String = "GET",
    configure: (connection: HttpURLConnection, sameHostAsFirst: Boolean) -> Unit = { _, _ -> },
  ): HttpURLConnection {
    val firstHost = hostOf(url)
    var current = url
    repeat(MAX_REDIRECTS + 1) {
      val connection = open(current, purpose, method)
      configure(connection, hostOf(current) == firstHost)
      val code = connection.responseCode
      if (code !in 300..399 || code == 304) {
        updateLastStatus(current, code)
        return connection
      }
      val location = connection.getHeaderField("Location")
      connection.disconnect()
      if (location.isNullOrEmpty()) throw IOException("Redirect without Location header")
      current = URI(current).resolve(location).toString()
    }
    throw IOException("Too many redirects")
  }

  private fun hostOf(url: String): String =
    try {
      URI(url).host ?: ""
    } catch (e: Exception) {
      ""
    }

  private fun record(url: String, purpose: NetPurpose, method: String, allowed: Boolean, reason: String) {
    val uri = runCatching { URI(url) }.getOrNull()
    val target =
      if (uri == null) url
      else (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
    val entry =
      NetLogEntry(
        timeMs = System.currentTimeMillis(),
        purpose = purpose.name,
        method = method,
        host = uri?.host ?: "?",
        target = target.take(2000),
        allowed = allowed,
        reason = reason,
      )
    _log.update { (listOf(entry) + it).take(MAX_LOG_ENTRIES) }
    persistLog()
  }

  private fun updateLastStatus(url: String, status: Int) {
    val host = hostOf(url)
    _log.update { list ->
      val idx = list.indexOfFirst { it.host == host && it.status == -1 && it.allowed }
      if (idx < 0) list else list.toMutableList().also { it[idx] = it[idx].copy(status = status) }
    }
    persistLog()
  }

  private fun persistLog() {
    if (!initialized) return
    val snapshot = _log.value
    logWriter.execute {
      SecureStore.write(appContext, LOG_FILE, ListSerializer(NetLogEntry.serializer()), snapshot)
    }
  }
}
