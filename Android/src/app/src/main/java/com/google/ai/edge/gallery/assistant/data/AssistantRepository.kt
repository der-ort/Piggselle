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

package com.google.ai.edge.gallery.assistant.data

import android.content.Context
import com.google.ai.edge.gallery.assistant.storage.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val STATE_FILE = "assistant_state.bin"

/**
 * Holds personas, memories and search settings. Everything is persisted encrypted via
 * [SecureStore] and never leaves the device. Pending memory suggestions only live in RAM.
 */
@Singleton
class AssistantRepository @Inject constructor(@ApplicationContext private val context: Context) {
  private val writer = Executors.newSingleThreadExecutor()

  private val _state =
    MutableStateFlow(
      SecureStore.read(context, STATE_FILE, AssistantState.serializer()) ?: AssistantState()
    )
  val state: StateFlow<AssistantState> = _state.asStateFlow()

  private val _suggestions = MutableStateFlow<List<MemorySuggestion>>(emptyList())
  val suggestions: StateFlow<List<MemorySuggestion>> = _suggestions.asStateFlow()

  private fun mutate(block: (AssistantState) -> AssistantState) {
    _state.update(block)
    val snapshot = _state.value
    writer.execute { SecureStore.write(context, STATE_FILE, AssistantState.serializer(), snapshot) }
  }

  // ---- Personas -------------------------------------------------------------------------------

  fun setActivePersona(id: String) = mutate { it.copy(activePersonaId = id) }

  fun upsertPersona(persona: Persona) = mutate { s ->
    val exists = s.personas.any { it.id == persona.id }
    s.copy(
      personas =
        if (exists) s.personas.map { if (it.id == persona.id) persona else it }
        else s.personas + persona
    )
  }

  fun newPersonaId(): String = UUID.randomUUID().toString()

  fun deletePersona(id: String) = mutate { s ->
    val remaining = s.personas.filterNot { it.id == id }.ifEmpty { DefaultPersonas.all }
    s.copy(
      personas = remaining,
      activePersonaId =
        if (s.activePersonaId == id) remaining.first().id else s.activePersonaId,
    )
  }

  fun resetPersona(id: String) {
    DefaultPersonas.all.firstOrNull { it.id == id }?.let { upsertPersona(it) }
  }

  // ---- Memory ---------------------------------------------------------------------------------

  fun setMemoryEnabled(on: Boolean) = mutate { it.copy(memoryEnabled = on) }

  fun addMemory(text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    mutate { s ->
      if (s.memories.any { it.text.equals(clean, ignoreCase = true) }) s
      else
        s.copy(
          memories =
            s.memories +
              MemoryItem(
                id = UUID.randomUUID().toString(),
                text = clean,
                createdAtMs = System.currentTimeMillis(),
              )
        )
    }
  }

  fun updateMemory(id: String, text: String) = mutate { s ->
    s.copy(memories = s.memories.map { if (it.id == id) it.copy(text = text.trim()) else it })
  }

  fun togglePin(id: String) = mutate { s ->
    s.copy(memories = s.memories.map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
  }

  fun deleteMemory(id: String) = mutate { s -> s.copy(memories = s.memories.filterNot { it.id == id }) }

  fun deleteAllMemories() = mutate { it.copy(memories = emptyList()) }

  /** Called by the suggest_memory tool. Duplicates of saved or pending facts are ignored. */
  fun addSuggestion(text: String): Boolean {
    val clean = text.trim().removePrefix("-").trim()
    if (clean.length < 3 || clean.length > 300) return false
    val known =
      _state.value.memories.any { it.text.equals(clean, ignoreCase = true) } ||
        _suggestions.value.any { it.text.equals(clean, ignoreCase = true) }
    if (known) return false
    _suggestions.update { it + MemorySuggestion(UUID.randomUUID().toString(), clean) }
    return true
  }

  fun acceptSuggestion(id: String, editedText: String? = null) {
    val suggestion = _suggestions.value.firstOrNull { it.id == id } ?: return
    addMemory(editedText ?: suggestion.text)
    dismissSuggestion(id)
  }

  fun dismissSuggestion(id: String) = _suggestions.update { list -> list.filterNot { it.id == id } }

  // ---- Search settings ------------------------------------------------------------------------

  fun updateSearch(block: (SearchSettings) -> SearchSettings) = mutate {
    it.copy(search = block(it.search))
  }
}
