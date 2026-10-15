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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.data.DefaultPersonas
import com.google.ai.edge.gallery.assistant.data.MemoryItem
import com.google.ai.edge.gallery.assistant.data.Persona

@Composable
internal fun SectionTitle(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (subtitle.isNotEmpty()) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

// ---- Persona --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSheet(
  repository: AssistantRepository,
  onDismiss: () -> Unit,
  onPersonaApplied: () -> Unit,
) {
  val state by repository.state.collectAsState()
  var editing by remember { mutableStateOf<Persona?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
      item {
        Text("Persona", style = MaterialTheme.typography.headlineSmall)
        Text(
          "Who the assistant is and how it talks. Changes apply right away to this chat.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
      }
      items(state.personas, key = { it.id }) { persona ->
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clickable {
                if (persona.id != state.activePersonaId) {
                  repository.setActivePersona(persona.id)
                  onPersonaApplied()
                }
              }
              .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = persona.id == state.activePersonaId,
            onClick = {
              repository.setActivePersona(persona.id)
              onPersonaApplied()
            },
          )
          Text(persona.emoji, style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(persona.name, style = MaterialTheme.typography.bodyLarge)
            Text(
              persona.instructions.lineSequence().firstOrNull().orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(onClick = { editing = persona }) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${persona.name}")
          }
        }
      }
      item {
        OutlinedButton(
          onClick = {
            editing =
              Persona(
                id = repository.newPersonaId(),
                name = "New persona",
                emoji = "✨",
                userName = state.activePersona.userName,
                instructions = "You are a helpful assistant. ",
              )
          },
          modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("New persona")
        }
      }
    }
  }

  editing?.let { p ->
    PersonaEditorDialog(
      persona = p,
      isBuiltIn = DefaultPersonas.all.any { it.id == p.id },
      canDelete = state.personas.size > 1,
      onSave = { updated ->
        repository.upsertPersona(updated)
        editing = null
        if (updated.id == state.activePersonaId) onPersonaApplied()
      },
      onDelete = {
        repository.deletePersona(p.id)
        editing = null
        onPersonaApplied()
      },
      onReset = {
        repository.resetPersona(p.id)
        editing = null
        if (p.id == state.activePersonaId) onPersonaApplied()
      },
      onDismiss = { editing = null },
    )
  }
}

@Composable
private fun PersonaEditorDialog(
  persona: Persona,
  isBuiltIn: Boolean,
  canDelete: Boolean,
  onSave: (Persona) -> Unit,
  onDelete: () -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(persona.name) }
  var emoji by remember { mutableStateOf(persona.emoji) }
  var userName by remember { mutableStateOf(persona.userName) }
  var instructions by remember { mutableStateOf(persona.instructions) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit persona") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = emoji,
            onValueChange = { emoji = it.take(4) },
            label = { Text("Icon") },
            singleLine = true,
            modifier = Modifier.width(80.dp),
          )
          OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        OutlinedTextField(
          value = userName,
          onValueChange = { userName = it.take(40) },
          label = { Text("What should it call you?") },
          supportingText = { Text("Stays on the phone and is filtered out of web searches.") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = instructions,
          onValueChange = { instructions = it.take(4000) },
          label = { Text("Instructions") },
          modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (isBuiltIn) TextButton(onClick = onReset) { Text("Reset to default") }
          if (canDelete) TextButton(onClick = onDelete) { Text("Delete") }
        }
      }
    },
    confirmButton = {
      Button(
        enabled = name.isNotBlank() && instructions.isNotBlank(),
        onClick = {
          onSave(
            persona.copy(
              name = name.trim(),
              emoji = emoji.trim().ifEmpty { "🐷" },
              userName = userName.trim(),
              instructions = instructions.trim(),
            )
          )
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

// ---- Memory ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySheet(
  repository: AssistantRepository,
  onDismiss: () -> Unit,
  onReviewChat: () -> Unit,
  onMemoryChanged: () -> Unit,
) {
  val state by repository.state.collectAsState()
  var newMemory by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf<MemoryItem?>(null) }
  var confirmDeleteAll by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
      item {
        Text("Memory", style = MaterialTheme.typography.headlineSmall)
        Text(
          "Facts you approved. They are encrypted on this phone, added to every new chat, and " +
            "never sent anywhere — including web searches.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
        SwitchRow(
          title = "Use memory",
          subtitle = "Let the assistant read these facts and suggest new ones",
          checked = state.memoryEnabled,
          onChange = {
            repository.setMemoryEnabled(it)
            onMemoryChanged()
          },
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(vertical = 8.dp),
        ) {
          OutlinedTextField(
            value = newMemory,
            onValueChange = { newMemory = it.take(300) },
            placeholder = { Text("Add a fact, e.g. \"Takes vitamin D in the morning\"") },
            modifier = Modifier.weight(1f),
          )
          IconButton(
            enabled = newMemory.isNotBlank(),
            onClick = {
              repository.addMemory(newMemory)
              newMemory = ""
              onMemoryChanged()
            },
          ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add memory")
          }
        }
        FilledTonalButton(
          onClick = onReviewChat,
          enabled = state.memoryEnabled,
          modifier = Modifier.padding(bottom = 8.dp),
        ) {
          Text("Suggest memories from this chat")
        }
        HorizontalDivider()
      }

      if (state.memories.isEmpty()) {
        item {
          Text(
            "Nothing saved yet. When you share something lasting, the assistant will propose it " +
              "and you decide.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
          )
        }
      }

      val sorted =
        state.memories.sortedWith(
          compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.createdAtMs }
        )
      items(sorted, key = { it.id }) { m ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          IconButton(
            onClick = {
              repository.togglePin(m.id)
              onMemoryChanged()
            }
          ) {
            Icon(
              if (m.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
              contentDescription = if (m.pinned) "Unpin" else "Pin",
            )
          }
          IconButton(onClick = { editing = m }) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
          }
          IconButton(
            onClick = {
              repository.deleteMemory(m.id)
              onMemoryChanged()
            }
          ) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
          }
        }
      }

      if (state.memories.isNotEmpty()) {
        item {
          TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Delete all memories")
          }
        }
      }
    }
  }

  editing?.let { m ->
    var text by remember(m.id) { mutableStateOf(m.text) }
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text("Edit memory") },
      text = {
        OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, modifier = Modifier.fillMaxWidth())
      },
      confirmButton = {
        Button(
          enabled = text.isNotBlank(),
          onClick = {
            repository.updateMemory(m.id, text)
            editing = null
            onMemoryChanged()
          },
        ) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
    )
  }

  if (confirmDeleteAll) {
    AlertDialog(
      onDismissRequest = { confirmDeleteAll = false },
      title = { Text("Delete all memories?") },
      text = { Text("This can't be undone.") },
      confirmButton = {
        Button(
          onClick = {
            repository.deleteAllMemories()
            confirmDeleteAll = false
            onMemoryChanged()
          }
        ) {
          Text("Delete all")
        }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
    )
  }
}
