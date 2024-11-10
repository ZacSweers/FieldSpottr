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
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.di.JvmFSComponent
import dev.zacsweers.fieldspottr.di.create
import java.nio.file.Paths
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main() {
  val component = JvmFSComponent::class.create()
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
          // Cmd+D
          event.key == Key.D && event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
            dumpAreasJson(component.json)
            true
          }
          else -> false
        }
      },
    ) {
      FieldSpottrApp(component.circuit, ::exitApplication)
    }
  }
}

@OptIn(ExperimentalSerializationApi::class)
private fun dumpAreasJson(json: Json) {
  val workingDir = Paths.get("").toAbsolutePath()
  val path = workingDir.resolve("areas.json")
  val prettyPrintingJson =
    Json(json) {
      prettyPrint = true
      prettyPrintIndent = "  "
    }
  if (!path.exists()) {
    path.createFile()
  }
  path.writeText(prettyPrintingJson.encodeToString(Areas.default))
  println("Wrote areas to ${path.toAbsolutePath()}")
}
