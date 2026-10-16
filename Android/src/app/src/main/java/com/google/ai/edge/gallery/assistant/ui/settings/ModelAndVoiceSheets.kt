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

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.models.ModelSettingsStore
import com.google.ai.edge.gallery.assistant.ui.SectionTitle
import com.google.ai.edge.gallery.assistant.voice.WhisperLanguage
import com.google.ai.edge.gallery.assistant.voice.WhisperManager
import com.google.ai.edge.gallery.assistant.voice.WhisperSize
import com.google.ai.edge.gallery.assistant.voice.WhisperStatus
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsSheet(
  model: Model,
  settings: ModelSettingsStore,
  onApply: (Map<String, Any>?) -> Unit,
  onDismiss: () -> Unit,
) {
  val values = remember(model.name) { mutableStateMapOf<String, Any>().apply { putAll(settings.baseValues(model)) } }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(model.displayName.ifEmpty { model.name }, style = MaterialTheme.typography.headlineSmall)
      Text(
        "Saved for this model and kept across restarts. Folders can override temperature and " +
          "thinking. Changing the accelerator, context length or speculative decoding reloads the model.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (model.configs.isEmpty()) {
        Text("This model has no adjustable settings.", style = MaterialTheme.typography.bodyMedium)
      } else {
        ConfigEditorsPanel(configs = model.configs, values = values)
      }
      HorizontalDivider()
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onApply(values.toMap()); onDismiss() }) { Text("Save") }
        OutlinedButton(onClick = { onApply(null); onDismiss() }) { Text("Reset to defaults") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(whisper: WhisperManager, onDismiss: () -> Unit) {
  val size by whisper.size.collectAsState()
  val language by whisper.language.collectAsState()
  val status by whisper.status.collectAsState()
  val scope = rememberCoroutineScope()

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text("Voice input", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Tap the mic in a chat to dictate. Whisper turns speech into text on this phone, and you " +
          "can edit it before sending. Models that understand audio (Gemma 4, Gemma 3n) can also " +
          "get the recording directly. Recordings are discarded right after use.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      SectionTitle("Whisper model")
      WhisperSize.entries.forEach { s ->
        val downloaded = whisper.isDownloaded(s)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clickable { whisper.select(s) },
        ) {
          RadioButton(selected = size == s, onClick = { whisper.select(s) })
          Column(modifier = Modifier.weight(1f)) {
            Text("${s.label} · ~${s.approxMb} MB" + if (downloaded) " · downloaded" else "", style = MaterialTheme.typography.bodyLarge)
            Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (downloaded) {
            TextButton(onClick = { scope.launch { whisper.delete(s) } }) { Text("Delete") }
          }
        }
      }
      when (val st = status) {
        WhisperStatus.NotDownloaded -> Button(onClick = { whisper.download() }) { Text("Download ${size.label}") }
        is WhisperStatus.Downloading -> {
          LinearProgressIndicator(
            progress = { if (st.total > 0) st.received.toFloat() / st.total else 0f },
            modifier = Modifier.fillMaxWidth(),
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              "File ${st.fileIndex + 1} of 3 · ${st.received / 1_000_000} / ${st.total / 1_000_000} MB",
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { whisper.cancelDownload() }) { Text("Cancel") }
          }
        }
        is WhisperStatus.Ready -> Text("${st.size.label} is ready.", style = MaterialTheme.typography.bodyMedium)
        is WhisperStatus.Failed -> {
          Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Button(onClick = { whisper.download() }) { Text("Retry") }
        }
      }

      SectionTitle("Language")
      WhisperLanguage.entries.forEach { l ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clickable { whisper.setLanguage(l) },
        ) {
          RadioButton(selected = language == l, onClick = { whisper.setLanguage(l) })
          Text(l.label, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}
