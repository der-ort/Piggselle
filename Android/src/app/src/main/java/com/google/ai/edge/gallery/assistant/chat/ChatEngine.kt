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

package com.google.ai.edge.gallery.assistant.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.assistant.AssistantPromptBuilder
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.knowledge.KnowledgeBase
import com.google.ai.edge.gallery.assistant.knowledge.KnowledgePrompt
import com.google.ai.edge.gallery.assistant.tools.AssistantTools
import com.google.ai.edge.gallery.customtasks.agentchat.isWebSearchAvailable
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "PGChatEngine"
/** History handed to the model when a chat is (re)opened: roughly 4k tokens. */
private const val HISTORY_CHAR_BUDGET = 14_000
/** Rebuild the session (with trimmed history) once a live session has grown this much. */
private const val REBIND_CHAR_THRESHOLD = 20_000
private const val PREFS = "piggselle_chat_engine"

/** A message as shown in the chat. */
data class UiMessage(
  val id: String,
  val role: Role,
  val text: String,
  val streaming: Boolean = false,
  val thinking: String = "",
  /** Live tool status, e.g. "Searching search.example.org…". */
  val status: String = "",
  val sources: List<String> = emptyList(),
  val images: List<Bitmap> = emptyList(),
  val imageCount: Int = 0,
  val error: Boolean = false,
)

private fun StoredMessage.toUi() =
  UiMessage(id = id, role = role, text = text, sources = sources, imageCount = imageCount, error = error)

/** What the chat should show instead of (or above) the conversation. */
sealed interface EngineIssue {
  data object NoModel : EngineIssue

  data class Failed(val message: String) : EngineIssue
}

/**
 * Drives the chat: picks the model, keeps one LiteRT-LM conversation in sync with the open chat
 * (system prompt + trimmed history), adds note excerpts, streams answers and saves them.
 *
 * It is a singleton so a generation keeps running while the user browses settings.
 */
@Singleton
class ChatEngine
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val store: ChatStore,
  private val repository: AssistantRepository,
  private val tools: AssistantTools,
  private val knowledge: KnowledgeBase,
  private val llmSessionManager: LlmSessionManager,
  @AgentChatExecutor private val executor: AgentRuntimeExecutor,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val bindLock = Mutex()
  private var mmvmRef = WeakReference<ModelManagerViewModel>(null)
  private var generation: Job? = null
  private var boundKey: String? = null
  private var charsSinceBind = 0

  private val _activeChatId = MutableStateFlow<String?>(null)
  val activeChatId: StateFlow<String?> = _activeChatId.asStateFlow()

  /** Where the next new chat goes, and whether it is private. */
  private val _draft = MutableStateFlow(Draft())
  val draft: StateFlow<Draft> = _draft.asStateFlow()

  data class Draft(
    val folderId: String? = null,
    val isPrivate: Boolean = false,
    val systemPrompt: String = "",
    val useKnowledge: Boolean? = null,
  )

  private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
  val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

  private val _generating = MutableStateFlow(false)
  val generating: StateFlow<Boolean> = _generating.asStateFlow()

  private val _loadingModel = MutableStateFlow(false)
  val loadingModel: StateFlow<Boolean> = _loadingModel.asStateFlow()

  private val _issue = MutableStateFlow<EngineIssue?>(null)
  val issue: StateFlow<EngineIssue?> = _issue.asStateFlow()

  init {
    // Upstream stored chat history unencrypted in its DataStore. Piggselle keeps its own encrypted
    // history, so wipe the old one once.
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean("legacy_sessions_cleared", false)) {
      scope.launch {
        runCatching { llmSessionManager.clearAllSessions() }
        prefs.edit().putBoolean("legacy_sessions_cleared", true).apply()
      }
    }
  }

  fun attach(mmvm: ModelManagerViewModel) {
    mmvmRef = WeakReference(mmvm)
  }

  // ---- Model ----------------------------------------------------------------------------------

  /** Models that can chat (assistant task) and are downloaded. */
  fun downloadedChatModels(mmvm: ModelManagerViewModel): List<Model> {
    val task = mmvm.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT) ?: return emptyList()
    val statuses = mmvm.uiState.value.modelDownloadStatus
    return task.models.filter { statuses[it.name]?.status == ModelDownloadStatusType.SUCCEEDED }
  }

  /** The model chats will use: the selected one if it's usable, else the first downloaded. */
  fun currentModel(mmvm: ModelManagerViewModel): Model? {
    val downloaded = downloadedChatModels(mmvm)
    val selected = mmvm.uiState.value.selectedModel
    return downloaded.firstOrNull { it.name == selected.name } ?: downloaded.firstOrNull()
  }

  fun selectModel(model: Model) {
    val mmvm = mmvmRef.get() ?: return
    val task = mmvm.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT) ?: return
    stop()
    mmvm.selectModel(model)
    // Free the previous model's memory.
    for (m in task.models) {
      if (m.name != model.name && m.instance != null) mmvm.cleanupModel(context, task, m)
    }
    boundKey = null
    _issue.value = null
    preload()
  }

  /** Starts loading the model in the background so the first message is quick. */
  fun preload() {
    val mmvm = mmvmRef.get() ?: return
    val model = currentModel(mmvm) ?: run {
      _issue.value = EngineIssue.NoModel
      return
    }
    _issue.value = null
    if (model.instance != null || model.initializing) return
    scope.launch { runCatching { ensureModel(mmvm, model) } }
  }

  private suspend fun ensureModel(mmvm: ModelManagerViewModel, model: Model) {
    if (model.initStatusFlow.value is Model.InitializationStatus.Initialized) return
    val task = mmvm.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT) ?: error("Assistant task missing")
    _loadingModel.value = true
    try {
      for (m in task.models) {
        if (m.name != model.name && m.instance != null) mmvm.cleanupModel(context, task, m)
      }
      if (!model.initializing) mmvm.initializeModel(context, task, model)
      val status =
        withTimeout(180_000) {
          model.initStatusFlow.first {
            it is Model.InitializationStatus.Initialized || it is Model.InitializationStatus.Failed
          }
        }
      if (status is Model.InitializationStatus.Failed) {
        throw IllegalStateException(status.error.message ?: "Model failed to load")
      }
      boundKey = null
    } finally {
      _loadingModel.value = false
    }
  }

  // ---- Chats ----------------------------------------------------------------------------------

  /** Opens an existing chat. */
  fun openChat(id: String) {
    if (id == _activeChatId.value) return
    stop()
    if (store.chat(id)?.isPrivate != true) store.dropPrivateChats()
    _activeChatId.value = id
    _messages.value = store.messages(id).map { it.toUi() }
    boundKey = null
  }

  /** Starts a new (empty) chat; it is created on the first message. */
  fun newChat(folderId: String? = null, isPrivate: Boolean = false) {
    stop()
    store.dropPrivateChats()
    _activeChatId.value = null
    _draft.value = Draft(folderId = if (isPrivate) null else folderId, isPrivate = isPrivate)
    _messages.value = emptyList()
    boundKey = null
  }

  fun deleteChat(id: String) {
    if (id == _activeChatId.value) newChat(store.chat(id)?.folderId)
    store.deleteChat(id)
  }

  /** Call after persona, memory, system prompt, knowledge or web settings changed. */
  fun settingsChanged() {
    boundKey = null
  }

  fun activeChat(): ChatMeta? = store.chat(_activeChatId.value)

  /** Effective settings for the open chat (or the draft). */
  fun isPrivate(): Boolean = activeChat()?.isPrivate ?: _draft.value.isPrivate

  fun knowledgeOn(chat: ChatMeta?): Boolean {
    val kb = knowledge.state.value
    if (!kb.configured) return false
    val folder = store.folder(chat?.folderId ?: _draft.value.folderId)
    val own = if (chat != null) chat.useKnowledge else _draft.value.useKnowledge
    return own ?: folder?.useKnowledge ?: kb.settings.onForNewChats
  }

  /** Turns note search on or off for the open chat (or the draft). */
  fun setKnowledge(on: Boolean) {
    val chat = activeChat()
    if (chat != null) store.updateChat(chat.id) { it.copy(useKnowledge = on) }
    else _draft.update { it.copy(useKnowledge = on) }
    boundKey = null
  }

  /** Sets the open chat's own system prompt; blank falls back to folder, then persona. */
  fun setChatSystemPrompt(text: String) {
    val chat = activeChat()
    if (chat != null) store.updateChat(chat.id) { it.copy(systemPrompt = text.trim()) }
    else _draft.update { it.copy(systemPrompt = text.trim()) }
    boundKey = null
  }

  fun systemPromptSource(chat: ChatMeta?): Pair<String, String> {
    val folder = store.folder(chat?.folderId ?: _draft.value.folderId)
    val own = if (chat != null) chat.systemPrompt else _draft.value.systemPrompt
    return when {
      own.isNotBlank() -> "This chat" to own
      !folder?.systemPrompt.isNullOrBlank() -> "Folder \"${folder!!.name}\"" to folder.systemPrompt
      else -> "Persona \"${repository.state.value.activePersona.name}\"" to repository.state.value.activePersona.instructions
    }
  }

  private fun systemPromptFor(chat: ChatMeta): String {
    val override = systemPromptSource(chat).second
    return AssistantPromptBuilder.build(
      state = repository.state.value,
      webSearchAvailable = isWebSearchAvailable(),
      instructionsOverride = override,
      includeMemories = !chat.isPrivate,
      knowledgeAvailable = knowledgeOn(chat),
    )
  }

  private fun history(messages: List<StoredMessage>): List<Message> {
    val usable = messages.filter { !it.error && it.text.isNotBlank() }
    val picked = ArrayDeque<StoredMessage>()
    var used = 0
    for (m in usable.asReversed()) {
      if (used + m.text.length > HISTORY_CHAR_BUDGET && picked.isNotEmpty()) break
      picked.addFirst(m)
      used += m.text.length
    }
    // A conversation must not start with the model's turn.
    while (picked.firstOrNull()?.role == Role.ASSISTANT) picked.removeFirst()
    return picked.map { if (it.role == Role.USER) Message.user(it.text) else Message.model(it.text) }
  }

  private suspend fun bind(mmvm: ModelManagerViewModel, model: Model, chat: ChatMeta, past: List<StoredMessage>) {
    bindLock.withLock {
      ensureModel(mmvm, model)
      val task = mmvm.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT) ?: error("Assistant task missing")
      val prompt = systemPromptFor(chat)
      val key = "${chat.id}|${model.name}|${prompt.hashCode()}|${System.identityHashCode(model.instance)}"
      if (key == boundKey && charsSinceBind < REBIND_CHAR_THRESHOLD) return
      tools.privateMode = chat.isPrivate
      executor.resetSession(
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          sessionId = chat.id,
          actionChannel = tools.sendActionChannel,
          supportImage = task.isVisionEnabled(model),
          supportAudio = task.isAudioEnabled(model),
          enableConversationConstrainedDecoding = true,
          systemInstruction = prompt,
          initialMessages = history(past),
        )
      )
      boundKey = key
      charsSinceBind = 0
    }
  }

  // ---- Sending --------------------------------------------------------------------------------

  fun send(text: String, images: List<Bitmap> = emptyList()) {
    val clean = text.trim()
    if (clean.isEmpty() && images.isEmpty()) return
    if (_generating.value) return
    val mmvm = mmvmRef.get() ?: return
    val model = currentModel(mmvm) ?: run {
      _issue.value = EngineIssue.NoModel
      return
    }

    val chat =
      activeChat()
        ?: store.createChat(
            title = clean.lineSequence().firstOrNull().orEmpty().take(48).ifBlank { "Image" },
            folderId = _draft.value.folderId,
            isPrivate = _draft.value.isPrivate,
          )
          .also { created ->
            val d = _draft.value
            if (d.systemPrompt.isNotBlank() || d.useKnowledge != null) {
              store.updateChat(created.id) { it.copy(systemPrompt = d.systemPrompt, useKnowledge = d.useKnowledge) }
            }
            _activeChatId.value = created.id
          }
          .let { store.chat(it.id) ?: it }

    val past = store.messages(chat.id)
    val userMsg =
      StoredMessage(
        id = store.newMessageId(),
        role = Role.USER,
        text = clean,
        timeMs = System.currentTimeMillis(),
        imageCount = images.size,
      )
    store.setMessages(chat.id, past + userMsg)
    val assistantId = store.newMessageId()
    _messages.value =
      past.map { it.toUi() } +
        userMsg.toUi().copy(images = images) +
        UiMessage(id = assistantId, role = Role.ASSISTANT, text = "", streaming = true)
    _generating.value = true
    _issue.value = null

    generation =
      scope.launch {
        val answer = StringBuilder()
        val thinking = StringBuilder()
        var sources = emptyList<String>()
        var failed = false
        try {
          bind(mmvm, model, chat, past)

          var prompt = clean
          if (knowledgeOn(chat) && clean.isNotEmpty()) {
            updateAssistant(assistantId) { it.copy(status = "Searching your notes…") }
            val hits = knowledge.search(clean)
            if (hits.isNotEmpty()) {
              prompt = KnowledgePrompt.wrap(clean, hits)
              sources = hits.map { it.chunk.docPath }.distinct()
            }
            updateAssistant(assistantId) { it.copy(status = "", sources = sources) }
          }

          val attachments =
            if (model.supportImage) images.map { Attachment.ImageBitmap(it) } else emptyList()
          if (images.isNotEmpty() && attachments.isEmpty()) {
            prompt = "$prompt\n\n(The user attached ${images.size} image(s), but this model can't see images.)"
          }

          executor
            .executeStream(
              AgentExecutionContext(),
              AgentRequest(
                query = prompt,
                attachments = attachments,
                metadata = mapOf(AgentRequest.SESSION_ID to chat.id),
              ),
            )
            .collect { event ->
              when (event) {
                is AgentEvent.StreamToken -> {
                  answer.append(event.token)
                  event.thinking?.let { thinking.append(it) }
                  updateAssistant(assistantId) {
                    it.copy(text = answer.toString(), thinking = thinking.toString(), status = "")
                  }
                }
                is AgentEvent.Error -> {
                  failed = true
                  boundKey = null
                  if (answer.isEmpty()) answer.append("Something went wrong: ${event.errorMessage}")
                }
                is AgentEvent.ContextCompactionStarted ->
                  updateAssistant(assistantId) { it.copy(status = "Condensing the conversation…") }
                else -> {}
              }
            }
          charsSinceBind += prompt.length + answer.length
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Generation failed", e)
          failed = true
          boundKey = null
          if (answer.isEmpty()) answer.append(e.message ?: "The model couldn't answer.")
          _issue.value = EngineIssue.Failed(e.message ?: "Model error")
        } finally {
          val final =
            StoredMessage(
              id = assistantId,
              role = Role.ASSISTANT,
              text = answer.toString().trim().ifEmpty { if (failed) "No answer." else "…" },
              timeMs = System.currentTimeMillis(),
              sources = sources,
              error = failed,
            )
          // Only save to the chat that produced it, even if the user switched meanwhile.
          store.setMessages(chat.id, store.messages(chat.id) + final)
          if (_activeChatId.value == chat.id) {
            updateAssistant(assistantId) {
              it.copy(text = final.text, streaming = false, status = "", error = failed, sources = sources)
            }
          }
          _generating.value = false
        }
      }
  }

  fun stop() {
    if (_generating.value) {
      executor.interrupt()
    }
  }

  /** Re-asks the last user message. */
  fun regenerate() {
    val id = _activeChatId.value ?: return
    if (_generating.value) return
    val msgs = store.messages(id)
    val lastUser = msgs.indexOfLast { it.role == Role.USER }
    if (lastUser < 0) return
    val text = msgs[lastUser].text
    store.setMessages(id, msgs.subList(0, lastUser))
    _messages.value = store.messages(id).map { it.toUi() }
    boundKey = null
    send(text)
  }

  /** Updates the status line of the message being generated (called for tool progress). */
  fun onToolStatus(label: String, inProgress: Boolean) {
    val streamingId = _messages.value.lastOrNull { it.streaming }?.id ?: return
    updateAssistant(streamingId) { it.copy(status = if (inProgress) label else "") }
  }

  private fun updateAssistant(id: String, block: (UiMessage) -> UiMessage) {
    _messages.update { list -> list.map { if (it.id == id) block(it) else it } }
  }
}
