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

package com.google.ai.edge.gallery.assistant.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Log
import java.io.ByteArrayOutputStream
import com.google.ai.edge.gallery.assistant.storage.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val INDEX_FILE = "chats_index.bin"

private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

private fun chatFile(id: String) = "chat_${safe(id)}.bin"

private fun imageFile(chatId: String, messageId: String, index: Int) = "img_${safe(chatId)}_${safe(messageId)}_$index.bin"

@Serializable
data class ChatFolder(
  val id: String,
  val name: String,
  /** Default system prompt for chats in this folder. Blank: use the persona's. */
  val systemPrompt: String = "",
  /** Knowledge base default for chats in this folder. Null: use the global default. */
  val useKnowledge: Boolean? = null,
  /** Sampling temperature for chats in this folder. Null: the model's setting. */
  val temperature: Float? = null,
  /** Thinking mode for chats in this folder (models that support it). Null: the model's setting. */
  val thinking: Boolean? = null,
  val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatMeta(
  val id: String,
  val title: String,
  val folderId: String? = null,
  val createdAtMs: Long,
  val updatedAtMs: Long,
  /** Per-chat system prompt. Blank: folder's, then persona's. */
  val systemPrompt: String = "",
  /** Null: folder default, then global default. */
  val useKnowledge: Boolean? = null,
  /** Private chats live in memory only and never touch memory. Never persisted. */
  val isPrivate: Boolean = false,
)

enum class Role {
  USER,
  ASSISTANT,
}

@Serializable
data class StoredMessage(
  val id: String,
  val role: Role,
  val text: String,
  val timeMs: Long,
  /** Knowledge base files used for this answer. */
  val sources: List<String> = emptyList(),
  /** Number of images attached (images themselves are not stored). */
  val imageCount: Int = 0,
  val error: Boolean = false,
)

@Serializable
private data class ChatIndex(
  val folders: List<ChatFolder> = emptyList(),
  val chats: List<ChatMeta> = emptyList(),
)

/**
 * Chats and folders. Persisted chats are encrypted (index + one file per chat); private chats are
 * kept only in this process and vanish when it ends or when the user leaves them.
 */
@Singleton
class ChatStore @Inject constructor(@ApplicationContext private val context: Context) {
  private val writer = Executors.newSingleThreadExecutor()
  private val messageCache = ConcurrentHashMap<String, List<StoredMessage>>()
  /** Images of private chats, which are never written. Keyed by message id. */
  private val privateImages = ConcurrentHashMap<String, List<Bitmap>>()
  /** Recently decrypted images of saved chats, bounded by bitmap memory. */
  private val decodedImages =
    object : LruCache<String, List<Bitmap>>(48 * 1024 * 1024) {
      override fun sizeOf(key: String, value: List<Bitmap>) = value.sumOf { it.allocationByteCount }.coerceAtLeast(1)
    }

  private fun forgetImages(messageId: String) {
    privateImages.remove(messageId)
    decodedImages.remove(messageId)
  }

  private val _folders = MutableStateFlow<List<ChatFolder>>(emptyList())
  val folders: StateFlow<List<ChatFolder>> = _folders.asStateFlow()

  private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
  /** All chats, newest first, including private ones. */
  val chats: StateFlow<List<ChatMeta>> = _chats.asStateFlow()

  init {
    val index = SecureStore.read(context, INDEX_FILE, ChatIndex.serializer()) ?: ChatIndex()
    _folders.value = index.folders
    _chats.value = index.chats.sortedByDescending { it.updatedAtMs }
  }

  private fun persistIndex() {
    val snapshot = ChatIndex(_folders.value, _chats.value.filterNot { it.isPrivate })
    writer.execute { SecureStore.write(context, INDEX_FILE, ChatIndex.serializer(), snapshot) }
  }

  fun chat(id: String?): ChatMeta? = id?.let { i -> _chats.value.firstOrNull { it.id == i } }

  fun folder(id: String?): ChatFolder? = id?.let { i -> _folders.value.firstOrNull { it.id == i } }

  // ---- Chats ----------------------------------------------------------------------------------

  fun createChat(title: String, folderId: String?, isPrivate: Boolean): ChatMeta {
    val now = System.currentTimeMillis()
    val meta =
      ChatMeta(
        id = UUID.randomUUID().toString(),
        title = title.ifBlank { "New chat" },
        folderId = if (isPrivate) null else folderId,
        createdAtMs = now,
        updatedAtMs = now,
        isPrivate = isPrivate,
      )
    _chats.update { listOf(meta) + it }
    messageCache[meta.id] = emptyList()
    if (!isPrivate) persistIndex()
    return meta
  }

  fun updateChat(id: String, block: (ChatMeta) -> ChatMeta) {
    _chats.update { list -> list.map { if (it.id == id) block(it) else it } }
    if (chat(id)?.isPrivate == false) persistIndex()
  }

  fun renameChat(id: String, title: String) = updateChat(id) { it.copy(title = title.trim().ifBlank { it.title }) }

  fun moveChat(id: String, folderId: String?) = updateChat(id) { if (it.isPrivate) it else it.copy(folderId = folderId) }

  fun deleteChat(id: String) {
    val wasPrivate = chat(id)?.isPrivate ?: true
    _chats.update { list -> list.filterNot { it.id == id } }
    messages(id).forEach { forgetImages(it.id) }
    messageCache.remove(id)
    if (!wasPrivate) {
      persistIndex()
      writer.execute {
        SecureStore.delete(context, chatFile(id))
        val prefix = "img_${safe(id)}_"
        SecureStore.dir(context).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
      }
    }
  }

  /** Removes all private chats (called when leaving one). */
  fun dropPrivateChats() {
    val private = _chats.value.filter { it.isPrivate }.map { it.id }
    if (private.isEmpty()) return
    _chats.update { list -> list.filterNot { it.isPrivate } }
    private.forEach { id ->
      messageCache[id]?.forEach { forgetImages(it.id) }
      messageCache.remove(id)
    }
  }

  fun messages(id: String): List<StoredMessage> {
    messageCache[id]?.let {
      return it
    }
    val meta = chat(id) ?: return emptyList()
    if (meta.isPrivate) return emptyList()
    val loaded = SecureStore.read(context, chatFile(id), ListSerializer(StoredMessage.serializer())) ?: emptyList()
    messageCache[id] = loaded
    return loaded
  }

  fun setMessages(id: String, messages: List<StoredMessage>) {
    val kept = messages.mapTo(HashSet()) { it.id }
    val dropped = messageCache[id].orEmpty().filter { it.id !in kept && it.imageCount > 0 }
    messageCache[id] = messages
    val meta = chat(id) ?: return
    _chats.update { list ->
      (list.map { if (it.id == id) it.copy(updatedAtMs = System.currentTimeMillis()) else it })
        .sortedByDescending { it.updatedAtMs }
    }
    if (!meta.isPrivate) {
      persistIndex()
      val snapshot = messages.toList()
      writer.execute {
        SecureStore.write(context, chatFile(id), ListSerializer(StoredMessage.serializer()), snapshot)
        // Images of removed messages (e.g. on regenerate) go with them.
        dropped.forEach { m -> repeat(m.imageCount) { i -> SecureStore.delete(context, imageFile(id, m.id, i)) } }
      }
    }
    dropped.forEach { forgetImages(it.id) }
  }

  fun newMessageId(): String = UUID.randomUUID().toString()

  // ---- Images ---------------------------------------------------------------------------------

  /** Keeps a message's images: encrypted on disk for normal chats, in memory for private ones. */
  fun saveImages(chatId: String, messageId: String, images: List<Bitmap>) {
    if (images.isEmpty()) return
    val copy = images.toList()
    if (chat(chatId)?.isPrivate != false) {
      privateImages[messageId] = copy
      return
    }
    decodedImages.put(messageId, copy)
    writer.execute {
      copy.forEachIndexed { i, bmp ->
        runCatching {
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            SecureStore.writeBytes(SecureStore.file(context, imageFile(chatId, messageId, i)), bytes)
          }
          .onFailure { Log.e("ChatStore", "Could not save image", it) }
      }
    }
  }

  /** Loads a message's images (blocking; call off the main thread). */
  fun loadImages(chatId: String, messageId: String, count: Int): List<Bitmap> {
    privateImages[messageId]?.let {
      return it
    }
    decodedImages.get(messageId)?.let {
      return it
    }
    val loaded =
      (0 until count).mapNotNull { i ->
        runCatching {
            SecureStore.readBytes(SecureStore.file(context, imageFile(chatId, messageId, i)))?.let {
              BitmapFactory.decodeByteArray(it, 0, it.size)
            }
          }
          .getOrNull()
      }
    if (loaded.isNotEmpty()) decodedImages.put(messageId, loaded)
    return loaded
  }

  // ---- Folders --------------------------------------------------------------------------------

  fun createFolder(name: String): ChatFolder {
    val f = ChatFolder(id = UUID.randomUUID().toString(), name = name.trim().ifBlank { "Folder" })
    _folders.update { it + f }
    persistIndex()
    return f
  }

  fun updateFolder(folder: ChatFolder) {
    _folders.update { list -> list.map { if (it.id == folder.id) folder else it } }
    persistIndex()
  }

  /** Deletes the folder; its chats move to the top level. */
  fun deleteFolder(id: String) {
    _folders.update { list -> list.filterNot { it.id == id } }
    _chats.update { list -> list.map { if (it.folderId == id) it.copy(folderId = null) else it } }
    persistIndex()
  }
}
