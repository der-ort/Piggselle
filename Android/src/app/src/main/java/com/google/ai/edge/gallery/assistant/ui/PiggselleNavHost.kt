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

package com.google.ai.edge.gallery.assistant.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.ai.edge.gallery.assistant.ui.chat.ChatScreen
import com.google.ai.edge.gallery.assistant.ui.chat.ChatViewModel
import com.google.ai.edge.gallery.assistant.ui.settings.SettingsScreen
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val ROUTE_CHAT = "chat"
private const val ROUTE_MODELS = "models"
private const val ROUTE_SETTINGS = "settings"

/** Piggselle's whole app: chat (start), models and settings. */
@Composable
fun PiggselleNavHost(
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel = hiltViewModel(),
  chatViewModel: ChatViewModel = hiltViewModel(),
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var tosAccepted by remember { mutableStateOf(tosViewModel.getIsTosAccepted()) }

  // Downloads need to know whether the app is visible (for notifications).
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> modelManagerViewModel.setAppInForeground(true)
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> modelManagerViewModel.setAppInForeground(false)
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_CHAT,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    composable(ROUTE_CHAT) {
      ChatScreen(
        modelManagerViewModel = modelManagerViewModel,
        onOpenModels = { navController.navigate(ROUTE_MODELS) { launchSingleTop = true } },
        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
        vm = chatViewModel,
        tosViewModel = tosViewModel,
      )
    }
    composable(ROUTE_MODELS) {
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        navigateUp = { navController.popBackStack() },
        onModelSelected = { _, model ->
          // "Try it" on a model: chat with it.
          chatViewModel.engine.selectModel(model)
          navController.popBackStack(ROUTE_CHAT, inclusive = false)
        },
        onBenchmarkClicked = {},
      )
    }
    composable(ROUTE_SETTINGS) {
      SettingsScreen(
        onBack = { navController.popBackStack() },
        onOpenModels = { navController.navigate(ROUTE_MODELS) { launchSingleTop = true } },
        vm = chatViewModel,
      )
    }
  }

  if (!tosAccepted) {
    AppTosDialog(
      onTosAccepted = {
        tosViewModel.acceptTos()
        tosAccepted = true
      }
    )
  }
}
