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

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.chat.Role
import com.google.ai.edge.gallery.assistant.chat.UiMessage
import com.google.ai.edge.gallery.assistant.data.MemorySuggestion
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.MarkdownText

// ---- Messages -------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageRow(
  message: UiMessage,
  isLast: Boolean,
  canRegenerate: Boolean,
  onRegenerate: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  if (message.role == Role.USER) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
      horizontalAlignment = Alignment.End,
    ) {
      if (message.images.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
          message.images.take(4).forEach { bmp -> Thumbnail(bmp, 96) }
        }
      } else if (message.imageCount > 0) {
        Text(
          "${message.imageCount} image(s) attached",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      if (message.text.isNotBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
        ) {
          Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          )
        }
      }
    }
    return
  }

  Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
    if (message.thinking.isNotBlank()) {
      var open by remember(message.id) { mutableStateOf(false) }
      Text(
        if (open) "Hide thoughts" else "Show thoughts",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
      )
      AnimatedVisibility(open) {
        Text(
          message.thinking,
          style = MaterialTheme.typography.bodySmall,
          fontStyle = FontStyle.Italic,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
    }
    if (message.status.isNotBlank()) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(message.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (message.text.isBlank() && message.streaming && message.status.isBlank()) {
      TypingDots()
    } else if (message.text.isNotBlank()) {
      MarkdownText(
        text = message.text,
        textColor =
          if (message.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
    }
    if (message.sources.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
      ) {
        message.sources.forEach { src ->
          AssistChip(
            onClick = {},
            label = { Text(src.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Description, null, modifier = Modifier.size(16.dp)) },
          )
        }
      }
    }
    if (!message.streaming && message.text.isNotBlank()) {
      Row {
        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
        }
        if (isLast && canRegenerate) {
          IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Regenerate", modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun TypingDots() {
  Row(modifier = Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    repeat(3) {
      Box(
        Modifier.size(7.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
      )
    }
  }
}

@Composable
private fun Thumbnail(bitmap: Bitmap, sizeDp: Int) {
  Image(
    bitmap = bitmap.asImageBitmap(),
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(12.dp)),
  )
}

// ---- Input ----------------------------------------------------------------------------------------

@Composable
fun ChatInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  images: List<Bitmap>,
  onRemoveImage: (Int) -> Unit,
  onPickImage: (() -> Unit)?,
  generating: Boolean,
  enabled: Boolean,
  placeholder: String,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
      if (images.isNotEmpty()) {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          images.forEachIndexed { i, bmp ->
            Box {
              Thumbnail(bmp, 56)
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clickable { onRemoveImage(i) },
              ) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove image", modifier = Modifier.padding(2.dp))
              }
            }
          }
        }
      }
      Row(verticalAlignment = Alignment.Bottom) {
        if (onPickImage != null) {
          IconButton(onClick = onPickImage, enabled = enabled && !generating) {
            Icon(Icons.Outlined.Image, contentDescription = "Attach image")
          }
        }
        TextField(
          value = text,
          onValueChange = onTextChange,
          placeholder = { Text(placeholder) },
          enabled = enabled,
          maxLines = 6,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          colors =
            TextFieldDefaults.colors(
              focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
              unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
              disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
              focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
              unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
              disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        if (generating) {
          FilledIconButton(onClick = onStop, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.Rounded.Stop, contentDescription = "Stop")
          }
        } else {
          FilledIconButton(
            onClick = onSend,
            enabled = enabled && (text.isNotBlank() || images.isNotEmpty()),
            modifier = Modifier.padding(4.dp),
          ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
          }
        }
      }
    }
  }
}

// ---- Model picker ---------------------------------------------------------------------------------

@Composable
fun ModelPickerButton(
  current: Model?,
  models: List<Model>,
  loading: Boolean,
  onSelect: (Model) -> Unit,
  onManage: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { open = true }) {
      if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
      }
      Text(
        current?.let { it.displayName.ifEmpty { it.name } } ?: "No model",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 140.dp),
        style = MaterialTheme.typography.labelLarge,
      )
      Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      models.forEach { m ->
        DropdownMenuItem(
          text = {
            Text(
              m.displayName.ifEmpty { m.name } + if (m.name == current?.name) "  ✓" else "",
              maxLines = 1,
            )
          },
          onClick = {
            open = false
            if (m.name != current?.name) onSelect(m)
          },
        )
      }
      if (models.isNotEmpty()) HorizontalDivider()
      DropdownMenuItem(text = { Text("Download & manage models…") }, onClick = { open = false; onManage() })
    }
  }
}

// ---- Empty states ---------------------------------------------------------------------------------

@Composable
fun NoModelCard(
  recommended: Model?,
  downloadProgress: Float?,
  downloadError: String?,
  onDownload: (Model) -> Unit,
  onBrowse: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Download a model to start", style = MaterialTheme.typography.titleMedium)
      Text(
        "Models run entirely on this phone. Gemma 4 E4B (≈3.7 GB) is a good fit for the Pixel 9 Pro. " +
          "You can also pick any LiteRT model from Hugging Face.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (downloadProgress != null) {
        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
        Text(
          "Downloading… ${(downloadProgress * 100).toInt()}%  (you can keep using the app)",
          style = MaterialTheme.typography.labelMedium,
        )
      } else {
        downloadError?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // Stacked, so both buttons fit on narrow screens.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          if (recommended != null) {
            Button(onClick = { onDownload(recommended) }, modifier = Modifier.fillMaxWidth()) {
              Text(
                "Get ${recommended.displayName.ifEmpty { recommended.name }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse models") }
        }
      }
    }
  }
}

// ---- Memory suggestions ---------------------------------------------------------------------------

@Composable
fun MemorySuggestionCards(
  suggestions: List<MemorySuggestion>,
  onSave: (MemorySuggestion, String?) -> Unit,
  onDismiss: (MemorySuggestion) -> Unit,
) {
  var editing by remember { mutableStateOf<MemorySuggestion?>(null) }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
    suggestions.take(2).forEach { s ->
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
          Text(
            "Remember: ${s.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
          )
          TextButton(onClick = { editing = s }) { Text("Edit") }
          TextButton(onClick = { onSave(s, null) }) { Text("Save") }
          IconButton(onClick = { onDismiss(s) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Don't remember", modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
  editing?.let { s ->
    var text by remember(s.id) { mutableStateOf(s.text) }
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text("Edit before saving") },
      text = { OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, modifier = Modifier.fillMaxWidth()) },
      confirmButton = {
        Button(enabled = text.isNotBlank(), onClick = { onSave(s, text); editing = null }) { Text("Save") }
      },
      dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
    )
  }
}
