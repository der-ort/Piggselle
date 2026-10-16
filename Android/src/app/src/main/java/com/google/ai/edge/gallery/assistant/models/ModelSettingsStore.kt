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

import android.content.Context
import com.google.ai.edge.gallery.assistant.storage.SecureStore
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.convertValueToTargetType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

private const val FILE = "model_settings.bin"

@Serializable private data class StoredModelSettings(val values: Map<String, Map<String, String>> = emptyMap())

/** Per-chat overrides coming from a folder. Null fields keep the model's own setting. */
data class SamplingOverride(val temperature: Float? = null, val thinking: Boolean? = null)

/**
 * The user's own settings per model (temperature, top-k/p, context length, accelerator,
 * thinking, speculative decoding), persisted across restarts. Upstream reset them on every start.
 */
@Singleton
class ModelSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
  private val _saved = MutableStateFlow(SecureStore.read(context, FILE, StoredModelSettings.serializer()) ?: StoredModelSettings())

  /** Bumps whenever settings change, so the UI and the engine can react. */
  private val _version = MutableStateFlow(0)
  val version: StateFlow<Int> = _version.asStateFlow()

  /** The model's defaults with the user's saved values on top. */
  fun baseValues(model: Model): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    for (c in model.configs) out[c.key.label] = c.defaultValue
    val saved = _saved.value.values[model.name].orEmpty()
    for (c in model.configs) {
      saved[c.key.label]?.let { raw ->
        val v = convertValueToTargetType(raw, c.valueType)
        if (v != "") out[c.key.label] = v
      }
    }
    return out
  }

  fun hasCustomValues(model: Model): Boolean = _saved.value.values[model.name].orEmpty().isNotEmpty()

  /** Values to use for a session, with folder overrides applied where the model supports them. */
  fun effectiveValues(model: Model, override: SamplingOverride?): Map<String, Any> {
    val base = baseValues(model).toMutableMap()
    override?.temperature?.let { t ->
      if (base.containsKey(ConfigKeys.TEMPERATURE.label)) base[ConfigKeys.TEMPERATURE.label] = t
    }
    override?.thinking?.let { on ->
      if (base.containsKey(ConfigKeys.ENABLE_THINKING.label)) base[ConfigKeys.ENABLE_THINKING.label] = on
    }
    return base
  }

  /** Applies the saved values to the model object before it is loaded. */
  fun applyTo(model: Model) {
    if (model.configs.isNotEmpty()) model.configValues = baseValues(model)
  }

  /** Saves [values] (only those differing from the defaults). Returns true if a reload is needed. */
  fun save(model: Model, values: Map<String, Any>): Boolean {
    val before = baseValues(model)
    val defaults = model.configs.associate { it.key.label to it.defaultValue }
    val toStore =
      values.filter { (k, v) -> defaults.containsKey(k) && "$v" != "${defaults[k]}" }.mapValues { (_, v) -> "$v" }
    val all = _saved.value.values.toMutableMap()
    if (toStore.isEmpty()) all.remove(model.name) else all[model.name] = toStore
    persist(StoredModelSettings(all))
    applyTo(model)
    val after = baseValues(model)
    return RELOAD_KEYS.any { "${before[it]}" != "${after[it]}" }
  }

  fun reset(model: Model): Boolean {
    val before = baseValues(model)
    val all = _saved.value.values.toMutableMap()
    all.remove(model.name)
    persist(StoredModelSettings(all))
    applyTo(model)
    val after = baseValues(model)
    return RELOAD_KEYS.any { "${before[it]}" != "${after[it]}" }
  }

  private fun persist(s: StoredModelSettings) {
    _saved.value = s
    SecureStore.write(context, FILE, StoredModelSettings.serializer(), s)
    _version.value++
  }

  companion object {
    /** Changing these needs the model to be loaded again; the rest applies to the next session. */
    val RELOAD_KEYS =
      listOf(
        ConfigKeys.ACCELERATOR.label,
        ConfigKeys.VISION_ACCELERATOR.label,
        ConfigKeys.MAX_TOKENS.label,
        ConfigKeys.ENABLE_SPECULATIVE_DECODING.label,
      )
  }
}
