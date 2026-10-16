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

package com.google.ai.edge.gallery.assistant.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.assistant.models.ModelFolder
import com.google.ai.edge.gallery.assistant.models.ModelFolderState
import com.google.ai.edge.gallery.assistant.ui.SectionTitle
import com.jakewharton.processphoenix.ProcessPhoenix
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

/** A planned switch of the models folder, waiting for the user to confirm moving files. */
private data class Switch(val target: File?, val bytes: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFolderSheet(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  // Re-read access when coming back from the system settings screen.
  var resumes by remember { mutableIntStateOf(0) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val hasAccess = remember(resumes) { ModelFolder.hasAllFilesAccess() }
  val state = remember(resumes) { ModelFolder.state(context) }
  var path by remember { mutableStateOf((ModelFolder.configuredDir(context) ?: ModelFolder.defaultSharedDir()).absolutePath) }
  var error by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf<Switch?>(null) }
  var progress by remember { mutableStateOf<Float?>(null) }

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
      if (uri == null) return@rememberLauncherForActivityResult
      val dir = ModelFolder.pathFromTreeUri(uri)
      if (dir == null) error = "Please pick a folder on the phone's internal storage." else { path = dir.absolutePath; error = null }
    }

  fun plan(target: File?) {
    error = null
    if (target != null) {
      if (!target.exists() && !target.mkdirs()) {
        error = "Can't create ${target.absolutePath}. Check that Piggselle has access to it."
        return
      }
      if (!target.canWrite()) {
        error = "Piggselle can't write to ${target.absolutePath}. With Storage Scopes, add this folder in Piggselle's Storage Scopes settings."
        return
      }
    }
    val from = currentRoots(context)
    scope.launch {
      val bytes = withContext(Dispatchers.IO) { ModelFolder.sizeOf(from) }
      pending = Switch(target, bytes)
    }
  }

  fun apply(switch: Switch, move: Boolean) {
    pending = null
    val from = currentRoots(context)
    val to = switch.target?.let { ModelFolder.sharedRoots(it) } ?: ModelFolder.privateRoots(context)
    scope.launch {
      if (move) {
        progress = 0f
        val result =
          withContext(Dispatchers.IO) {
            runCatching { ModelFolder.move(from, to) { done, total -> progress = done.toFloat() / total } }
          }
        result.exceptionOrNull()?.let {
          progress = null
          error = "Moving stopped: ${it.message}. Nothing was deleted that wasn't copied."
          return@launch
        }
      }
      ModelFolder.setConfiguredDir(context, switch.target)
      // Model paths are read at start-up, so restart cleanly.
      ProcessPhoenix.triggerRebirth(context)
    }
  }

  ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Models folder", style = MaterialTheme.typography.headlineSmall)
      Text(
        "By default, models live in Piggselle's private storage and are deleted when the app is " +
          "uninstalled. A shared folder keeps them across reinstalls, and other apps can import " +
          "them from there. Chat models, Whisper and the notes embedding model all move together.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      SectionTitle("Now")
      Text(
        when (val s = state) {
          ModelFolderState.Private -> "App storage (deleted on uninstall)"
          is ModelFolderState.Shared -> s.dir.absolutePath
          is ModelFolderState.Unavailable -> "${s.dir.absolutePath}: ${s.reason} Using app storage until that's fixed."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (state is ModelFolderState.Unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )

      progress?.let {
        SectionTitle("Moving models…")
        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        Text("Keep Piggselle open. It restarts when done.", style = MaterialTheme.typography.bodySmall)
        return@Column
      }

      SectionTitle("Shared folder")
      if (!hasAccess) {
        Text(
          "Piggselle needs file access for this. On GrapheneOS you can keep it narrow: in " +
            "Piggselle's app info, enable Storage Scopes and add only your models folder. " +
            "Otherwise allow \"All files access\".",
          style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { runCatching { context.startActivity(ModelFolder.allFilesAccessIntent(context)) } }) { Text("Open access settings") }
      } else {
        Text(path, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { runCatching { picker.launch(null) } }) { Text("Choose another folder") }
        Button(
          enabled = (state as? ModelFolderState.Shared)?.dir?.absolutePath != path,
          onClick = { plan(File(path)) },
        ) {
          Text("Use this folder")
        }
      }
      if (state !is ModelFolderState.Private) {
        TextButton(onClick = { plan(null) }) { Text("Go back to app storage") }
      }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
  }

  pending?.let { sw ->
    val where = sw.target?.absolutePath ?: "app storage"
    AlertDialog(
      onDismissRequest = { pending = null },
      title = { Text("Move models?") },
      text = {
        Text(
          if (sw.bytes > 0) "Move ${gb(sw.bytes)} of models to $where? Piggselle restarts afterwards. " +
            "If you don't move them, they stay where they are and Piggselle won't see them."
          else "Piggselle restarts and uses $where. Models already there are picked up."
        )
      },
      confirmButton = { Button(onClick = { apply(sw, move = sw.bytes > 0) }) { Text(if (sw.bytes > 0) "Move and restart" else "Restart") } },
      dismissButton = {
        if (sw.bytes > 0) TextButton(onClick = { apply(sw, move = false) }) { Text("Don't move") }
        else TextButton(onClick = { pending = null }) { Text("Cancel") }
      },
    )
  }
}

private fun currentRoots(context: Context): ModelFolder.Roots =
  ModelFolder.sharedDirOrNull(context)?.let { ModelFolder.sharedRoots(it) } ?: ModelFolder.privateRoots(context)
