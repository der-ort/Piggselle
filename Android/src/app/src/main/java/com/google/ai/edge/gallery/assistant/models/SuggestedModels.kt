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

package com.google.ai.edge.gallery.assistant.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A community model on Hugging Face that runs with LiteRT-LM. */
data class SuggestedModel(val repo: String, val name: String, val note: String, val group: String)

/**
 * Hand-picked community models in .litertlm format, sized for phones with 12–16 GB RAM like the
 * Pixel 9 Pro. Tapping one opens its Hugging Face page in the app to choose a file; license and
 * file sizes are shown there.
 */
val SUGGESTED_MODELS =
  listOf(
    SuggestedModel("litert-community/Qwen3-4B-Instruct-2507", "Qwen3 4B Instruct", "Strong all-rounder, many languages", "Chat"),
    SuggestedModel("litert-community/Qwen3.5-4B", "Qwen3.5 4B", "Newer Qwen, optional thinking", "Chat"),
    SuggestedModel("litert-community/Qwen3.5-2B", "Qwen3.5 2B", "Fast and light", "Chat"),
    SuggestedModel("litert-community/Phi-4-mini-instruct", "Phi-4 mini", "Microsoft, 3.8B, good at reasoning", "Chat"),
    SuggestedModel("litert-community/Ministral-3-3B-Instruct-2512", "Ministral 3 3B", "Mistral's small model, European languages", "Chat"),
    SuggestedModel("litert-community/granite-4.2-3b", "Granite 4.2 3B", "IBM, built for tools and documents", "Chat"),
    SuggestedModel("litert-community/SmolLM3-3B", "SmolLM3 3B", "Fully open: weights, data and training code", "Chat"),
    SuggestedModel("litert-community/LFM2.5-2.6B", "LFM2.5 2.6B", "Liquid AI, very fast on phones", "Chat"),
    SuggestedModel("litert-community/LFM2.5-1.2B-Instruct", "LFM2.5 1.2B", "Tiny and quick", "Chat"),
    SuggestedModel("litert-community/Nemotron-3-Nano-4B", "Nemotron 3 Nano 4B", "NVIDIA, hybrid architecture", "Chat"),
    SuggestedModel("litert-community/Spark-X2.5-4B", "Spark-X2.5 4B", "Compact general model", "Chat"),
    SuggestedModel("litert-community/Ternary-Bonsai-8B", "Ternary Bonsai 8B", "8B parameters with ternary weights, small download", "Chat"),
    SuggestedModel("litert-community/Ternary-Bonsai-1.7B", "Ternary Bonsai 1.7B", "Ternary weights, very small", "Chat"),
    SuggestedModel("litert-community/Qwen3-4B-Thinking-2507", "Qwen3 4B Thinking", "Thinks step by step before answering", "Reasoning"),
    SuggestedModel("litert-community/Phi-4-mini-reasoning", "Phi-4 mini reasoning", "Math and logic", "Reasoning"),
    SuggestedModel("litert-community/DeepSeek-R1-Distill-Qwen-7B", "DeepSeek R1 Distill 7B", "Bigger reasoning model, slower", "Reasoning"),
    SuggestedModel("litert-community/LFM2.5-VL-1.6B", "LFM2.5 VL 1.6B", "Understands images", "Images"),
    SuggestedModel("litert-community/Qwen2-VL-2B", "Qwen2 VL 2B", "Understands images", "Images"),
    SuggestedModel("litert-community/InternVL3_5-2B", "InternVL 3.5 2B", "Understands images and documents", "Images"),
    SuggestedModel("litert-community/SmolVLM2-2.2B", "SmolVLM2 2.2B", "Understands images", "Images"),
    SuggestedModel("litert-community/gemma-4-12B-it-litert-lm", "Gemma 4 12B", "Smarter than E4B, needs much more memory", "Larger"),
    SuggestedModel("litert-community/Qwen3-8B", "Qwen3 8B", "Larger Qwen, slower", "Larger"),
    SuggestedModel("litert-community/Hy-MT2-1.8B", "Hy-MT2 1.8B", "Translation between many languages", "Special"),
  )

val SUGGESTED_GROUP_NOTES =
  mapOf(
    "Images" to "When importing, turn on image support.",
    "Larger" to "Close other apps first; loading takes a while.",
    "Chat" to "Web search and memory suggestions work best with Gemma and Qwen.",
  )

@Composable
fun SuggestedModelCard(model: SuggestedModel, downloaded: Boolean, onClick: () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(model.name, style = MaterialTheme.typography.titleSmall)
        Text(model.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Icon(
        if (downloaded) Icons.Rounded.CheckCircle else Icons.Rounded.ChevronRight,
        contentDescription = if (downloaded) "Downloaded" else null,
        tint = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
