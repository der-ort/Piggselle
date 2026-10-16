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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val VOICE_SAMPLE_RATE = 16_000
private const val MAX_SECONDS = 300

/** A finished recording: PCM16 mono at 16 kHz. Kept in memory only. */
class Recording(val pcm16: ByteArray) {
  val seconds: Float
    get() = pcm16.size / 2f / VOICE_SAMPLE_RATE

  /** Samples in [-1, 1] for Whisper. */
  fun floatSamples(): FloatArray {
    val buf = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(buf.remaining()) { buf.get(it) / 32768f }
  }

  /** WAV container for models that take audio input directly. */
  fun wav(): ByteArray {
    val dataSize = pcm16.size
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    header.putInt(VOICE_SAMPLE_RATE).putInt(VOICE_SAMPLE_RATE * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(dataSize)
    return header.array() + pcm16
  }
}

/** Microphone capture. Call [start] only after RECORD_AUDIO was granted. */
class VoiceRecorder(private val scope: CoroutineScope) {
  private var record: AudioRecord? = null
  private var job: Job? = null
  private val buffer = ByteArrayOutputStream()

  private val _level = MutableStateFlow(0f)
  /** Input level 0..1 for a simple meter. */
  val level: StateFlow<Float> = _level.asStateFlow()

  private val _seconds = MutableStateFlow(0f)
  val seconds: StateFlow<Float> = _seconds.asStateFlow()

  val isRecording: Boolean
    get() = job?.isActive == true

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (isRecording) return true
    val minBuf =
      AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return false
    val r =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          VOICE_SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minBuf * 4,
        )
      } catch (e: Exception) {
        return false
      }
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release()
      return false
    }
    buffer.reset()
    _seconds.value = 0f
    record = r
    r.startRecording()
    job =
      scope.launch(Dispatchers.IO) {
        val chunk = ByteArray(minBuf)
        while (isActive) {
          val n = r.read(chunk, 0, chunk.size)
          if (n <= 0) continue
          buffer.write(chunk, 0, n)
          var peak = 0
          var i = 0
          while (i + 1 < n) {
            val s = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(s.toShort().toInt()))
            i += 2
          }
          _level.value = peak / 32768f
          _seconds.value = buffer.size() / 2f / VOICE_SAMPLE_RATE
          if (_seconds.value >= MAX_SECONDS) break
        }
      }
    return true
  }

  /** Stops and returns the recording (null if nothing was captured). */
  suspend fun stop(): Recording? {
    val r = record
    // Stopping the recorder makes the pending read() return, then wait for the loop to end so the
    // buffer is complete.
    try {
      r?.stop()
    } catch (_: Exception) {}
    job?.cancelAndJoin()
    job = null
    r?.release()
    record = null
    _level.value = 0f
    val bytes = buffer.toByteArray()
    buffer.reset()
    return if (bytes.size < VOICE_SAMPLE_RATE / 5) null else Recording(bytes)
  }

  suspend fun cancel() {
    stop()
  }
}
