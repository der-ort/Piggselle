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

package com.google.ai.edge.gallery.assistant.tools

import android.util.Log
import com.google.ai.edge.gallery.assistant.data.AssistantRepository
import com.google.ai.edge.gallery.assistant.net.HostPolicy
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.gallery.assistant.privacy.MemoryOverlap
import com.google.ai.edge.gallery.assistant.privacy.QueryScrubber
import com.google.ai.edge.gallery.assistant.privacy.RedactionCategory
import com.google.ai.edge.gallery.assistant.privacy.ScrubResult
import com.google.ai.edge.gallery.assistant.search.SearchResultFormatter
import com.google.ai.edge.gallery.assistant.search.SearxngClient
import com.google.ai.edge.gallery.tools.SkillProgressToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.tools.ToolActionName
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PGAssistantTools"
private const val CONFIRM_TIMEOUT_MS = 5 * 60_000L

/**
 * Asks the UI to show the scrubbed query. Completes with the query to send (possibly edited by the
 * user), or null if the user declined.
 */
class ConfirmWebSearchAction(
  val modelQuery: String,
  val scrub: ScrubResult,
  val host: String,
  /** The query looks like it contains something from the user's memories. */
  val touchesMemory: Boolean = false,
  val result: CompletableDeferred<String?> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.CONFIRM_WEB_SEARCH)

/** The two tools Piggselle gives the model, plus the channel their UI side effects go through. */
@Singleton
class AssistantTools @Inject constructor(private val repository: AssistantRepository) :
  ToolsProvider {
  private val channel = Channel<ToolAction>(Channel.UNLIMITED)
  val receiveActionChannel: ReceiveChannel<ToolAction> = channel
  val sendActionChannel: SendChannel<ToolAction> = channel

  private val webSearch = WebSearchTool(repository, fallbackActions = channel)
  private val suggestMemory = SuggestMemoryTool(repository)

  override fun getAvailableTools(): List<ToolDefinition> = listOf(webSearch, suggestMemory)

  override fun registerTool(tool: ToolDefinition) {}

  override fun unregisterTool(tool: ToolDefinition) {}
}

/**
 * @param fallbackActions used when the runtime re-created the session without an action channel
 *   (e.g. after the user stopped a response), so the confirmation dialog can still be shown.
 */
class WebSearchTool(
  private val repository: AssistantRepository,
  private val fallbackActions: SendChannel<ToolAction>,
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Suppress("FunctionName")
  @Tool(
    description =
      "Search the web through the user's private SearXNG instance for public, general " +
        "information. The user reviews the query before it is sent."
  )
  fun web_search(
    @ToolParam(
      description =
        "Short generic keywords, max 8 words, third person, no names or personal details."
    )
    query: String
  ): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      val policy = NetworkGate.policy.value
      if (!policy.isEnabled(NetPurpose.WEB_SEARCH) || policy.searxngBaseUrl.isBlank()) {
        return@runBlocking mapOf(
          "status" to "unavailable",
          "message" to
            "Web search is turned off. Answer from your own knowledge and tell the user you " +
              "couldn't check online.",
        )
      }
      val state = repository.state.value
      val terms = state.search.privateTerms + listOf(state.activePersona.userName)
      val scrub = QueryScrubber(terms).scrub(query)
      val host = HostPolicy.parseBase(policy.searxngBaseUrl)?.host ?: "your SearXNG"
      val actions: SendChannel<ToolAction> = executionContext?.actionChannel ?: fallbackActions

      val touchesMemory =
        state.memoryEnabled && MemoryOverlap.overlaps(scrub.cleaned, state.memories.map { it.text })
      val needsConfirmation =
        !state.search.autoSend || scrub.requiresReview || scrub.isEmpty || touchesMemory
      val finalQuery: String? =
        if (needsConfirmation) {
          val action =
            ConfirmWebSearchAction(
              modelQuery = query,
              scrub = scrub,
              host = host,
              touchesMemory = touchesMemory,
            )
          actions.send(action)
          // If nobody answers (screen closed, app backgrounded), treat it as "don't send"
          // instead of blocking the model forever.
          withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { action.result.await() }
            .also { if (!action.result.isCompleted) action.result.complete(null) }
        } else {
          scrub.cleaned
        }

      if (finalQuery.isNullOrBlank()) {
        actions.send(
          SkillProgressToolAction(
            label = "Web search cancelled",
            inProgress = false,
            addItemTitle = "Not sent",
            addItemDescription = scrub.cleaned.ifBlank { "(nothing left after filtering)" },
          )
        )
        return@runBlocking mapOf(
          "status" to "declined",
          "message" to
            "The user declined this web search. Do not retry it. Answer without it or ask " +
              "the user what they want.",
        )
      }

      actions.send(
        SkillProgressToolAction(
          label = "Searching $host…",
          inProgress = true,
          addItemTitle = "Sent to $host",
          addItemDescription = finalQuery,
        )
      )
      try {
        val raw =
          withContext(Dispatchers.IO) {
            SearxngClient.search(policy.searxngBaseUrl, finalQuery, state.search.language)
          }
        val response = SearchResultFormatter.normalize(raw, state.search.maxResults)
        actions.send(
          SkillProgressToolAction(
            label = "Web search: ${response.hits.size} results",
            inProgress = false,
            addItemTitle = "Results",
            addItemDescription = SearchResultFormatter.forUser(response).ifBlank { "No results" },
          )
        )
        mapOf("status" to "ok", "results" to SearchResultFormatter.forModel(finalQuery, response))
      } catch (e: IOException) {
        Log.w(TAG, "Web search failed", e)
        actions.send(
          SkillProgressToolAction(
            label = "Web search failed",
            inProgress = false,
            addItemTitle = "Error",
            addItemDescription = e.message ?: "Unknown error",
          )
        )
        mapOf(
          "status" to "error",
          "message" to
            "The web search failed (${e.message}). Tell the user briefly and answer as well " +
              "as you can without it.",
        )
      }
    }
}

class SuggestMemoryTool(private val repository: AssistantRepository) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Suppress("FunctionName")
  @Tool(
    description =
      "Propose a lasting fact about the user to remember in future chats. The user decides " +
        "whether to save it."
  )
  fun suggest_memory(
    @ToolParam(description = "One short third-person sentence, e.g. 'Prefers tea over coffee.'")
    fact: String
  ): Map<String, String> {
    if (!repository.state.value.memoryEnabled) {
      return mapOf("status" to "disabled", "message" to "Memory is turned off. Continue normally.")
    }
    // Refuse obvious secrets outright; the user would have to type those in themselves.
    val secretCategories =
      setOf(RedactionCategory.CARD, RedactionCategory.IBAN, RedactionCategory.EMAIL)
    val scrub = QueryScrubber(maxLength = 400).scrub(fact)
    if (scrub.redactions.any { it.category in secretCategories }) {
      return mapOf(
        "status" to "rejected",
        "message" to "That looks like account or contact data, which is not stored. Continue.",
      )
    }
    val added = repository.addSuggestion(fact)
    return mapOf(
      "status" to if (added) "shown_to_user" else "already_known",
      "message" to "The user will decide whether to save it. Continue your answer normally.",
    )
  }
}
