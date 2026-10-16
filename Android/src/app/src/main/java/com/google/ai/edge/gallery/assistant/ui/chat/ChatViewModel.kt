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

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.assistant.chat.ChatEngine
import com.google.ai.edge.gallery.assistant.chat.ChatStore
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.knowledge.EmbedderManager
import com.google.ai.edge.gallery.assistant.knowledge.KnowledgeBase
import com.google.ai.edge.gallery.assistant.models.ModelSettingsStore
import com.google.ai.edge.gallery.assistant.voice.WhisperManager
import com.google.ai.edge.gallery.assistant.tools.AssistantTools
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Hands the app-wide singletons to the composables. */
@HiltViewModel
class ChatViewModel
@Inject
constructor(
  val engine: ChatEngine,
  val store: ChatStore,
  val repository: AssistantRepository,
  val tools: AssistantTools,
  val knowledge: KnowledgeBase,
  val embedder: EmbedderManager,
  val modelSettings: ModelSettingsStore,
  val whisper: WhisperManager,
) : ViewModel()
