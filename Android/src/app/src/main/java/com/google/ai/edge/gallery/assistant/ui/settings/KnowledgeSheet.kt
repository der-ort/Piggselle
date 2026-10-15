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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.knowledge.EMBEDDER_SIZE_BYTES
import com.google.ai.edge.gallery.assistant.knowledge.EmbedderManager
import com.google.ai.edge.gallery.assistant.knowledge.EmbedderStatus
import com.google.ai.edge.gallery.assistant.knowledge.KnowledgeBase
import com.google.ai.edge.gallery.assistant.ui.SectionTitle
import com.google.ai.edge.gallery.assistant.ui.SwitchRow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeSheet(
  knowledge: KnowledgeBase,
  embedder: EmbedderManager,
  onDismiss: () -> Unit,
  onChanged: () -> Unit,
) {
  val state by knowledge.state.collectAsState()
  val embedStatus by embedder.status.collectAsState()
  val scope = rememberCoroutineScope()
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
      if (uri != null) {
        knowledge.setFolder(uri)
        onChanged()
      }
    }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(
      modifier =
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text("Knowledge base", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Pick a folder of notes (Markor, Obsidian, text, Markdown, CSV, JSON, HTML). Piggselle reads " +
          "it, never changes it, and keeps an encrypted index on this phone. Relevant passages are " +
          "added to your messages when note search is on.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      SectionTitle("Folder")
      if (state.configured) {
        Text(state.settings.folderName, style = MaterialTheme.typography.titleMedium)
        Text(
          "${state.docCount} files · ${state.chunkCount} passages" +
            (if (state.vectorCount > 0) " · ${state.vectorCount} embedded" else "") +
            (if (state.settings.lastIndexedMs > 0)
              " · updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.settings.lastIndexedMs))
            else ""),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.indexing) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        if (state.progress.isNotBlank()) Text(state.progress, style = MaterialTheme.typography.labelMedium)
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { knowledge.reindex() }, enabled = !state.indexing) { Text("Re-scan") }
          OutlinedButton(onClick = { picker.launch(null) }) { Text("Change folder") }
          TextButton(onClick = { knowledge.removeFolder(); onChanged() }) { Text("Remove") }
        }
      } else {
        Button(onClick = { picker.launch(null) }) { Text("Choose folder") }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      SwitchRow(
        title = "Search notes in new chats",
        subtitle = "Default; each chat or folder can override it",
        checked = state.settings.onForNewChats,
        onChange = { knowledge.setOnForNewChats(it); onChanged() },
      )

      SectionTitle("Smarter search (optional)")
      Text(
        "Keyword search works offline right away. EmbeddingGemma 2 also finds passages that mean the " +
          "same thing in other words, in German and English. It runs on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      when (val s = embedStatus) {
        EmbedderStatus.NotDownloaded ->
          Button(onClick = { embedder.download() }) {
            Text("Download EmbeddingGemma 2 (${EMBEDDER_SIZE_BYTES / 1_000_000} MB)")
          }
        is EmbedderStatus.Downloading -> {
          LinearProgressIndicator(
            progress = { if (s.total > 0) s.received.toFloat() / s.total else 0f },
            modifier = Modifier.fillMaxWidth(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${s.received / 1_000_000} / ${s.total / 1_000_000} MB", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { embedder.cancelDownload() }) { Text("Cancel") }
          }
        }
        is EmbedderStatus.Failed -> {
          Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Button(onClick = { embedder.download() }) { Text("Retry download") }
        }
        EmbedderStatus.Ready -> {
          SwitchRow(
            title = "Use EmbeddingGemma",
            subtitle = "Embeds new or changed notes when the folder is scanned",
            checked = state.settings.semantic,
            onChange = { knowledge.setSemantic(it); onChanged() },
          )
          TextButton(onClick = { scope.launch { embedder.delete() } }) { Text("Delete EmbeddingGemma") }
        }
      }
      if (embedStatus is EmbedderStatus.Ready && state.configured && state.settings.semantic && state.vectorCount < state.chunkCount && !state.indexing) {
        TextButton(onClick = { knowledge.reindex() }) { Text("Embed ${state.chunkCount - state.vectorCount} remaining passages") }
      }
    }
  }
}
