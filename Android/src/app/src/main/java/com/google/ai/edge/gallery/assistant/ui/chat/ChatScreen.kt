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
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.assistant.chat.ChatFolder
import com.google.ai.edge.gallery.assistant.chat.ChatMeta
import com.google.ai.edge.gallery.assistant.chat.EngineIssue
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.tools.ConfirmWebSearchAction
import com.google.ai.edge.gallery.assistant.ui.WebSearchConfirmDialog
import com.google.ai.edge.gallery.assistant.voice.VoiceRecorder
import com.google.ai.edge.gallery.assistant.voice.WhisperStatus
import com.google.ai.edge.gallery.customtasks.agentchat.isWebSearchAvailable
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.tools.SkillProgressToolAction
import com.google.ai.edge.gallery.ui.common.tos.GemmaTermsOfUseDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RECOMMENDED_MODEL = "Gemma-4-E4B-it"

private data class QuickPrompt(val label: String, val prompt: String)

private val QUICK_PROMPTS =
  listOf(
    QuickPrompt("Plan my day", "Help me plan today. Ask me what's on, then give me a realistic schedule and a top 3."),
    QuickPrompt("Brain dump", "I'll dump what's on my mind. Sort it into schedule, top 3, open loops and health notes."),
    QuickPrompt("Health log", "Let's log today: sleep, meals, water, movement, mood. Ask one thing at a time."),
    QuickPrompt("Evening review", "Short evening review: what got done, what moves to tomorrow, one good thing."),
  )

private sealed interface ChatDialog {
  data class Rename(val chat: ChatMeta) : ChatDialog

  data class Move(val chat: ChatMeta) : ChatDialog

  data class Delete(val chat: ChatMeta) : ChatDialog

  data class Folder(val folder: ChatFolder, val isNew: Boolean) : ChatDialog

  data object SystemPrompt : ChatDialog

  data class GemmaTerms(val model: Model) : ChatDialog

  data class ModelSettings(val model: Model) : ChatDialog

  data object NeedWhisper : ChatDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenModels: () -> Unit,
  onOpenSettings: () -> Unit,
  vm: ChatViewModel = hiltViewModel(),
  tosViewModel: TosViewModel = hiltViewModel(),
) {
  val engine = vm.engine
  val store = vm.store
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) { engine.attach(modelManagerViewModel) }

  val mmState by modelManagerViewModel.uiState.collectAsState()
  val chats by store.chats.collectAsState()
  val folders by store.folders.collectAsState()
  val activeChatId by engine.activeChatId.collectAsState()
  val draft by engine.draft.collectAsState()
  val messages by engine.messages.collectAsState()
  val generating by engine.generating.collectAsState()
  val loadingModel by engine.loadingModel.collectAsState()
  val issue by engine.issue.collectAsState()
  val suggestions by vm.repository.suggestions.collectAsState()
  val kbState by vm.knowledge.state.collectAsState()
  val policy by NetworkGate.policy.collectAsState()

  val chatModels = remember(mmState.modelDownloadStatus, mmState.tasks) { engine.downloadedChatModels(modelManagerViewModel) }
  val currentModel = remember(chatModels, mmState.selectedModel) { engine.currentModel(modelManagerViewModel) }
  val activeChat = chats.firstOrNull { it.id == activeChatId }
  val isPrivate = activeChat?.isPrivate ?: draft.isPrivate
  val folderName = store.folder(activeChat?.folderId ?: draft.folderId)?.name

  // Load the model as soon as one is available.
  LaunchedEffect(currentModel?.name) { if (currentModel != null) engine.preload() }

  val drawerState = rememberDrawerState(DrawerValue.Closed)
  var dialog by remember { mutableStateOf<ChatDialog?>(null) }
  var pendingSearch by remember { mutableStateOf<ConfirmWebSearchAction?>(null) }
  var input by remember { mutableStateOf("") }
  val images = remember { mutableStateListOf<Bitmap>() }
  var menuOpen by remember { mutableStateOf(false) }

  // ---- Voice ----
  val recorder = remember { VoiceRecorder(scope) }
  var recording by remember { mutableStateOf(false) }
  var transcribing by remember { mutableStateOf(false) }
  val recSeconds by recorder.seconds.collectAsState()
  val recLevel by recorder.level.collectAsState()
  val whisperStatus by vm.whisper.status.collectAsState()
  val whisperReady = whisperStatus is WhisperStatus.Ready
  val canHear = currentModel?.supportAudio == true

  fun startRecording() {
    if (!whisperReady && !canHear) {
      dialog = ChatDialog.NeedWhisper
      return
    }
    recording = recorder.start()
  }

  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startRecording()
    }

  fun onMic() {
    val granted =
      androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (granted) startRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }

  // Tool side effects: web search confirmation and progress.
  LaunchedEffect(vm.tools.receiveActionChannel) {
    for (action in vm.tools.receiveActionChannel) {
      when (action) {
        is ConfirmWebSearchAction -> pendingSearch = action
        is SkillProgressToolAction -> engine.onToolStatus(action.label, action.inProgress)
        else -> {}
      }
    }
  }

  val imagePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris: List<Uri> ->
      scope.launch {
        val decoded = withContext(Dispatchers.IO) { uris.mapNotNull { decodeScaled(context, it) } }
        images.addAll(decoded.take(4 - images.size))
      }
    }

  BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

  fun closeDrawer() = scope.launch { drawerState.close() }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ChatDrawer(
        folders = folders,
        chats = chats,
        activeChatId = activeChatId,
        onAction = { action ->
          when (action) {
            DrawerAction.NewChat -> { engine.newChat(); closeDrawer() }
            DrawerAction.NewPrivateChat -> { engine.newChat(isPrivate = true); closeDrawer() }
            is DrawerAction.NewChatInFolder -> { engine.newChat(folderId = action.folderId); closeDrawer() }
            is DrawerAction.Open -> { engine.openChat(action.chatId); closeDrawer() }
            is DrawerAction.Rename -> dialog = ChatDialog.Rename(action.chat)
            is DrawerAction.Move -> dialog = ChatDialog.Move(action.chat)
            is DrawerAction.Delete -> dialog = ChatDialog.Delete(action.chat)
            DrawerAction.NewFolder -> dialog = ChatDialog.Folder(ChatFolder(id = "", name = ""), isNew = true)
            is DrawerAction.EditFolder -> dialog = ChatDialog.Folder(action.folder, isNew = false)
            DrawerAction.Models -> { closeDrawer(); onOpenModels() }
            DrawerAction.Settings -> { closeDrawer(); onOpenSettings() }
          }
        },
      )
    },
  ) {
    Scaffold(
      topBar = {
        TopAppBar(
          navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
              Icon(Icons.Rounded.Menu, contentDescription = "Chats")
            }
          },
          title = {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPrivate) {
                  Icon(Icons.Outlined.VisibilityOff, null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                }
                Text(
                  activeChat?.title ?: if (isPrivate) "Private chat" else "New chat",
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.titleMedium,
                )
              }
              val subtitle =
                when {
                  isPrivate -> "Not saved · no memory"
                  folderName != null -> folderName
                  else -> null
                }
              subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
              }
            }
          },
          actions = {
            ModelPickerButton(
              current = currentModel,
              models = chatModels,
              loading = loadingModel,
              onSelect = { engine.selectModel(it) },
              onManage = onOpenModels,
              onSettings = { currentModel?.let { dialog = ChatDialog.ModelSettings(it) } },
            )
            Box {
              IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Chat options") }
              DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("System prompt…") }, onClick = { menuOpen = false; dialog = ChatDialog.SystemPrompt })
                if (kbState.configured) {
                  val kbOn = engine.knowledgeOn(activeChat)
                  DropdownMenuItem(
                    text = { Text("Search my notes") },
                    trailingIcon = { Switch(checked = kbOn, onCheckedChange = null) },
                    onClick = { engine.setKnowledge(!kbOn) },
                  )
                }
                if (activeChat != null && !activeChat.isPrivate) {
                  HorizontalDivider()
                  DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; dialog = ChatDialog.Rename(activeChat) })
                  DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menuOpen = false; dialog = ChatDialog.Move(activeChat) })
                  DropdownMenuItem(
                    text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; dialog = ChatDialog.Delete(activeChat) },
                  )
                }
              }
            }
          },
          colors =
            if (isPrivate)
              TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            else TopAppBarDefaults.topAppBarColors(),
        )
      },
      bottomBar = {
        Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
          if (!isPrivate && suggestions.isNotEmpty()) {
            MemorySuggestionCards(
              suggestions = suggestions,
              onSave = { s, edited -> vm.repository.acceptSuggestion(s.id, edited); engine.settingsChanged() },
              onDismiss = { vm.repository.dismissSuggestion(it.id) },
            )
            Spacer(Modifier.height(6.dp))
          }
          if (recording || transcribing) {
            RecordingBar(
              seconds = recSeconds,
              level = recLevel,
              busy = transcribing,
              canTranscribe = whisperReady,
              canSendAudio = canHear,
              onCancel = {
                scope.launch {
                  recorder.cancel()
                  recording = false
                }
              },
              onTranscribe = {
                scope.launch {
                  val rec = recorder.stop()
                  recording = false
                  if (rec != null) {
                    transcribing = true
                    val text = vm.whisper.transcribe(rec.floatSamples())
                    transcribing = false
                    if (!text.isNullOrBlank()) input = if (input.isBlank()) text else "$input $text"
                  }
                }
              },
              onSendAudio = {
                scope.launch {
                  val rec = recorder.stop()
                  recording = false
                  if (rec != null) engine.send(input, images.toList(), audioWav = rec.wav())
                  input = ""
                  images.clear()
                }
              },
            )
          } else
          ChatInputBar(
            text = input,
            onTextChange = { input = it },
            images = images,
            onRemoveImage = { images.removeAt(it) },
            onPickImage =
              if (currentModel?.supportImage == true) {
                { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
              } else null,
            generating = generating,
            enabled = currentModel != null,
            placeholder =
              when {
                currentModel == null -> "Download a model first"
                isPrivate -> "Private message"
                else -> "Message"
              },
            onSend = {
              engine.send(input, images.toList())
              input = ""
              images.clear()
            },
            onStop = { engine.stop() },
            onMic = { onMic() },
          )
        }
      },
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (messages.isEmpty()) {
          EmptyChat(
            hasModel = currentModel != null,
            loadingModel = loadingModel,
            isPrivate = isPrivate,
            notesOn = engine.knowledgeOn(activeChat),
            webOn = !policy.killSwitch && isWebSearchAvailable(),
            issue = issue,
            noModelContent = {
              val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT)
              val recommended = task?.models?.firstOrNull { it.name == RECOMMENDED_MODEL } ?: task?.models?.firstOrNull()
              val status = recommended?.let { mmState.modelDownloadStatus[it.name] }
              val progress =
                if (status?.status == ModelDownloadStatusType.IN_PROGRESS && status.totalBytes > 0)
                  status.receivedBytes.toFloat() / status.totalBytes
                else if (status?.status == ModelDownloadStatusType.IN_PROGRESS) 0f
                else null
              NoModelCard(
                recommended = recommended,
                downloadProgress = progress,
                downloadError =
                  status?.takeIf { it.status == ModelDownloadStatusType.FAILED }?.errorMessage?.takeIf { it.isNotBlank() },
                onDownload = { model ->
                  if (model.name.contains("gemma", ignoreCase = true) && !tosViewModel.getIsGemmaTermsOfUseAccepted()) {
                    dialog = ChatDialog.GemmaTerms(model)
                  } else {
                    modelManagerViewModel.downloadModel(task, model)
                  }
                },
                onBrowse = onOpenModels,
              )
            },
            onQuickPrompt = { engine.send(it) },
          )
        } else {
          val listState = rememberLazyListState()
          val lastText = messages.lastOrNull()?.text?.length ?: 0
          LaunchedEffect(messages.size, lastText / 80) {
            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
          }
          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
              MessageRow(
                message = m,
                isLast = i == messages.lastIndex,
                canRegenerate = !generating,
                onRegenerate = { engine.regenerate() },
              )
            }
          }
        }
      }
    }
  }

  // ---- Dialogs --------------------------------------------------------------------------------

  pendingSearch?.let { WebSearchConfirmDialog(action = it, onFinished = { pendingSearch = null }) }

  when (val d = dialog) {
    is ChatDialog.Rename ->
      TextInputDialog(
        title = "Rename chat",
        initial = d.chat.title,
        label = "Title",
        onConfirm = { store.renameChat(d.chat.id, it); dialog = null },
        onDismiss = { dialog = null },
      )
    is ChatDialog.Move ->
      MoveToFolderDialog(
        folders = folders,
        currentFolderId = d.chat.folderId,
        onMove = { store.moveChat(d.chat.id, it); engine.settingsChanged(); dialog = null },
        onDismiss = { dialog = null },
      )
    is ChatDialog.Delete ->
      AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text("Delete chat?") },
        text = { Text("\"${d.chat.title}\" will be deleted from this phone.") },
        confirmButton = { Button(onClick = { engine.deleteChat(d.chat.id); dialog = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
      )
    is ChatDialog.Folder ->
      FolderEditorDialog(
        folder = d.folder,
        knowledgeConfigured = kbState.configured,
        onSave = { f ->
          if (d.isNew) {
            val created = store.createFolder(f.name)
            store.updateFolder(
              created.copy(
                systemPrompt = f.systemPrompt,
                useKnowledge = f.useKnowledge,
                temperature = f.temperature,
                thinking = f.thinking,
              )
            )
          } else {
            store.updateFolder(f)
          }
          engine.settingsChanged()
          dialog = null
        },
        onDelete = if (d.isNew) null else ({ store.deleteFolder(d.folder.id); engine.settingsChanged(); dialog = null }),
        onDismiss = { dialog = null },
      )
    ChatDialog.SystemPrompt -> {
      val (source, effective) = engine.systemPromptSource(activeChat)
      SystemPromptDialog(
        sourceLabel = source,
        effective = effective,
        chatOverride = activeChat?.systemPrompt ?: draft.systemPrompt,
        onSave = { engine.setChatSystemPrompt(it); dialog = null },
        onReset = { engine.setChatSystemPrompt(""); dialog = null },
        onDismiss = { dialog = null },
      )
    }
    is ChatDialog.GemmaTerms ->
      GemmaTermsOfUseDialog(
        onTosAccepted = {
          tosViewModel.acceptGemmaTermsOfUse()
          modelManagerViewModel.downloadModel(modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT), d.model)
          dialog = null
        },
        onCancel = { dialog = null },
      )
    is ChatDialog.ModelSettings ->
      com.google.ai.edge.gallery.assistant.ui.settings.ModelSettingsSheet(
        model = d.model,
        settings = vm.modelSettings,
        onApply = { values -> engine.applyModelSettings(d.model, values) },
        onDismiss = { dialog = null },
      )
    ChatDialog.NeedWhisper ->
      AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text("Voice input needs Whisper") },
        text = {
          Text(
            "This model can't listen to audio, so speech has to be turned into text first. Download a " +
              "Whisper model (runs on this phone) in Settings → Voice input."
          )
        },
        confirmButton = { Button(onClick = { dialog = null; onOpenSettings() }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
      )
    null -> {}
  }
}

@Composable
private fun EmptyChat(
  hasModel: Boolean,
  loadingModel: Boolean,
  isPrivate: Boolean,
  notesOn: Boolean,
  webOn: Boolean,
  issue: EngineIssue?,
  noModelContent: @Composable () -> Unit,
  onQuickPrompt: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (!hasModel) {
      noModelContent()
      return@Column
    }
    Text(if (isPrivate) "🕶️" else "🐷", style = MaterialTheme.typography.displayMedium)
    Text(
      if (isPrivate) "Private chat" else "How can I help?",
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(top = 8.dp),
    )
    val details =
      buildList {
        add(if (isPrivate) "Not saved, no memory" else "Stays on this phone")
        if (notesOn) add("searches your notes")
        if (webOn) add("can search the web (you approve)")
      }
    Text(
      details.joinToString(" · "),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 6.dp),
    )
    if (loadingModel) {
      Text("Loading model…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
    }
    (issue as? EngineIssue.Failed)?.let {
      Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    Row(
      modifier = Modifier.padding(top = 24.dp).horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      QUICK_PROMPTS.forEach { qp -> SuggestionChip(onClick = { onQuickPrompt(qp.prompt) }, label = { Text(qp.label) }) }
    }
  }
}

private fun decodeScaled(context: android.content.Context, uri: Uri, maxSide: Int = 1024): Bitmap? =
  try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val w = info.size.width
      val h = info.size.height
      val scale = maxSide.toFloat() / maxOf(w, h)
      if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
  } catch (e: Exception) {
    null
  }
