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

import com.google.ai.edge.gallery.assistant.privacy.MarkdownSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {
  private val searx = "https://search.example.org"

  @Test
  fun searchOnlyReachesConfiguredInstance() {
    assertNull(HostPolicy.check("https://search.example.org/search?q=x", NetPurpose.WEB_SEARCH, searx))
    assertNotNull(HostPolicy.check("https://evil.example.org/search?q=x", NetPurpose.WEB_SEARCH, searx))
    assertNotNull(HostPolicy.check("https://search.example.org:8443/search", NetPurpose.WEB_SEARCH, searx))
    assertNotNull(HostPolicy.check("http://search.example.org/search", NetPurpose.WEB_SEARCH, searx))
  }

  @Test
  fun searchIsBlockedWithoutInstance() {
    assertNotNull(HostPolicy.check("https://search.example.org/search", NetPurpose.WEB_SEARCH, ""))
  }

  @Test
  fun modelDownloadsOnlyFromHuggingFace() {
    assertNull(HostPolicy.check("https://huggingface.co/a/b/resolve/main/m.litertlm", NetPurpose.MODEL_DOWNLOAD, ""))
    assertNull(HostPolicy.check("https://cas-bridge.xethub.hf.co/x", NetPurpose.MODEL_DOWNLOAD, ""))
    assertNull(HostPolicy.check("https://cdn-lfs.huggingface.co/x", NetPurpose.MODEL_DOWNLOAD, ""))
    assertNotNull(HostPolicy.check("https://huggingface.co.evil.com/x", NetPurpose.MODEL_DOWNLOAD, ""))
    assertNotNull(HostPolicy.check("https://example.com/m.litertlm", NetPurpose.MODEL_DOWNLOAD, ""))
    assertNotNull(HostPolicy.check("http://huggingface.co/x", NetPurpose.MODEL_DOWNLOAD, ""))
  }

  @Test
  fun unclassifiedIsAlwaysBlocked() {
    assertNotNull(HostPolicy.check("https://huggingface.co/x", NetPurpose.UNCLASSIFIED, searx))
  }

  @Test
  fun credentialsInUrlAreRejected() {
    assertNotNull(HostPolicy.check("https://user:pw@search.example.org/", NetPurpose.WEB_SEARCH, searx))
  }

  @Test
  fun plainHttpOnlyForPrivateHosts() {
    assertNull(HostPolicy.validateSearxngBase("http://100.101.102.103:8080"))
    assertNull(HostPolicy.validateSearxngBase("http://searx.tail1234.ts.net"))
    assertNull(HostPolicy.validateSearxngBase("http://192.168.1.20:8888"))
    assertNull(HostPolicy.validateSearxngBase("https://searx.example.org"))
    assertNotNull(HostPolicy.validateSearxngBase("http://searx.example.org"))
    assertNotNull(HostPolicy.validateSearxngBase("ftp://192.168.1.2"))
    assertNotNull(HostPolicy.validateSearxngBase(""))
  }

  @Test
  fun privateHostDetection() {
    assertTrue(HostPolicy.isPrivateHost("10.0.0.5"))
    assertTrue(HostPolicy.isPrivateHost("172.20.1.1"))
    assertTrue(HostPolicy.isPrivateHost("100.64.0.1"))
    assertTrue(HostPolicy.isPrivateHost("[fd7a:115c:a1e0::1]"))
    assertTrue(HostPolicy.isPrivateHost("localhost"))
    assertFalse(HostPolicy.isPrivateHost("100.128.0.1"))
    assertFalse(HostPolicy.isPrivateHost("172.32.0.1"))
    assertFalse(HostPolicy.isPrivateHost("8.8.8.8"))
    assertFalse(HostPolicy.isPrivateHost("example.com"))
  }

  @Test
  fun tailscaleHttpSearchIsAllowedWhenConfigured() {
    val base = "http://100.101.102.103:8080"
    assertNull(HostPolicy.check("http://100.101.102.103:8080/search?q=x&format=json", NetPurpose.WEB_SEARCH, base))
    assertNotNull(HostPolicy.check("http://100.101.102.103:9090/search", NetPurpose.WEB_SEARCH, base))
  }

  @Test
  fun markdownImagesBecomeLinks() {
    assertEquals("see [x](https://a.b/c.png)", MarkdownSanitizer.sanitize("see ![x](https://a.b/c.png)"))
    assertEquals("a  b", MarkdownSanitizer.sanitize("a <img src=\"https://t.co/p.gif\"> b"))
    assertEquals("plain **text**", MarkdownSanitizer.sanitize("plain **text**"))
  }
}
