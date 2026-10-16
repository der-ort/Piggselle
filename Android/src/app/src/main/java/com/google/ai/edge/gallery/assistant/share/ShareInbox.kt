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

package com.google.ai.edge.gallery.assistant.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import com.google.ai.edge.gallery.assistant.ui.chat.decodeScaled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PiggselleShare"
private const val MAX_TEXT_CHARS = 30_000
private const val MAX_IMAGES = 4

/** Something another app shared with Piggselle, waiting to be put into a new chat. */
data class SharedContent(val text: String, val images: List<Bitmap>, val skipped: Int)

/**
 * Receives "Share to Piggselle" from other apps. Text and links go into the message box, images
 * become attachments and text files are pasted in. Nothing is sent until the user taps send.
 */
object ShareInbox {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _pending = MutableStateFlow<SharedContent?>(null)
  val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

  fun take(): SharedContent? = _pending.value.also { _pending.value = null }

  /** Returns true if [intent] was a share and was taken. */
  fun accept(context: Context, intent: Intent?): Boolean {
    if (intent == null) return false
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
    val streams: List<Uri> =
      if (action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      } else {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      }
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
    val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
    // Read now, while the sending app's permission to the files is still valid.
    val resolver = context.applicationContext.contentResolver
    val app = context.applicationContext
    scope.launch {
      val images = mutableListOf<Bitmap>()
      val parts = mutableListOf<String>()
      var skipped = 0
      if (subject.isNotEmpty() && !sharedText.startsWith(subject)) parts += subject
      if (sharedText.isNotEmpty()) parts += sharedText
      for (uri in streams) {
        val type = runCatching { resolver.getType(uri) }.getOrNull() ?: intent.type.orEmpty()
        when {
          type.startsWith("image/") -> {
            val bmp = if (images.size < MAX_IMAGES) decodeScaled(app, uri) else null
            if (bmp != null) images.add(bmp) else skipped++
          }
          type.startsWith("text/") || type == "application/json" -> {
            val body =
              runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                .onFailure { Log.w(TAG, "Couldn't read a shared file", it) }
                .getOrNull()
            if (body == null) skipped++
            else {
              val name = displayName(app, uri) ?: "Shared file"
              val clipped = if (body.length > MAX_TEXT_CHARS) body.take(MAX_TEXT_CHARS) + "\n[…cut off]" else body
              parts += "$name:\n\n$clipped"
            }
          }
          else -> skipped++
        }
      }
      if (parts.isEmpty() && images.isEmpty()) {
        Log.i(TAG, "Nothing usable in the share")
        return@launch
      }
      _pending.value = SharedContent(parts.joinToString("\n\n"), images, skipped)
    }
    return true
  }

  private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
          if (c.moveToFirst()) c.getString(0) else null
        }
      }
      .getOrNull()
}
