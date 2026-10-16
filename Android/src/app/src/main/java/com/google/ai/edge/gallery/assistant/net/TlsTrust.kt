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

import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Details of a server certificate, shown before the user decides to trust it. */
data class CertInfo(
  val sha256: String,
  val subject: String,
  val issuer: String,
  val notAfterMs: Long,
  val selfSigned: Boolean,
  /** Why Android's normal checks rejected it, if they did. */
  val systemError: String?,
)

/**
 * Trust-on-first-use for the user's own SearXNG: if its certificate isn't trusted by Android
 * (self-signed, private CA, or an IP address not in the certificate), the user can pin that exact
 * certificate. The pin only ever applies to the configured search host.
 */
object TlsTrust {
  fun sha256(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

  private val systemTrustManager: X509TrustManager by lazy {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
  }

  /** Accepts the chain if Android trusts it, or if its leaf certificate matches [pin]. */
  private class PinningTrustManager(private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
      throw CertificateException("Client certificates are not supported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
      try {
        systemTrustManager.checkServerTrusted(chain, authType)
      } catch (e: CertificateException) {
        if (chain.isEmpty() || !sha256(chain[0]).equals(pin, ignoreCase = true)) throw e
      }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
  }

  private fun pinnedFactory(pin: String): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinningTrustManager(pin)), null) }.socketFactory

  /** Hostname check that also accepts the pinned certificate (e.g. when using an IP address). */
  private fun pinnedHostnameVerifier(pin: String): HostnameVerifier {
    val default = HttpsURLConnection.getDefaultHostnameVerifier()
    return HostnameVerifier { host, session ->
      default.verify(host, session) ||
        (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { sha256(it).equals(pin, true) } == true
    }
  }

  fun applyPin(connection: HttpsURLConnection, pin: String) {
    connection.sslSocketFactory = pinnedFactory(pin)
    connection.hostnameVerifier = pinnedHostnameVerifier(pin)
  }

  /**
   * Performs only a TLS handshake (no HTTP request) with the host of [url] and returns its
   * certificate. Callers must have checked NetworkGate first.
   */
  fun probe(url: String): CertInfo {
    val uri = URI(url)
    val host = uri.host ?: error("No host")
    val port = if (uri.port > 0) uri.port else 443
    var captured: Array<out X509Certificate> = emptyArray()
    var systemError: String? = null
    val capturing =
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
          captured = chain
          try {
            systemTrustManager.checkServerTrusted(chain, authType)
          } catch (e: CertificateException) {
            systemError = e.message ?: "Not trusted"
          }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
    val factory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturing), null) }.socketFactory
    (factory.createSocket() as SSLSocket).use { socket ->
      socket.soTimeout = 10_000
      socket.connect(InetSocketAddress(host, port), 10_000)
      socket.startHandshake()
      val leaf = captured.firstOrNull() ?: error("Server sent no certificate")
      val hostOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)
      return CertInfo(
        sha256 = sha256(leaf),
        subject = leaf.subjectX500Principal.name,
        issuer = leaf.issuerX500Principal.name,
        notAfterMs = leaf.notAfter.time,
        selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
        systemError = systemError ?: if (!hostOk) "Certificate doesn't match \"$host\"" else null,
      )
    }
  }
}
