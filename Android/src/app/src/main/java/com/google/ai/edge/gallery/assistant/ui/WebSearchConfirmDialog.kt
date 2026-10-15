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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.assistant.tools.ConfirmWebSearchAction

/**
 * Shows exactly what would leave the phone. The user can edit the query, send it, or refuse.
 * Dismissing the dialog counts as refusing.
 */
@Composable
fun WebSearchConfirmDialog(action: ConfirmWebSearchAction, onFinished: () -> Unit) {
  var query by remember(action) { mutableStateOf(action.scrub.cleaned) }
  var showOriginal by remember(action) { mutableStateOf(false) }

  fun finish(value: String?) {
    if (!action.result.isCompleted) action.result.complete(value?.trim()?.ifBlank { null })
    onFinished()
  }

  AlertDialog(
    onDismissRequest = { finish(null) },
    properties = DialogProperties(dismissOnClickOutside = false),
    icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
    title = { Text("Send this web search?") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "Only this text goes to ${action.host}. Nothing else from the chat is sent.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = query,
          onValueChange = { query = it.replace('\n', ' ').take(160) },
          label = { Text("Search query") },
          singleLine = false,
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )

        if (action.touchesMemory) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
          ) {
            Text(
              "This query looks like it contains something from your memories. Check it before " +
                "sending.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }

        val health = action.scrub.healthFlags
        if (health.isNotEmpty()) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.medium,
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                Icons.Outlined.HealthAndSafety,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
              )
              Text(
                "Health topic: " + health.joinToString(", ") { it.text } +
                  ". The search engine will see this term, but not that it's about you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        }

        val removed = action.scrub.removed
        if (removed.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "Filtered out",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
            )
            removed.forEach { r ->
              Text(
                "• ${r.category.label}: ${r.text}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        if (action.modelQuery.trim() != action.scrub.cleaned) {
          TextButton(onClick = { showOriginal = !showOriginal }) {
            Text(if (showOriginal) "Hide what the model asked for" else "Show what the model asked for")
          }
          if (showOriginal) {
            Text(
              action.modelQuery,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { finish(query) }, enabled = query.isNotBlank()) { Text("Send") }
    },
    dismissButton = { TextButton(onClick = { finish(null) }) { Text("Don't send") } },
  )
}
