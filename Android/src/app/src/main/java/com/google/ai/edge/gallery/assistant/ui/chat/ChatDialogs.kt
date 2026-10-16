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

package com.google.ai.edge.gallery.assistant.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.chat.ChatFolder

@Composable
fun TextInputDialog(
  title: String,
  initial: String,
  label: String,
  confirm: String = "Save",
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(80) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** Three-way switch: on, off, or inherit the default. */
@Composable
private fun TriStateRow(label: String, value: Boolean?, defaultLabel: String, onChange: (Boolean?) -> Unit) {
  Column {
    Text(label, style = MaterialTheme.typography.labelLarge)
    listOf<Pair<Boolean?, String>>(null to defaultLabel, true to "On", false to "Off").forEach { (v, text) ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(v) },
      ) {
        RadioButton(selected = value == v, onClick = { onChange(v) })
        Text(text, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun FolderEditorDialog(
  folder: ChatFolder,
  knowledgeConfigured: Boolean,
  onSave: (ChatFolder) -> Unit,
  onDelete: (() -> Unit)?,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(folder.name) }
  var prompt by remember { mutableStateOf(folder.systemPrompt) }
  var kb by remember { mutableStateOf(folder.useKnowledge) }
  var customTemp by remember { mutableStateOf(folder.temperature != null) }
  var temp by remember { mutableStateOf(folder.temperature ?: 0.7f) }
  var thinking by remember { mutableStateOf(folder.thinking) }
  var confirmDelete by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (onDelete == null) "New folder" else "Folder settings") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it.take(60) },
          label = { Text("Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it.take(4000) },
          label = { Text("System prompt for chats in this folder") },
          placeholder = { Text("Empty: use the persona's") },
          modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        )
        if (knowledgeConfigured) {
          TriStateRow("Search my notes", kb, "Use the global default") { kb = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { customTemp = !customTemp }) {
          Checkbox(checked = customTemp, onCheckedChange = { customTemp = it })
          Text(if (customTemp) "Temperature: ${"%.2f".format(temp)}" else "Temperature: model default", style = MaterialTheme.typography.bodyMedium)
        }
        if (customTemp) {
          Slider(value = temp, onValueChange = { temp = (it * 20).toInt() / 20f }, valueRange = 0f..2f)
          Text(
            "Lower is more focused and repeatable, higher is more creative.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TriStateRow("Thinking mode (models that support it)", thinking, "Use the model's setting") { thinking = it }
        if (onDelete != null) {
          TextButton(onClick = { confirmDelete = true }) {
            Text("Delete folder", color = MaterialTheme.colorScheme.error)
          }
        }
      }
    },
    confirmButton = {
      Button(
        enabled = name.isNotBlank(),
        onClick = {
          onSave(
            folder.copy(
              name = name.trim(),
              systemPrompt = prompt.trim(),
              useKnowledge = kb,
              temperature = if (customTemp) temp else null,
              thinking = thinking,
            )
          )
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
  if (confirmDelete && onDelete != null) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete \"${folder.name}\"?") },
      text = { Text("The folder is removed. Its chats are kept and move to the main list.") },
      confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
fun SystemPromptDialog(
  sourceLabel: String,
  effective: String,
  chatOverride: String,
  onSave: (String) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember { mutableStateOf(chatOverride.ifBlank { effective }) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("System prompt") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
          "Currently from: $sourceLabel. Saving here sets it for this chat only. Memories, notes " +
            "and web-search rules are added automatically.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it.take(6000) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        )
        if (chatOverride.isNotBlank()) {
          TextButton(onClick = onReset) { Text("Use folder/persona default") }
        }
      }
    },
    confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
fun MoveToFolderDialog(
  folders: List<ChatFolder>,
  currentFolderId: String?,
  onMove: (String?) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Move to folder") },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        val options = listOf<ChatFolder?>(null) + folders
        options.forEach { f ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onMove(f?.id) }.padding(vertical = 10.dp),
          ) {
            Icon(if (f == null) Icons.Outlined.FolderOff else Icons.Outlined.Folder, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
              (f?.name ?: "No folder") + if (f?.id == currentFolderId) "  ✓" else "",
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
        if (folders.isEmpty()) {
          Text(
            "No folders yet. Create one from the chat list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
