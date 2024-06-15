// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.zacsweers.fieldspottr.di.FSComponent
import dev.zacsweers.fieldspottr.di.JvmSharedPlatformFSComponent

fun main() {
  val component = FSComponent(JvmSharedPlatformFSComponent())
  application {
    val windowState =
      rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
        position = WindowPosition(Alignment.Center),
      )
    Window(
      title = "Field Spottr",
      state = windowState,
      onCloseRequest = ::exitApplication,
      // In lieu of a global shortcut handler, we best-effort with this
      // https://github.com/JetBrains/compose-multiplatform/issues/914
      onKeyEvent = { event ->
        when {
          // Cmd+W
          event.key == Key.W && event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
            exitApplication()
            true
          }
          else -> false
        }
      },
    ) {
      FieldSpottrApp(component, ::exitApplication)
    }
  }
}
