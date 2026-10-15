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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.assistant.chat.ChatFolder
import com.google.ai.edge.gallery.assistant.chat.ChatMeta

sealed interface DrawerAction {
  data object NewChat : DrawerAction

  data object NewPrivateChat : DrawerAction

  data class NewChatInFolder(val folderId: String) : DrawerAction

  data class Open(val chatId: String) : DrawerAction

  data class Rename(val chat: ChatMeta) : DrawerAction

  data class Move(val chat: ChatMeta) : DrawerAction

  data class Delete(val chat: ChatMeta) : DrawerAction

  data object NewFolder : DrawerAction

  data class EditFolder(val folder: ChatFolder) : DrawerAction

  data object Models : DrawerAction

  data object Settings : DrawerAction
}

@Composable
fun ChatDrawer(
  folders: List<ChatFolder>,
  chats: List<ChatMeta>,
  activeChatId: String?,
  onAction: (DrawerAction) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val expanded = remember { mutableStateMapOf<String, Boolean>() }
  val visibleChats =
    chats.filter { !it.isPrivate && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }

  ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
    Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp)) {
      Text(
        "Piggselle",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onAction(DrawerAction.NewChat) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("New chat")
        }
        OutlinedButton(onClick = { onAction(DrawerAction.NewPrivateChat) }) {
          Icon(Icons.Outlined.VisibilityOff, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Private")
        }
      }
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search chats") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
      )
    }

    LazyColumn(
      modifier = Modifier.weight(1f),
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
      // Folders.
      item {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
        ) {
          Text("Folders", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
          IconButton(onClick = { onAction(DrawerAction.NewFolder) }) {
            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
          }
        }
      }
      folders.forEach { folder ->
        val inFolder = visibleChats.filter { it.folderId == folder.id }
        val isOpen = expanded[folder.id] ?: (query.isNotBlank() || inFolder.any { it.id == activeChatId })
        item(key = "folder_${folder.id}") {
          FolderRow(
            folder = folder,
            count = inFolder.size,
            open = isOpen,
            onToggle = { expanded[folder.id] = !isOpen },
            onNewChat = { onAction(DrawerAction.NewChatInFolder(folder.id)) },
            onEdit = { onAction(DrawerAction.EditFolder(folder)) },
          )
        }
        if (isOpen) {
          items(inFolder, key = { "fc_${it.id}" }) { chat ->
            ChatRow(chat, chat.id == activeChatId, indent = true, onAction = onAction)
          }
        }
      }
      if (folders.isEmpty()) {
        item {
          Text(
            "Group chats by topic, each with its own system prompt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
          )
        }
      }

      // Loose chats.
      val loose = visibleChats.filter { c -> c.folderId == null || folders.none { it.id == c.folderId } }
      item {
        Text(
          "Chats",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        )
      }
      if (loose.isEmpty()) {
        item {
          Text(
            if (query.isBlank()) "No chats yet." else "No matches.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
      items(loose, key = { "c_${it.id}" }) { chat -> ChatRow(chat, chat.id == activeChatId, indent = false, onAction = onAction) }
    }

    HorizontalDivider()
    Column(modifier = Modifier.navigationBarsPadding().padding(12.dp)) {
      NavigationDrawerItem(
        label = { Text("Models") },
        icon = { Icon(Icons.Outlined.Memory, null) },
        selected = false,
        onClick = { onAction(DrawerAction.Models) },
      )
      NavigationDrawerItem(
        label = { Text("Settings") },
        icon = { Icon(Icons.Outlined.Settings, null) },
        selected = false,
        onClick = { onAction(DrawerAction.Settings) },
      )
    }
  }
}

@Composable
private fun FolderRow(
  folder: ChatFolder,
  count: Int,
  open: Boolean,
  onToggle: () -> Unit,
  onNewChat: () -> Unit,
  onEdit: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  NavigationDrawerItem(
    label = { Text("${folder.name}  ·  $count", maxLines = 1, overflow = TextOverflow.Ellipsis) },
    icon = { Icon(if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null) },
    selected = false,
    onClick = onToggle,
    badge = {
      Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Outlined.MoreVert, contentDescription = "Folder options")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          DropdownMenuItem(text = { Text("New chat here") }, onClick = { menu = false; onNewChat() })
          DropdownMenuItem(text = { Text("Folder settings") }, onClick = { menu = false; onEdit() })
        }
      }
    },
    colors = NavigationDrawerItemDefaults.colors(),
  )
}

@Composable
private fun ChatRow(chat: ChatMeta, active: Boolean, indent: Boolean, onAction: (DrawerAction) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Box(modifier = Modifier.padding(start = if (indent) 20.dp else 0.dp)) {
    NavigationDrawerItem(
      label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(18.dp)) },
      selected = active,
      onClick = { onAction(DrawerAction.Open(chat.id)) },
      badge = {
        Box {
          IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Chat options")
          }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onAction(DrawerAction.Rename(chat)) })
            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; onAction(DrawerAction.Move(chat)) })
            DropdownMenuItem(
              text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
              onClick = { menu = false; onAction(DrawerAction.Delete(chat)) },
            )
          }
        }
      },
    )
  }
}
