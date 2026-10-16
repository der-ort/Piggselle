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

package com.google.ai.edge.gallery.assistant.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen viewer for images given to the model: swipe between them, pinch or double-tap to
 * zoom. Built on Compose's own pager and transform gestures.
 *
 * @param onAttach if set, offers to attach the shown image to the next message.
 */
@Composable
fun ImageViewer(
  images: List<Bitmap>,
  startIndex: Int,
  onAttach: ((Bitmap) -> Unit)?,
  onDismiss: () -> Unit,
) {
  if (images.isEmpty()) return
  val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        ZoomableImage(images[page], onTapEmpty = onDismiss)
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
      ) {
        IconButton(onClick = onDismiss) {
          Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
        }
        if (images.size > 1) {
          Text(
            "${pager.currentPage + 1} / ${images.size}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
          )
        }
      }
      if (onAttach != null) {
        Row(
          horizontalArrangement = Arrangement.Center,
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
        ) {
          FilledTonalButton(onClick = { onAttach(images[pager.currentPage]); onDismiss() }) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Text("  Ask about this again")
          }
        }
      }
    }
  }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, onTapEmpty: () -> Unit) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val state = rememberTransformableState { zoom, pan, _ ->
    scale = (scale * zoom).coerceIn(1f, 6f)
    offset = if (scale == 1f) Offset.Zero else offset + pan * scale
  }
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier.fillMaxSize()
        .pointerInput(bitmap) {
          detectTapGestures(
            onDoubleTap = {
              if (scale > 1f) {
                scale = 1f
                offset = Offset.Zero
              } else {
                scale = 2.5f
              }
            },
            onTap = { if (scale == 1f) onTapEmpty() },
          )
        }
        // Only take over drags while zoomed, so the pager can still swipe at 1x.
        .transformable(state = state, canPan = { scale > 1f }),
  ) {
    Image(
      bitmap = bitmap.asImageBitmap(),
      contentDescription = "Attached image",
      contentScale = ContentScale.Fit,
      modifier =
        Modifier.fillMaxSize().graphicsLayer {
          scaleX = scale
          scaleY = scale
          translationX = offset.x
          translationY = offset.y
        },
    )
  }
}
