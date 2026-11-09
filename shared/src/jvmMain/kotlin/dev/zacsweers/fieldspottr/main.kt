// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.di.JvmFSGraph
import dev.zacsweers.metro.createGraph
import java.nio.file.Paths
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
  val fsGraph = createGraph<JvmFSGraph>()
  if ("--dump" in args) {
    dumpAreasJson(fsGraph.json)
    return
  }
  application {
    val windowState =
      rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
        position = WindowPosition(Alignment.Center),
      )
    // Escape sends a back event, as a back gesture does on phones. Circuit's handlers decide what
    // it closes. None are enabled on the root screen, so there it does nothing and never quits the
    // app.
    val escapeInput = remember { DirectNavigationEventInput() }
    Window(
      title = "Field Spottr",
      state = windowState,
      onCloseRequest = ::exitApplication,
      alwaysOnTop = true,
      // In lieu of a global shortcut handler, we best-effort with this
      // https://youtrack.jetbrains.com/issue/CMP-5337
      onKeyEvent = { event ->
        when (event.key) {
          // Cmd+W
          Key.W if event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
            exitApplication()
            true
          }
          // Cmd+D
          Key.D if event.isMetaPressed && event.type == KeyEventType.KeyDown -> {
            dumpAreasJson(fsGraph.json)
            true
          }
          Key.Escape if event.type == KeyEventType.KeyUp -> {
            escapeInput.backCompleted()
            true
          }

          else -> false
        }
      },
    ) {
      // Use the window's dispatcher when Compose provides one, so Escape and Circuit's back
      // handlers share it
      val owner =
        LocalNavigationEventDispatcherOwner.current
          ?: remember {
            object : NavigationEventDispatcherOwner {
              override val navigationEventDispatcher = NavigationEventDispatcher()
            }
          }
      DisposableEffect(owner) {
        val dispatcher = owner.navigationEventDispatcher
        dispatcher.addInput(escapeInput)
        onDispose { dispatcher.removeInput(escapeInput) }
      }
      CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        fsGraph.fsApp(onRootPop = ::exitApplication)
      }
    }
  }
}

@OptIn(ExperimentalSerializationApi::class)
private fun dumpAreasJson(json: Json) {
  val workingDir = Paths.get("").toAbsolutePath()
  val path = workingDir.resolve("../areas.json")
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
