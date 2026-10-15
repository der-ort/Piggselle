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

package com.google.ai.edge.gallery.assistant.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.ui.MemorySheet
import com.google.ai.edge.gallery.assistant.ui.PersonaSheet
import com.google.ai.edge.gallery.assistant.ui.PrivacySheet
import com.google.ai.edge.gallery.assistant.ui.chat.ChatViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.PIGGSELLE_REPO_URL
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog

private enum class Sheet {
  PERSONA,
  MEMORY,
  KNOWLEDGE,
  PRIVACY,
  ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenModels: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
  val context = LocalContext.current
  val assistant by vm.repository.state.collectAsState()
  val kb by vm.knowledge.state.collectAsState()
  val policy by NetworkGate.policy.collectAsState()
  var sheet by remember { mutableStateOf<Sheet?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
      item {
        SettingRow(
          Icons.Outlined.Face,
          "Persona & default system prompt",
          "${assistant.activePersona.emoji} ${assistant.activePersona.name}",
        ) { sheet = Sheet.PERSONA }
      }
      item {
        SettingRow(
          Icons.Outlined.Psychology,
          "Memory",
          if (assistant.memoryEnabled) "${assistant.memories.size} saved facts" else "Off",
        ) { sheet = Sheet.MEMORY }
      }
      item {
        SettingRow(
          Icons.Outlined.Description,
          "Knowledge base",
          if (kb.configured) "${kb.settings.folderName} · ${kb.docCount} files" else "No folder chosen",
        ) { sheet = Sheet.KNOWLEDGE }
      }
      item {
        val web =
          when {
            policy.killSwitch -> "Offline mode"
            policy.isEnabled(NetPurpose.WEB_SEARCH) && policy.searxngBaseUrl.isNotBlank() -> "Web search via SearXNG"
            else -> "Fully local"
          }
        SettingRow(Icons.Outlined.Shield, "Privacy & web search", web) { sheet = Sheet.PRIVACY }
      }
      item { SettingRow(Icons.Outlined.Memory, "Models", "Download, import from Hugging Face, delete") { onOpenModels() } }
      item { HorizontalDivider() }
      item { SettingRow(Icons.Outlined.Info, "About Piggselle", "Version ${BuildConfig.VERSION_NAME}") { sheet = Sheet.ABOUT } }
      item {
        SettingRow(Icons.Outlined.Gavel, "Open-source licenses", "Third-party notices") {
          context.startActivity(Intent(context, com.google.android.gms.oss.licenses.OssLicensesMenuActivity::class.java))
        }
      }
    }
  }

  when (sheet) {
    Sheet.PERSONA -> PersonaSheet(vm.repository, onDismiss = { sheet = null }, onPersonaApplied = { vm.engine.settingsChanged() })
    Sheet.MEMORY ->
      MemorySheet(
        repository = vm.repository,
        onDismiss = { sheet = null },
        onReviewChat = {
          sheet = null
          onBack()
          vm.engine.send(
            "Look back at this chat. For each lasting fact about me that would help in future chats, " +
              "call suggest_memory once. Skip anything already remembered. Then briefly say what you suggested."
          )
        },
        onMemoryChanged = { vm.engine.settingsChanged() },
      )
    Sheet.KNOWLEDGE ->
      KnowledgeSheet(vm.knowledge, vm.embedder, onDismiss = { sheet = null }, onChanged = { vm.engine.settingsChanged() })
    Sheet.PRIVACY -> PrivacySheet(vm.repository, onDismiss = { sheet = null }, onWebSearchChanged = { vm.engine.settingsChanged() })
    Sheet.ABOUT -> {
      AppTosDialog(onTosAccepted = { sheet = null }, viewingMode = true)
    }
    null -> {}
  }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(subtitle) },
    leadingContent = { Icon(icon, contentDescription = null) },
    modifier = Modifier.clickable(onClick = onClick),
  )
}
