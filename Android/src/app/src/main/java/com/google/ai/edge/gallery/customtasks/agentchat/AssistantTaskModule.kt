/*
 * Copyright 2025 Google LLC
 * Modifications copyright 2026 Daniel (der-ort)
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.assistant.AssistantPromptBuilder
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.tools.AssistantTools
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val PIGGSELLE_REPO_URL = "https://github.com/der-ort/Piggselle"

/** True if web search can currently be used: purpose enabled and an instance configured. */
fun isWebSearchAvailable(): Boolean {
  val policy = NetworkGate.policy.value
  return policy.isEnabled(NetPurpose.WEB_SEARCH) && policy.searxngBaseUrl.isNotBlank()
}

/**
 * Piggselle's assistant. It reuses the upstream "agent chat" task id so that model assignment,
 * configuration screens and deep links keep working, but replaces skills/MCP with a persona,
 * approved memories and a filtered SearXNG web search.
 */
class AssistantTask(
  private val context: Context,
  private val repository: AssistantRepository,
  private val tools: AssistantTools,
  private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = context.getString(R.string.task_label_agent_skills),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_agent_skills),
      shortDescription = context.getString(R.string.task_short_desc_agent_skills),
      docUrl = PIGGSELLE_REPO_URL,
      sourceCodeUrl = PIGGSELLE_REPO_URL,
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = "",
      supportImage = true,
      supportAudio = true,
      supportContextCompact = true,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = tools.sendActionChannel,
          supportImage = task.isVisionEnabled(model),
          supportAudio = task.isAudioEnabled(model),
          enableConversationConstrainedDecoding = true,
          systemInstruction =
            AssistantPromptBuilder.build(repository.state.value, isWebSearchAvailable()),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  // The chat UI lives in assistant/ui/chat; this task only owns model init and cleanup.
  @Composable override fun MainScreen(data: Any) {}
}

@Module
@InstallIn(SingletonComponent::class)
internal object AssistantTaskModule {
  @Provides
  @Singleton
  @AgentChatExecutor
  fun provideAssistantExecutor(
    tools: AssistantTools,
    llmSessionManager: LlmSessionManager,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = tools,
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )
  }

  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    repository: AssistantRepository,
    tools: AssistantTools,
    @AgentChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask = AssistantTask(context, repository, tools, executor)
}
