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

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.assistant.AssistantPromptBuilder
import com.google.ai.edge.gallery.assistant.data.MemorySuggestion
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.tools.ConfirmWebSearchAction
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.isWebSearchAvailable
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.tools.SkillProgressToolAction
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val TAG = "PGAssistantScreen"

private enum class AssistantSheet {
  PERSONA,
  MEMORY,
  PRIVACY,
}

private data class QuickPrompt(val label: String, val prompt: String)

private val QUICK_PROMPTS =
  listOf(
    QuickPrompt(
      "Plan my day",
      "Help me plan today. Ask me for my appointments and what's on my mind, then give me a " +
        "realistic schedule and my top 3.",
    ),
    QuickPrompt(
      "Brain dump",
      "I'm going to dump everything on my mind. Sort it into: schedule, top 3, open loops, and " +
        "health log entries.",
    ),
    QuickPrompt(
      "Health log",
      "Let's log how I'm doing today: sleep, meals, water, movement, mood and anything else. " +
        "Ask me briefly, one thing at a time.",
    ),
    QuickPrompt(
      "Evening review",
      "Let's do a short evening review: what got done, what moves to tomorrow, and one thing " +
        "that went well.",
    ),
  )

private const val REVIEW_MEMORY_PROMPT =
  "Look back at this chat. For each lasting fact about me that would help in future chats " +
    "(routines, preferences, goals, people), call suggest_memory once. Skip anything already " +
    "remembered. Then briefly tell me what you suggested."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  initialQuery: String? = null,
  viewModel: AgentChatViewModel = hiltViewModel(),
  controls: AssistantControlsViewModel = hiltViewModel(),
) {
  val repository = controls.repository
  val tools = controls.tools
  val state by repository.state.collectAsState()
  val suggestions by repository.suggestions.collectAsState()
  val policy by NetworkGate.policy.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  var sheet by remember { mutableStateOf<AssistantSheet?>(null) }
  var memoryDirty by remember { mutableStateOf(false) }
  var pendingSearch by remember { mutableStateOf<ConfirmWebSearchAction?>(null) }
  var sendTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  // Deep links are untrusted: their text is only sent after the user confirms it.
  var pendingExternalPrompt by
    remember(initialQuery) { mutableStateOf(initialQuery?.takeIf { it.isNotBlank() }) }

  val webAvailable = !policy.killSwitch && isWebSearchAvailable()

  // Keep the view model's prompt in sync, so every path that re-creates the session (restore,
  // stop-and-continue) uses the current persona, memories and tool rules.
  LaunchedEffect(state, policy) {
    viewModel.setUISystemPrompt(AssistantPromptBuilder.build(state, isWebSearchAvailable()))
  }

  fun rebuildSession(model: Model, initialMessages: List<ChatMessage>, clearHistory: Boolean) {
    val history = initialMessages.mapNotNull { convertToLitertMessage(it) }
    viewModel.resetSession(
      task = task,
      model = model,
      systemInstruction = AssistantPromptBuilder.build(repository.state.value, isWebSearchAvailable()),
      actionChannel = tools.sendActionChannel,
      supportImage = task.isVisionEnabled(model),
      supportAudio = task.isAudioEnabled(model),
      enableConversationConstrainedDecoding = true,
      initialMessages = history,
      clearHistory = clearHistory,
    )
  }

  /** Re-applies the system prompt while keeping the visible conversation. */
  fun applySettingsKeepingChat() {
    val model = modelManagerViewModel.uiState.value.selectedModel
    if (model.initStatusFlow.value !is Model.InitializationStatus.Initialized) return
    val messages = viewModel.uiState.value.messagesByModel[model.name].orEmpty()
    rebuildSession(model, messages, clearHistory = false)
  }

  fun send(model: Model, text: String) {
    sendTrigger =
      SendMessageTrigger(model = model, messages = listOf(ChatMessageText(content = text, side = ChatSide.USER)))
  }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_AGENT_CHAT,
    navigateUp = navigateUp,
    viewModel = viewModel,
    showImagePicker = true,
    showAudioPicker = true,
    sendMessageTrigger = sendTrigger,
    onResetSessionClickedOverride = { _, model, messages, clearHistory ->
      rebuildSession(model, messages, clearHistory)
    },
    extraInputChips = {
      AssistantChip(
        icon = null,
        text = "${state.activePersona.emoji} ${state.activePersona.name}",
        onClick = { sheet = AssistantSheet.PERSONA },
      )
      AssistantChip(
        icon = { Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) },
        text = if (state.memoryEnabled) "${state.memories.size}" else "off",
        onClick = { sheet = AssistantSheet.MEMORY },
        contentDescription = "Memory",
      )
      AssistantChip(
        icon = {
          Icon(
            if (webAvailable) Icons.Outlined.Public else Icons.Outlined.PublicOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint =
              if (webAvailable) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        text = if (policy.killSwitch) "Offline" else if (webAvailable) "Web" else "Local",
        onClick = { sheet = AssistantSheet.PRIVACY },
        contentDescription = "Privacy and web search",
      )
    },
    composableBelowMessageList = { model ->
      val currentModel by rememberUpdatedState(model)
      LaunchedEffect(tools.receiveActionChannel) {
        for (action in tools.receiveActionChannel) {
          when (action) {
            is ConfirmWebSearchAction -> pendingSearch = action
            is SkillProgressToolAction ->
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = Icons.Outlined.Public,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
            else -> Log.d(TAG, "Ignoring tool action ${action.name}")
          }
        }
      }
      MemorySuggestionCards(
        suggestions = suggestions,
        onSave = { s, edited -> repository.acceptSuggestion(s.id, edited) },
        onDismiss = { s -> repository.dismissSuggestion(s.id) },
      )
    },
    emptyStateComposable = { model ->
      val initStatus by model.initStatusFlow.collectAsState()
      val ready = initStatus is Model.InitializationStatus.Initialized
      Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
          !WindowInsets.isImeVisible,
          enter = fadeIn(animationSpec = tween(200)),
          exit = fadeOut(animationSpec = tween(200)),
          modifier = Modifier.align(Alignment.Center),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 40.dp).padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(state.activePersona.emoji, style = MaterialTheme.typography.displayMedium)
            val greetName = state.activePersona.userName.takeIf { it.isNotBlank() }
            Text(
              if (greetName != null) "Hi $greetName!" else "Hi!",
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.padding(top = 8.dp),
            )
            Text(
              "Everything here stays on your phone." +
                if (webAvailable) " Web searches go to your SearXNG, filtered." else "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }
        Row(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          QUICK_PROMPTS.forEach { qp ->
            FilledTonalButton(enabled = ready, onClick = { send(model, qp.prompt) }) {
              Text(qp.label)
            }
          }
        }
      }
    },
  )

  pendingSearch?.let { action ->
    WebSearchConfirmDialog(action = action, onFinished = { pendingSearch = null })
  }

  pendingExternalPrompt?.let { prompt ->
    AlertDialog(
      onDismissRequest = { pendingExternalPrompt = null },
      title = { Text("Send this message?") },
      text = { Text("Another app asked to start a chat with:\n\n$prompt") },
      confirmButton = {
        Button(
          enabled = selectedModel.initStatusFlow.value is Model.InitializationStatus.Initialized,
          onClick = {
            send(selectedModel, prompt)
            pendingExternalPrompt = null
          },
        ) {
          Text("Send")
        }
      },
      dismissButton = { TextButton(onClick = { pendingExternalPrompt = null }) { Text("Discard") } },
    )
  }

  when (sheet) {
    AssistantSheet.PERSONA ->
      PersonaSheet(
        repository = repository,
        onDismiss = { sheet = null },
        onPersonaApplied = { applySettingsKeepingChat() },
      )
    AssistantSheet.MEMORY ->
      MemorySheet(
        repository = repository,
        onDismiss = {
          sheet = null
          if (memoryDirty) {
            memoryDirty = false
            applySettingsKeepingChat()
          }
        },
        onReviewChat = {
          sheet = null
          send(selectedModel, REVIEW_MEMORY_PROMPT)
        },
        onMemoryChanged = { memoryDirty = true },
      )
    AssistantSheet.PRIVACY ->
      PrivacySheet(
        repository = repository,
        onDismiss = { sheet = null },
        onWebSearchChanged = { applySettingsKeepingChat() },
      )
    null -> {}
  }
}

@Composable
private fun AssistantChip(
  icon: (@Composable () -> Unit)?,
  text: String,
  onClick: () -> Unit,
  contentDescription: String? = null,
) {
  OutlinedButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    modifier =
      if (contentDescription != null)
        Modifier.semanticsLabel(contentDescription)
      else Modifier,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (icon != null) {
        icon()
        Spacer(Modifier.width(4.dp))
      }
      Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
  }
}

private fun Modifier.semanticsLabel(label: String): Modifier =
  this.semantics(mergeDescendants = true) { contentDescription = label }

@Composable
private fun MemorySuggestionCards(
  suggestions: List<MemorySuggestion>,
  onSave: (MemorySuggestion, String?) -> Unit,
  onDismiss: (MemorySuggestion) -> Unit,
) {
  var editing by remember { mutableStateOf<MemorySuggestion?>(null) }
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    suggestions.take(3).forEach { s ->
      Card(
        colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            Icons.Outlined.Lightbulb,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
          )
          Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
              "Remember this?",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
              s.text,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
          TextButton(onClick = { editing = s }) { Text("Edit") }
          TextButton(onClick = { onSave(s, null) }) { Text("Save") }
          IconButton(onClick = { onDismiss(s) }) {
            Icon(Icons.Rounded.Close, contentDescription = "Don't remember")
          }
        }
      }
    }
    if (suggestions.size > 3) {
      Text(
        "+${suggestions.size - 3} more suggestions",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  editing?.let { s ->
    var text by remember(s.id) { mutableStateOf(s.text) }
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text("Edit before saving") },
      text = {
        OutlinedTextField(value = text, onValueChange = { text = it.take(300) }, modifier = Modifier.fillMaxWidth())
      },
      confirmButton = {
        Button(
          enabled = text.isNotBlank(),
          onClick = {
            onSave(s, text)
            editing = null
          },
        ) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
    )
  }
}
