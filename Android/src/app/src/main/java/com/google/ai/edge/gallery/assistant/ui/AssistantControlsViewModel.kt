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

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.tools.AssistantTools
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the assistant's composables access to the repository and the tool action channel. */
@HiltViewModel
class AssistantControlsViewModel
@Inject
constructor(val repository: AssistantRepository, val tools: AssistantTools) : ViewModel()
