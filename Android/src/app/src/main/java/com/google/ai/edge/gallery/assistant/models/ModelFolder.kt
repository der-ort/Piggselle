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
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.LlmConfig
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "PiggselleModelFolder"
private const val PREFS = "piggselle_model_folder"
private const val KEY_DIR = "shared_dir"
const val MANIFEST_FILE = "piggselle-models.json"

/** Where model files live, as seen by the rest of the app. */
sealed interface ModelFolderState {
  /** App-private storage: deleted when the app is uninstalled. */
  data object Private : ModelFolderState

  /** A shared folder that is readable and writable. */
  data class Shared(val dir: File) : ModelFolderState

  /** A shared folder is configured but can't be used right now (e.g. access was removed). */
  data class Unavailable(val dir: File, val reason: String) : ModelFolderState
}

/** One model in the shared folder's manifest, so it survives a reinstall with its settings. */
@Serializable
data class SharedModelEntry(
  val fileName: String,
  val fileSize: Long,
  val url: String = "",
  val accelerators: List<String> = listOf("GPU", "CPU"),
  val maxTokens: Int = 4096,
  val topK: Int = 64,
  val topP: Float = 0.95f,
  val temperature: Float = 1.0f,
  val image: Boolean = false,
  val audio: Boolean = false,
  val thinking: Boolean = false,
  val speculativeDecoding: Boolean = false,
)

/**
 * The optional shared models folder. Models (chat models, Whisper and the embedding model) are
 * stored there instead of in app-private storage, so they survive reinstalls and other apps can
 * import them. Needs "All files access", which GrapheneOS can scope to just this folder (Storage
 * Scopes).
 */
object ModelFolder {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

  fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

  fun allFilesAccessIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
      .setData(Uri.parse("package:${context.packageName}"))

  fun defaultSharedDir(): File = File(Environment.getExternalStorageDirectory(), "Models")

  fun configuredDir(context: Context): File? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)?.takeIf { it.isNotBlank() }?.let(::File)

  fun state(context: Context): ModelFolderState {
    val dir = configuredDir(context) ?: return ModelFolderState.Private
    if (!hasAllFilesAccess()) return ModelFolderState.Unavailable(dir, "Piggselle no longer has file access.")
    if (!dir.exists() && !dir.mkdirs()) return ModelFolderState.Unavailable(dir, "The folder can't be created.")
    if (!dir.canRead() || !dir.canWrite()) return ModelFolderState.Unavailable(dir, "The folder isn't readable and writable.")
    return ModelFolderState.Shared(dir)
  }

  @Volatile private var cached: Pair<String?, File?>? = null

  /**
   * The shared folder if it is usable, else null (callers fall back to app storage). Checked once
   * per process: the app restarts when the folder changes.
   */
  fun sharedDirOrNull(context: Context): File? {
    val configured = configuredDir(context)?.absolutePath
    cached?.let { (path, dir) -> if (path == configured) return dir }
    val dir = (state(context) as? ModelFolderState.Shared)?.dir
    cached = configured to dir
    return dir
  }

  fun setConfiguredDir(context: Context, dir: File?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DIR, dir?.absolutePath).commit()
    cached = null
  }

  /** Converts a folder picked in the system picker to a file path (primary storage only). */
  fun pathFromTreeUri(uri: Uri): File? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2 || parts[0] != "primary") return null
    return File(Environment.getExternalStorageDirectory(), parts[1])
  }

  /** Directory for Piggselle's own helper models (Whisper, embeddings). */
  fun helperDir(context: Context, name: String): File =
    (sharedDirOrNull(context)?.let { File(it, name) } ?: File(context.filesDir, name)).apply { mkdirs() }

  // ---- Moving -----------------------------------------------------------------------------------

  /** Model locations under one root: the chat-model root plus the helper-model folders. */
  data class Roots(val models: File, val whisper: File, val embedder: File)

  fun privateRoots(context: Context) =
    Roots(
      models = context.getExternalFilesDir(null) ?: context.filesDir,
      whisper = File(context.filesDir, "whisper"),
      embedder = File(context.filesDir, "embedder"),
    )

  fun sharedRoots(dir: File) = Roots(models = dir, whisper = File(dir, "whisper"), embedder = File(dir, "embedder"))

  /** Total size of the model files that a move would transfer. */
  fun sizeOf(roots: Roots): Long = listOf(roots.models, roots.whisper, roots.embedder).distinct().sumOf { movableSize(it) }

  private fun movableSize(dir: File): Long =
    dir.listFiles()?.filter { isMovable(it) }?.sumOf { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length() } ?: 0L

  /** Skips files that are app bookkeeping rather than models. */
  private fun isMovable(f: File): Boolean =
    !f.name.endsWith(".json") && !f.name.endsWith(".tmp") && !f.name.endsWith(".part") &&
      f.name != "whisper" && f.name != "embedder" && f.name != "litert-cache"

  /**
   * Moves model files from [from] to [to], renaming where possible and copying otherwise.
   * Existing files of the same size at the destination are kept and the source is removed.
   */
  fun move(from: Roots, to: Roots, onProgress: (moved: Long, total: Long) -> Unit) {
    val total = sizeOf(from).coerceAtLeast(1)
    var moved = 0L
    fun moveTree(src: File, dst: File) {
      if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.forEach { moveTree(it, File(dst, it.name)) }
        src.delete()
        return
      }
      val len = src.length()
      if (dst.exists() && dst.length() == len) {
        src.delete()
      } else if (!src.renameTo(dst)) {
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        src.inputStream().use { input ->
          tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 20)
            var copied = 0L
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)
              copied += n
              onProgress(moved + copied, total)
            }
            out.fd.sync()
          }
        }
        dst.delete()
        if (!tmp.renameTo(dst)) error("Could not move ${src.name}")
        src.delete()
      }
      moved += len
      onProgress(moved, total)
    }
    listOf(from.models to to.models, from.whisper to to.whisper, from.embedder to to.embedder).forEach { (src, dst) ->
      if (src.absolutePath == dst.absolutePath) return@forEach
      dst.mkdirs()
      src.listFiles()?.filter { isMovable(it) }?.forEach { moveTree(it, File(dst, it.name)) }
    }
    Log.i(TAG, "Moved $moved bytes")
  }

  // ---- Manifest and discovery -------------------------------------------------------------------

  private fun readManifest(dir: File): List<SharedModelEntry> =
    runCatching { json.decodeFromString(ListSerializer(SharedModelEntry.serializer()), File(dir, MANIFEST_FILE).readText()) }
      .getOrDefault(emptyList())

  /** Records the imported models in the shared folder (no-op without one). */
  fun writeManifest(context: Context, models: List<ImportedModel>) {
    val dir = sharedDirOrNull(context) ?: return
    runCatching {
        File(dir, MANIFEST_FILE).writeText(json.encodeToString(ListSerializer(SharedModelEntry.serializer()), models.map { it.toEntry() }))
      }
      .onFailure { Log.w(TAG, "Could not write the models manifest", it) }
  }

  /**
   * Returns [known] plus models found in the shared folder that the app doesn't know yet: first
   * from the manifest (keeps their settings), then any other .litertlm files in the folder or its
   * imports folder (e.g. copied there by hand or by another app). Only files that exist count.
   */
  fun discover(context: Context, known: List<ImportedModel>): List<ImportedModel> {
    val dir = sharedDirOrNull(context) ?: return known
    val names = known.mapTo(HashSet()) { it.fileName }
    val found = mutableListOf<ImportedModel>()
    fun locate(name: String): File? =
      listOf(File(dir, "$IMPORTS_DIR/$name"), File(dir, name)).firstOrNull { it.isFile && it.length() > 0 }
    for (entry in readManifest(dir)) {
      if (entry.fileName in names) continue
      if (entry.url.isEmpty() && locate(entry.fileName) == null) continue
      found += entry.toImported()
      names += entry.fileName
    }
    val candidates = (File(dir, IMPORTS_DIR).listFiles()?.toList().orEmpty() + dir.listFiles()?.toList().orEmpty()).filter {
      it.isFile && it.name.endsWith(".litertlm") && it.length() > 0
    }
    for (file in candidates) {
      if (file.name in names) continue
      found += guessEntry(file).toImported()
      names += file.name
    }
    if (found.isNotEmpty()) Log.i(TAG, "Found ${found.size} model(s) in the shared folder")
    return known + found
  }

  /** Conservative defaults for a model file found without settings. */
  private fun guessEntry(file: File): SharedModelEntry {
    val n = file.name.lowercase()
    val gemmaMultimodal = n.contains("gemma-3n") || n.contains("gemma3n") || Regex("gemma-?4").containsMatchIn(n)
    val smallGemma = n.contains("gemma-3n") || n.contains("gemma3n") || Regex("gemma-?4.*e[24]b").containsMatchIn(n)
    return SharedModelEntry(
      fileName = file.name,
      fileSize = file.length(),
      image = gemmaMultimodal,
      audio = smallGemma,
      thinking = Regex("gemma-?4|qwen3|thinking|reason|r1").containsMatchIn(n),
    )
  }

  private fun ImportedModel.toEntry() =
    SharedModelEntry(
      fileName = fileName,
      fileSize = fileSize,
      url = url,
      accelerators = llmConfig.compatibleAcceleratorsList.toList().ifEmpty { listOf("CPU") },
      maxTokens = llmConfig.defaultMaxTokens,
      topK = llmConfig.defaultTopk,
      topP = llmConfig.defaultTopp,
      temperature = llmConfig.defaultTemperature,
      image = llmConfig.supportImage,
      audio = llmConfig.supportAudio,
      thinking = llmConfig.supportThinking,
      speculativeDecoding = llmConfig.supportSpeculativeDecoding,
    )

  private fun SharedModelEntry.toImported(): ImportedModel =
    ImportedModel.newBuilder()
      .setFileName(fileName)
      .setFileSize(fileSize)
      .setUrl(url)
      .setLlmConfig(
        LlmConfig.newBuilder()
          .addAllCompatibleAccelerators(accelerators)
          .setDefaultMaxTokens(maxTokens)
          .setDefaultTopk(topK)
          .setDefaultTopp(topP)
          .setDefaultTemperature(temperature)
          .setSupportImage(image)
          .setSupportAudio(audio)
          .setSupportThinking(thinking)
          .setSupportSpeculativeDecoding(speculativeDecoding)
          .build()
      )
      .build()
}
