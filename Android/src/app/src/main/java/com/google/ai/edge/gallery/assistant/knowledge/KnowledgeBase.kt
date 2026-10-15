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
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.assistant.storage.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "PGKnowledge"
private const val SETTINGS_FILE = "kb_settings.bin"
private const val INDEX_FILE = "kb_index.bin"
private const val MAX_FILE_BYTES = 1_500_000
private const val MAX_FILES = 5_000
private val TEXT_EXTENSIONS =
  setOf("md", "markdown", "txt", "text", "org", "csv", "tsv", "json", "yaml", "yml", "html", "htm", "rst", "tex", "log")
private val SKIPPED_DIRS = setOf(".obsidian", ".git", ".trash", ".stfolder", ".stversions", "node_modules")

@Serializable
data class KbSettings(
  val folderUri: String = "",
  val folderName: String = "",
  /** Default for new chats; each chat (or folder) can override. */
  val onForNewChats: Boolean = true,
  /** Use EmbeddingGemma (if downloaded) in addition to keyword search. */
  val semantic: Boolean = true,
  val lastIndexedMs: Long = 0,
)

@Serializable
private data class KbDoc(val key: String, val path: String, val lastModified: Long, val size: Long)

@Serializable
private data class KbStoredIndex(
  val docs: List<KbDoc> = emptyList(),
  val chunks: List<KbChunk> = emptyList(),
  /** chunk id -> base64 little-endian float32 vector. */
  val vectors: Map<String, String> = emptyMap(),
)

data class KbState(
  val settings: KbSettings = KbSettings(),
  val docCount: Int = 0,
  val chunkCount: Int = 0,
  val vectorCount: Int = 0,
  val indexing: Boolean = false,
  val progress: String = "",
  val error: String = "",
) {
  val configured: Boolean
    get() = settings.folderUri.isNotBlank()
}

/**
 * On-device knowledge base over a folder the user picked (Storage Access Framework, read-only).
 * Text files are chunked and indexed with BM25; with EmbeddingGemma downloaded, chunks are also
 * embedded and results are fused. The index is encrypted at rest and nothing leaves the phone.
 */
@Singleton
class KnowledgeBase
@Inject
constructor(@ApplicationContext private val context: Context, private val embedder: EmbedderManager) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Mutex()
  private var job: Job? = null

  private var stored = KbStoredIndex()
  private var bm25 = Bm25Index(emptyList())
  private var vectors: Map<String, FloatArray> = emptyMap()

  private val _state = MutableStateFlow(KbState())
  val state: StateFlow<KbState> = _state.asStateFlow()

  init {
    scope.launch {
      val settings = SecureStore.read(context, SETTINGS_FILE, KbSettings.serializer()) ?: KbSettings()
      val index = SecureStore.read(context, INDEX_FILE, KbStoredIndex.serializer()) ?: KbStoredIndex()
      lock.withLock { applyIndex(index) }
      _state.update { it.copy(settings = settings) }
      publishCounts()
      // Pick up edits made since last time.
      if (settings.folderUri.isNotBlank()) reindex()
    }
  }

  private fun applyIndex(index: KbStoredIndex) {
    stored = index
    bm25 = Bm25Index(index.chunks)
    vectors = index.vectors.mapValues { (_, v) -> decodeVector(v) }
  }

  private fun publishCounts() {
    _state.update {
      it.copy(docCount = stored.docs.size, chunkCount = stored.chunks.size, vectorCount = vectors.size)
    }
  }

  private fun saveSettings(s: KbSettings) {
    _state.update { it.copy(settings = s) }
    SecureStore.write(context, SETTINGS_FILE, KbSettings.serializer(), s)
  }

  // ---- Settings -------------------------------------------------------------------------------

  fun setFolder(uri: Uri) {
    try {
      context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
      Log.w(TAG, "Could not persist folder permission", e)
    }
    val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Folder"
    scope.launch {
      lock.withLock { applyIndex(KbStoredIndex()) }
      SecureStore.delete(context, INDEX_FILE)
      publishCounts()
      saveSettings(_state.value.settings.copy(folderUri = uri.toString(), folderName = name, lastIndexedMs = 0))
      reindex()
    }
  }

  fun removeFolder() {
    job?.cancel()
    val old = _state.value.settings.folderUri
    scope.launch {
      if (old.isNotBlank()) {
        try {
          context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
      }
      lock.withLock { applyIndex(KbStoredIndex()) }
      SecureStore.delete(context, INDEX_FILE)
      saveSettings(KbSettings(onForNewChats = _state.value.settings.onForNewChats, semantic = _state.value.settings.semantic))
      publishCounts()
      _state.update { it.copy(indexing = false, progress = "", error = "") }
    }
  }

  fun setOnForNewChats(on: Boolean) = saveSettings(_state.value.settings.copy(onForNewChats = on))

  fun setSemantic(on: Boolean) {
    saveSettings(_state.value.settings.copy(semantic = on))
    if (on) reindex() else scope.launch { embedder.release() }
  }

  // ---- Indexing -------------------------------------------------------------------------------

  fun reindex() {
    val settings = _state.value.settings
    if (settings.folderUri.isBlank()) return
    if (job?.isActive == true) return
    job =
      scope.launch {
        _state.update { it.copy(indexing = true, progress = "Scanning folder…", error = "") }
        try {
          val treeUri = Uri.parse(settings.folderUri)
          val files = listFiles(treeUri)
          val previous = stored.docs.associateBy { it.key }
          val oldChunksByDoc = stored.chunks.groupBy { it.docKey }
          val newDocs = mutableListOf<KbDoc>()
          val newChunks = mutableListOf<KbChunk>()
          val keptVectors = HashMap<String, String>()
          var changed = 0
          files.forEachIndexed { i, f ->
            if (!isActive) return@launch
            val prev = previous[f.key]
            if (prev != null && prev.lastModified == f.lastModified && prev.size == f.size) {
              newDocs.add(prev)
              val chunks = oldChunksByDoc[f.key].orEmpty()
              newChunks.addAll(chunks)
              for (c in chunks) stored.vectors[c.id]?.let { keptVectors[c.id] = it }
            } else {
              changed++
              if (i % 10 == 0) _state.update { it.copy(progress = "Reading ${i + 1} of ${files.size}…") }
              val text = readText(f.uri) ?: return@forEachIndexed
              val pieces = TextChunker.chunk(TextChunker.clean(text, f.path))
              pieces.forEachIndexed { n, p -> newChunks.add(KbChunk("${f.key}#$n#${f.lastModified}", f.key, f.path, p)) }
              newDocs.add(KbDoc(f.key, f.path, f.lastModified, f.size))
            }
          }

          // Embed chunks that don't have a vector yet.
          if (settings.semantic && embedder.isReady) {
            val missing = newChunks.filter { it.id !in keptVectors }
            missing.chunked(8).forEachIndexed { b, batch ->
              if (!isActive) return@launch
              _state.update {
                it.copy(progress = "Embedding ${minOf((b + 1) * 8, missing.size)} of ${missing.size}…")
              }
              val vecs = embedder.embed(batch.map { it.text }, isQuery = false, titles = batch.map { it.docPath })
              vecs?.forEachIndexed { k, v -> keptVectors[batch[k].id] = encodeVector(v) }
            }
          }

          val index = KbStoredIndex(docs = newDocs, chunks = newChunks, vectors = keptVectors)
          lock.withLock { applyIndex(index) }
          SecureStore.write(context, INDEX_FILE, KbStoredIndex.serializer(), index)
          saveSettings(_state.value.settings.copy(lastIndexedMs = System.currentTimeMillis()))
          publishCounts()
          _state.update {
            it.copy(
              indexing = false,
              progress = if (changed == 0) "Up to date" else "Indexed $changed changed file(s)",
            )
          }
        } catch (e: SecurityException) {
          _state.update { it.copy(indexing = false, error = "Lost access to the folder. Pick it again.") }
        } catch (e: Exception) {
          Log.e(TAG, "Indexing failed", e)
          _state.update { it.copy(indexing = false, error = e.message ?: "Indexing failed") }
        }
      }
  }

  private data class FileRef(val key: String, val uri: Uri, val path: String, val lastModified: Long, val size: Long)

  private fun listFiles(treeUri: Uri): List<FileRef> {
    val out = mutableListOf<FileRef>()
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val stack = ArrayDeque<Pair<String, String>>().apply { add(rootId to "") }
    val cols =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
      )
    while (stack.isNotEmpty() && out.size < MAX_FILES) {
      val (docId, prefix) = stack.removeLast()
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
      context.contentResolver.query(children, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0) ?: continue
          val name = c.getString(1) ?: continue
          val mime = c.getString(2) ?: ""
          val path = if (prefix.isEmpty()) name else "$prefix/$name"
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (name !in SKIPPED_DIRS && !name.startsWith(".")) stack.add(id to path)
            continue
          }
          val ext = name.substringAfterLast('.', "").lowercase()
          val size = if (c.isNull(4)) 0L else c.getLong(4)
          if (ext !in TEXT_EXTENSIONS || size > MAX_FILE_BYTES || name.startsWith(".")) continue
          val modified = if (c.isNull(3)) 0L else c.getLong(3)
          out.add(FileRef(id, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), path, modified, size))
        }
      }
    }
    return out.sortedBy { it.path }
  }

  private fun readText(uri: Uri): String? =
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes()
        if (bytes.size > MAX_FILE_BYTES) null else bytes.decodeToString()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not read $uri", e)
      null
    }

  // ---- Search ---------------------------------------------------------------------------------

  /** Returns up to [limit] passages relevant to [query]. Empty if nothing matches well. */
  suspend fun search(query: String, limit: Int = 4): List<KbHit> {
    val (index, vecs) = lock.withLock { bm25 to vectors }
    if (index.size == 0) return emptyList()
    val keyword = index.search(query, limit = 12)
    val settings = _state.value.settings
    val semantic =
      if (settings.semantic && vecs.isNotEmpty() && embedder.isReady) {
        val q = embedder.embed(listOf(query), isQuery = true)?.firstOrNull()
        if (q == null) emptyList()
        else {
          val byId = stored.chunks.associateBy { it.id }
          vecs.entries
            .map { (id, v) -> id to VectorMath.cosine(q, v) }
            .filter { it.second >= 0.35 }
            .sortedByDescending { it.second }
            .take(12)
            .mapNotNull { (id, s) -> byId[id]?.let { KbHit(it, s) } }
        }
      } else emptyList()

    if (semantic.isEmpty()) return keyword.take(limit)
    val fused = VectorMath.rrf(listOf(keyword.map { it.chunk.id }, semantic.map { it.chunk.id }))
    val all = (keyword + semantic).associateBy { it.chunk.id }
    return fused.entries
      .sortedByDescending { it.value }
      .take(limit)
      .mapNotNull { (id, score) -> all[id]?.copy(score = score) }
  }

  private fun encodeVector(v: FloatArray): String {
    val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    v.forEach { buf.putFloat(it) }
    return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
  }

  private fun decodeVector(s: String): FloatArray {
    val buf = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(buf.remaining() / 4) { buf.float }
  }
}

/** Formats retrieved passages as context for the model. */
object KnowledgePrompt {
  fun wrap(question: String, hits: List<KbHit>): String {
    if (hits.isEmpty()) return question
    val sb = StringBuilder()
    sb.appendLine("Excerpts from my notes that may be relevant (ignore them if they are not):")
    hits.forEachIndexed { i, h ->
      sb.appendLine("[${i + 1}] ${h.chunk.docPath}")
      sb.appendLine(h.chunk.text.trim().take(1200))
      sb.appendLine()
    }
    sb.appendLine("My message:")
    sb.append(question)
    return sb.toString()
  }
}
