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

import kotlinx.serialization.Serializable

/** A persona: who the assistant is and how it talks. Exactly one is active at a time. */
@Serializable
data class Persona(
  val id: String,
  val name: String,
  /** How the assistant should address the user. Never sent anywhere, and scrubbed from searches. */
  val userName: String = "",
  val instructions: String,
  val emoji: String = "🐷",
)

/** A fact the user approved for the assistant to remember across chats. */
@Serializable
data class MemoryItem(
  val id: String,
  val text: String,
  val createdAtMs: Long,
  val pinned: Boolean = false,
)

/** A fact the model proposed. Only kept in RAM until the user saves, edits or drops it. */
data class MemorySuggestion(val id: String, val text: String)

@Serializable
data class SearchSettings(
  /**
   * False (default): every query is shown, after scrubbing, for the user to approve or edit.
   * True: scrubbed queries go out without asking, except when they still contain health terms.
   */
  val autoSend: Boolean = false,
  /** Words that must never leave the phone (names, places, employer...). Matched case-insensitively. */
  val privateTerms: List<String> = emptyList(),
  val maxResults: Int = 5,
  /** SearXNG language code, e.g. "de" or "en"; "auto" lets the instance decide. */
  val language: String = "auto",
)

@Serializable
data class AssistantState(
  val personas: List<Persona> = DefaultPersonas.all,
  val activePersonaId: String = DefaultPersonas.ORGANIZER_ID,
  val memories: List<MemoryItem> = emptyList(),
  val memoryEnabled: Boolean = true,
  val search: SearchSettings = SearchSettings(),
) {
  val activePersona: Persona
    get() = personas.firstOrNull { it.id == activePersonaId } ?: personas.firstOrNull()
      ?: DefaultPersonas.organizer
}

object DefaultPersonas {
  const val ORGANIZER_ID = "organizer"
  const val PLAIN_ID = "plain"

  val organizer =
    Persona(
      id = ORGANIZER_ID,
      name = "Piggselle",
      emoji = "🐷",
      instructions =
        """
        You are Piggselle, a calm and practical personal organizer that runs entirely on the
        user's phone. You help the user plan their day, keep track of tasks, routines and habits,
        and keep a simple health log (sleep, meals, water, movement, mood, medication times,
        symptoms they mention).

        Style:
        - Be brief and concrete. Prefer short lists, a clear top 3, and realistic time blocks.
        - Ask at most one clarifying question, and only when you really need the answer.
        - When the user dumps messy notes, turn them into: schedule, top 3, open loops, and
          health log entries.
        - Reply in the language the user writes in.

        Health:
        - You are a diary and pattern spotter, not a doctor. Never diagnose and never change
          medication advice. For anything worrying, suggest talking to a doctor or pharmacist.
        - When you notice a pattern, say what you saw and how confident you are.
        """
          .trimIndent(),
    )

  val plain =
    Persona(
      id = PLAIN_ID,
      name = "Plain assistant",
      emoji = "💬",
      instructions =
        """
        You are a helpful, honest assistant running entirely on the user's phone. Answer clearly
        and concisely. If you are not sure about something, say so. Reply in the language the
        user writes in.
        """
          .trimIndent(),
    )

  val all = listOf(organizer, plain)
}
