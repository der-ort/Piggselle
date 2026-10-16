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

package com.google.ai.edge.gallery.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.net.CertInfo
import com.google.ai.edge.gallery.assistant.net.HostPolicy
import com.google.ai.edge.gallery.assistant.net.NetLogEntry
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.search.SearxngClient
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrivacySheet(
  repository: AssistantRepository,
  onDismiss: () -> Unit,
  onWebSearchChanged: () -> Unit,
) {
  val state by repository.state.collectAsState()
  val policy by NetworkGate.policy.collectAsState()
  val log by NetworkGate.log.collectAsState()
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var urlDraft by remember { mutableStateOf(policy.searxngBaseUrl) }
  var urlError by remember { mutableStateOf<String?>(null) }
  var testResult by remember { mutableStateOf<String?>(null) }
  var certInfo by remember { mutableStateOf<CertInfo?>(null) }
  var newTerm by remember { mutableStateOf("") }
  var showLog by remember { mutableStateOf(false) }

  fun saveUrl(): Boolean {
    val error = HostPolicy.validateSearxngBase(urlDraft)
    urlError = error
    if (error == null && urlDraft.trim().trimEnd('/') != policy.searxngBaseUrl) {
      NetworkGate.setSearxngBaseUrl(urlDraft)
      onWebSearchChanged()
    }
    return error == null
  }

  ModalBottomSheet(onDismissRequest = { saveUrl(); onDismiss() }, sheetState = sheetState) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
      item {
        Text("Privacy & network", style = MaterialTheme.typography.headlineSmall)
        Text(
          "Chats, memories and personas never leave this phone. Only the items switched on below " +
            "may use the network, and every attempt is logged here.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )

        SwitchRow(
          title = "Offline mode",
          subtitle = "Block all network access, whatever else is enabled",
          checked = policy.killSwitch,
          onChange = {
            NetworkGate.setKillSwitch(it)
            onWebSearchChanged()
          },
        )
        HorizontalDivider()

        SectionTitle("Web search (SearXNG)")
        SwitchRow(
          title = "Allow web search",
          subtitle = "The assistant may propose searches on your own instance",
          checked = policy.enabled[NetPurpose.WEB_SEARCH] == true,
          onChange = {
            if (it) saveUrl()
            NetworkGate.setPurposeEnabled(NetPurpose.WEB_SEARCH, it)
            onWebSearchChanged()
          },
        )
        OutlinedTextField(
          value = urlDraft,
          onValueChange = {
            urlDraft = it.trim()
            urlError = null
            testResult = null
          },
          label = { Text("Instance URL") },
          placeholder = { Text("https://search.example.org or http://100.x.y.z:8080") },
          isError = urlError != null,
          supportingText = {
            Text(
              urlError
                ?: ("Needs JSON output enabled (search → formats: [html, json]). Plain http only " +
                  "inside your LAN or tailnet.")
            )
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { saveUrl() }) { Text("Save") }
          TextButton(
            onClick = {
              if (!saveUrl()) return@TextButton
              testResult = "Testing…"
              scope.launch {
                testResult =
                  withContext(Dispatchers.IO) {
                    certInfo = null
                    try {
                      val r = SearxngClient.search(policy.searxngBaseUrl.ifBlank { urlDraft }, "weather", "auto")
                      "Works: ${r.hits.size} results for the test query \"weather\"."
                    } catch (e: javax.net.ssl.SSLException) {
                      // Show the certificate so the user can decide whether to trust it.
                      certInfo = runCatching { NetworkGate.probeSearchCertificate() }.getOrNull()
                      "TLS failed: Android doesn't trust this server's certificate" +
                        (certInfo?.systemError?.let { " ($it)" } ?: " (${e.message})") + "."
                    } catch (e: Exception) {
                      "Failed: ${e.message}"
                    }
                  }
              }
            }
          ) {
            Text("Test with \"weather\"")
          }
        }
        testResult?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        certInfo?.let { c ->
          androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Server certificate", style = MaterialTheme.typography.titleSmall)
              Text("Subject: ${c.subject}", style = MaterialTheme.typography.bodySmall)
              Text("Issuer: ${c.issuer}" + if (c.selfSigned) " (self-signed)" else "", style = MaterialTheme.typography.bodySmall)
              Text("Valid until: ${java.text.DateFormat.getDateInstance().format(java.util.Date(c.notAfterMs))}", style = MaterialTheme.typography.bodySmall)
              Text("SHA-256: ${c.sha256}", style = MaterialTheme.typography.labelSmall)
              Text(
                "Only trust it if this fingerprint matches your server (e.g. check it in Caddy, " +
                  "Traefik or with openssl). The exception applies to this host only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              androidx.compose.material3.Button(
                onClick = {
                  NetworkGate.setSearxngPin(c.sha256)
                  certInfo = null
                  testResult = "Certificate trusted for this host. Test again."
                }
              ) {
                Text("Trust this certificate")
              }
            }
          }
        }
        if (policy.searxngPin.isNotEmpty()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "Using a certificate you trusted (${policy.searxngPin.take(11)}…)",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { NetworkGate.setSearxngPin("") }) { Text("Forget") }
          }
        }

        SwitchRow(
          title = "Send filtered queries automatically",
          subtitle =
            "Skip the confirmation step. Queries are still filtered, and health topics always ask.",
          checked = state.search.autoSend,
          onChange = { on -> repository.updateSearch { it.copy(autoSend = on) } },
        )

        Text(
          "Results per search: ${state.search.maxResults}",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
          value = state.search.maxResults.toFloat(),
          onValueChange = { v -> repository.updateSearch { it.copy(maxResults = v.toInt()) } },
          valueRange = 3f..8f,
          steps = 4,
        )
        OutlinedTextField(
          value = state.search.language,
          onValueChange = { v -> repository.updateSearch { it.copy(language = v.trim().take(10)) } },
          label = { Text("Search language") },
          supportingText = { Text("\"auto\", or a code like \"de\" or \"en\"") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Never send these words")
        Text(
          "Names, places, your employer… They are removed from every query. Your persona name for " +
            "yourself is always included.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(vertical = 6.dp),
        ) {
          state.search.privateTerms.forEach { term ->
            InputChip(
              selected = false,
              onClick = {},
              label = { Text(term) },
              trailingIcon = {
                IconButton(
                  onClick = {
                    repository.updateSearch { s -> s.copy(privateTerms = s.privateTerms - term) }
                  },
                  modifier = Modifier.size(18.dp),
                ) {
                  Icon(Icons.Rounded.Close, contentDescription = "Remove $term")
                }
              },
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = newTerm,
            onValueChange = { newTerm = it.take(60) },
            placeholder = { Text("Add a word or name") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            enabled = newTerm.isNotBlank(),
            onClick = {
              val t = newTerm.trim()
              repository.updateSearch { s ->
                if (s.privateTerms.any { it.equals(t, ignoreCase = true) }) s
                else s.copy(privateTerms = s.privateTerms + t)
              }
              newTerm = ""
            },
          ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add term")
          }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        SectionTitle("Models")
        SwitchRow(
          title = "Model downloads",
          subtitle = "Download model files you pick from Hugging Face. Sends no personal data.",
          checked = policy.enabled[NetPurpose.MODEL_DOWNLOAD] == true,
          onChange = { NetworkGate.setPurposeEnabled(NetPurpose.MODEL_DOWNLOAD, it) },
        )
        SwitchRow(
          title = "Browse Hugging Face catalog",
          subtitle = "Sends your catalog search terms to Hugging Face",
          checked = policy.enabled[NetPurpose.MODEL_CATALOG] == true,
          onChange = { NetworkGate.setPurposeEnabled(NetPurpose.MODEL_CATALOG, it) },
        )
        Text(
          "Tip for GrapheneOS: once your models are downloaded and you don't use web search, you " +
            "can also revoke Piggselle's Network permission in the system settings.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
          SectionTitle("Network log (${log.size})")
          TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
          if (log.isNotEmpty()) TextButton(onClick = { NetworkGate.clearLog() }) { Text("Clear") }
        }
      }

      if (showLog) {
        if (log.isEmpty()) {
          item {
            Text(
              "No network activity yet.",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(bottom = 16.dp),
            )
          }
        }
        items(log.take(200)) { entry -> LogRow(entry) }
      }
    }
  }
}

@Composable
private fun LogRow(entry: NetLogEntry) {
  val time = remember(entry.timeMs) {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(entry.timeMs))
  }
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
    Icon(
      if (entry.allowed) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
      contentDescription = if (entry.allowed) "Allowed" else "Blocked",
      tint =
        if (entry.allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      modifier = Modifier.size(18.dp).padding(top = 2.dp),
    )
    Column(modifier = Modifier.padding(start = 8.dp)) {
      val purpose = runCatching { NetPurpose.valueOf(entry.purpose).label }.getOrDefault(entry.purpose)
      Text(
        "$time · $purpose" + if (entry.status > 0) " · HTTP ${entry.status}" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "${entry.method} ${entry.host}${entry.target}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      if (!entry.allowed && entry.reason.isNotEmpty()) {
        Text(entry.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}
