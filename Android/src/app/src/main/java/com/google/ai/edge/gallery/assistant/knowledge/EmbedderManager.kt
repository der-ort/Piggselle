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

package com.google.ai.edge.gallery.assistant.knowledge

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PGEmbedder"
private const val MODEL_FILE = "embeddinggemma-2-740m.litertlm"
private const val MODEL_URL =
  "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/" +
    "86b903a293f96d042bb92e1b7af333d94114458f/$MODEL_FILE?download=true"
const val EMBEDDER_SIZE_BYTES = 484_622_336L
const val EMBEDDING_DIM = 256

sealed interface EmbedderStatus {
  data object NotDownloaded : EmbedderStatus

  data class Downloading(val received: Long, val total: Long) : EmbedderStatus

  data object Ready : EmbedderStatus

  data class Failed(val message: String) : EmbedderStatus
}

/**
 * EmbeddingGemma 2 for semantic search over the knowledge base. Optional: keyword search works
 * without it. Downloaded through NetworkGate (purpose: model download) and run with LiteRT-LM.
 */
@Singleton
class EmbedderManager @Inject constructor(@ApplicationContext private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val dir = File(context.filesDir, "embedder").apply { mkdirs() }
  private val modelFile = File(dir, MODEL_FILE)
  private val partFile = File(dir, "$MODEL_FILE.part")
  private val engineLock = Mutex()
  private var engine: EmbeddingEngine? = null
  private var downloadJob: Job? = null

  private val _status =
    MutableStateFlow<EmbedderStatus>(
      if (modelFile.exists()) EmbedderStatus.Ready else EmbedderStatus.NotDownloaded
    )
  val status: StateFlow<EmbedderStatus> = _status.asStateFlow()

  val isReady: Boolean
    get() = _status.value is EmbedderStatus.Ready

  fun download() {
    if (downloadJob?.isActive == true || isReady) return
    downloadJob =
      scope.launch {
        try {
          val already = if (partFile.exists()) partFile.length() else 0L
          _status.value = EmbedderStatus.Downloading(already, EMBEDDER_SIZE_BYTES)
          val conn =
            NetworkGate.openFollowingRedirects(MODEL_URL, NetPurpose.MODEL_DOWNLOAD) { c, _ ->
              c.connectTimeout = 20_000
              c.readTimeout = 30_000
              if (already > 0) {
                c.setRequestProperty("Range", "bytes=$already-")
                c.setRequestProperty("Accept-Encoding", "identity")
              }
            }
          val resumed = conn.responseCode == 206
          if (conn.responseCode != 200 && !resumed) error("HTTP ${conn.responseCode}")
          var received = if (resumed) already else 0L
          conn.inputStream.use { input ->
            FileOutputStream(partFile, resumed).use { out ->
              val buf = ByteArray(256 * 1024)
              var lastReport = 0L
              while (isActive) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                received += n
                if (received - lastReport > 2_000_000) {
                  lastReport = received
                  _status.value = EmbedderStatus.Downloading(received, EMBEDDER_SIZE_BYTES)
                }
              }
            }
          }
          conn.disconnect()
          if (partFile.length() != EMBEDDER_SIZE_BYTES) {
            error("Incomplete download (${partFile.length()} of $EMBEDDER_SIZE_BYTES bytes)")
          }
          if (!partFile.renameTo(modelFile)) error("Could not save the model file")
          _status.value = EmbedderStatus.Ready
        } catch (e: Exception) {
          Log.w(TAG, "Embedder download failed", e)
          _status.value = EmbedderStatus.Failed(e.message ?: "Download failed")
        }
      }
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    _status.value = EmbedderStatus.NotDownloaded
  }

  suspend fun delete() {
    engineLock.withLock {
      engine?.close()
      engine = null
    }
    modelFile.delete()
    partFile.delete()
    _status.value = EmbedderStatus.NotDownloaded
  }

  /** Releases the engine (about 0.5 GB of memory). It is re-created on the next call. */
  suspend fun release() {
    engineLock.withLock {
      engine?.close()
      engine = null
    }
  }

  private fun createEngine(): EmbeddingEngine {
    val cache = File(context.cacheDir, "embedder").apply { mkdirs() }.absolutePath
    return try {
      EmbeddingEngine(
          EmbeddingEngineConfig(modelPath = modelFile.absolutePath, backend = Backend.GPU(), cacheDir = cache)
        )
        .also { it.initialize() }
    } catch (e: Exception) {
      Log.w(TAG, "GPU embedder failed, using CPU: ${e.message}")
      EmbeddingEngine(
          EmbeddingEngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(threadCount = 4),
            cacheDir = cache,
          )
        )
        .also { it.initialize() }
    }
  }

  /**
   * Embeds [texts]. Queries and documents use EmbeddingGemma's task prefixes. Returns null if the
   * model is not downloaded or fails.
   */
  suspend fun embed(texts: List<String>, isQuery: Boolean, titles: List<String>? = null): List<FloatArray>? {
    if (!isReady || texts.isEmpty()) return null
    return engineLock.withLock {
      try {
        val e = engine ?: createEngine().also { engine = it }
        val inputs =
          texts.mapIndexed { i, t ->
            val text =
              if (isQuery) "task: search result | query: $t"
              else "title: ${titles?.getOrNull(i)?.ifBlank { null } ?: "none"} | text: $t"
            listOf<InputData>(InputData.Text(text))
          }
        e.computeEmbeddingBatch(inputs, EmbeddingOptions(normalize = true, outputSize = EMBEDDING_DIM))
          .map { it.embedding }
      } catch (ex: Exception) {
        Log.e(TAG, "Embedding failed", ex)
        null
      }
    }
  }
}
