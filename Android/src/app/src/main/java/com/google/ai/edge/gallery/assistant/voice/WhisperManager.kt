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

package com.google.ai.edge.gallery.assistant.voice

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.assistant.net.NetPurpose
import com.google.ai.edge.gallery.assistant.net.NetworkGate
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
import kotlinx.coroutines.withContext

private const val TAG = "PGWhisper"
private const val PREFS = "piggselle_voice"

/** Whisper sizes, as int8 ONNX exports for sherpa-onnx on Hugging Face (csukuangfj/sherpa-onnx-whisper-*). */
enum class WhisperSize(val id: String, val label: String, val approxMb: Int, val note: String) {
  TINY("tiny", "Tiny", 105, "Fastest, rough"),
  BASE("base", "Base", 160, "Fast, fine for English"),
  SMALL("small", "Small", 375, "Good German and English (recommended)"),
  TURBO("turbo", "Large v3 Turbo", 1040, "Best quality, slower"),
  ;

  val files: List<String>
    get() = listOf("$id-encoder.int8.onnx", "$id-decoder.int8.onnx", "$id-tokens.txt")

  fun url(file: String) = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main/$file?download=true"
}

enum class WhisperLanguage(val code: String, val label: String) {
  AUTO("", "Detect automatically"),
  GERMAN("de", "German"),
  ENGLISH("en", "English"),
}

sealed interface WhisperStatus {
  data object NotDownloaded : WhisperStatus

  data class Downloading(val size: WhisperSize, val fileIndex: Int, val received: Long, val total: Long) : WhisperStatus

  data class Ready(val size: WhisperSize) : WhisperStatus

  data class Failed(val message: String) : WhisperStatus
}

/**
 * Offline speech-to-text with Whisper via sherpa-onnx. The user picks the size; models are
 * downloaded through NetworkGate (purpose: model download) and audio never leaves the phone.
 */
@Singleton
class WhisperManager @Inject constructor(@ApplicationContext private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val root = com.google.ai.edge.gallery.assistant.models.ModelFolder.helperDir(context, "whisper")
  private val lock = Mutex()
  private var recognizer: OfflineRecognizer? = null
  private var recognizerKey: String? = null
  private var job: Job? = null

  private val _size = MutableStateFlow(WhisperSize.entries.firstOrNull { it.id == prefs.getString("size", null) } ?: WhisperSize.SMALL)
  val size: StateFlow<WhisperSize> = _size.asStateFlow()

  private val _language = MutableStateFlow(WhisperLanguage.entries.firstOrNull { it.code == prefs.getString("language", "") } ?: WhisperLanguage.AUTO)
  val language: StateFlow<WhisperLanguage> = _language.asStateFlow()

  private val _status = MutableStateFlow<WhisperStatus>(WhisperStatus.NotDownloaded)
  val status: StateFlow<WhisperStatus> = _status.asStateFlow()

  init {
    refreshStatus()
  }

  private fun dir(size: WhisperSize) = File(root, size.id)

  fun isDownloaded(size: WhisperSize): Boolean = size.files.all { File(dir(size), it).let { f -> f.exists() && f.length() > 0 } }

  fun downloadedSizes(): List<WhisperSize> = WhisperSize.entries.filter { isDownloaded(it) }

  private fun refreshStatus() {
    if (_status.value is WhisperStatus.Downloading) return
    _status.value = if (isDownloaded(_size.value)) WhisperStatus.Ready(_size.value) else WhisperStatus.NotDownloaded
  }

  val isReady: Boolean
    get() = _status.value is WhisperStatus.Ready

  fun select(size: WhisperSize) {
    _size.value = size
    prefs.edit().putString("size", size.id).apply()
    refreshStatus()
  }

  fun setLanguage(language: WhisperLanguage) {
    _language.value = language
    prefs.edit().putString("language", language.code).apply()
  }

  fun download(size: WhisperSize = _size.value) {
    if (job?.isActive == true) return
    job =
      scope.launch {
        val target = dir(size).apply { mkdirs() }
        try {
          size.files.forEachIndexed { i, name ->
            val out = File(target, name)
            if (out.exists() && out.length() > 0) return@forEachIndexed
            val part = File(target, "$name.part")
            val already = if (part.exists()) part.length() else 0L
            val conn =
              NetworkGate.openFollowingRedirects(size.url(name), NetPurpose.MODEL_DOWNLOAD) { c, _ ->
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                if (already > 0) {
                  c.setRequestProperty("Range", "bytes=$already-")
                  c.setRequestProperty("Accept-Encoding", "identity")
                }
              }
            val resumed = conn.responseCode == 206
            if (conn.responseCode != 200 && !resumed) error("HTTP ${conn.responseCode} for $name")
            val total = (if (resumed) already else 0L) + conn.contentLengthLong.coerceAtLeast(0)
            var received = if (resumed) already else 0L
            conn.inputStream.use { input ->
              FileOutputStream(part, resumed).use { o ->
                val buf = ByteArray(256 * 1024)
                var last = 0L
                while (isActive) {
                  val n = input.read(buf)
                  if (n < 0) break
                  o.write(buf, 0, n)
                  received += n
                  if (received - last > 1_000_000) {
                    last = received
                    _status.value = WhisperStatus.Downloading(size, i, received, total)
                  }
                }
              }
            }
            conn.disconnect()
            if (total > 0 && part.length() != total) error("Incomplete download of $name")
            if (!part.renameTo(out)) error("Could not save $name")
          }
          _status.value = if (size == _size.value) WhisperStatus.Ready(size) else WhisperStatus.NotDownloaded
          refreshStatus()
        } catch (e: Exception) {
          Log.w(TAG, "Whisper download failed", e)
          _status.value = WhisperStatus.Failed(e.message ?: "Download failed")
        }
      }
  }

  fun cancelDownload() {
    job?.cancel()
    _status.value = WhisperStatus.NotDownloaded
    refreshStatus()
  }

  suspend fun delete(size: WhisperSize) {
    lock.withLock {
      if (recognizerKey?.startsWith(size.id) == true) {
        recognizer?.release()
        recognizer = null
        recognizerKey = null
      }
    }
    dir(size).deleteRecursively()
    refreshStatus()
  }

  /** Frees the recognizer's memory. */
  suspend fun release() {
    lock.withLock {
      recognizer?.release()
      recognizer = null
      recognizerKey = null
    }
  }

  /** Transcribes 16 kHz mono samples in [-1, 1]. Returns null if no model is ready. */
  suspend fun transcribe(samples: FloatArray): String? =
    withContext(Dispatchers.Default) {
      val size = _size.value
      if (!isDownloaded(size) || samples.isEmpty()) return@withContext null
      lock.withLock {
        try {
          val lang = _language.value.code
          val key = "${size.id}|$lang"
          if (recognizer == null || recognizerKey != key) {
            recognizer?.release()
            val d = dir(size)
            val config =
              OfflineRecognizerConfig(
                modelConfig =
                  OfflineModelConfig(
                    whisper =
                      OfflineWhisperModelConfig(
                        encoder = File(d, size.files[0]).absolutePath,
                        decoder = File(d, size.files[1]).absolutePath,
                        language = lang,
                        task = "transcribe",
                      ),
                    tokens = File(d, size.files[2]).absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                  )
              )
            recognizer = OfflineRecognizer(config = config)
            recognizerKey = key
          }
          val r = recognizer!!
          val stream = r.createStream()
          try {
            stream.acceptWaveform(samples, 16_000)
            r.decode(stream)
            r.getResult(stream).text.trim()
          } finally {
            stream.release()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Transcription failed", e)
          null
        }
      }
    }
}
