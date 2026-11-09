// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Pane state shared by [PaneNavDecoration], [PaneAwareNavigator], and the screens that take part in
 * the split layout. The app creates one per window.
 */
@Stable
class PaneHost {
  /** The layout the decoration applied last. [PaneAwareNavigator] reads it when a screen opens. */
  var layout: PaneLayout by mutableStateOf(PaneLayout.Single)
    internal set

  /**
   * Text for the right pane while nothing is open there. The root screen's visible content sets it
   * through [ReserveEmptyDetailPane], and wide windows then keep the right pane even when it's
   * empty.
   */
  var emptyDetailMessage: String? by mutableStateOf(null)
    internal set
}

/** The window's [PaneHost], or null outside the app's navigation content, such as in previews. */
val LocalPaneHost = staticCompositionLocalOf<PaneHost?> { null }

/** The layout of the window the current screen renders in. */
val LocalPaneLayout = compositionLocalOf { PaneLayout.Single }

/** Where the current back stack record renders. */
val LocalPaneRole = compositionLocalOf { PaneRole.SINGLE }

/**
 * Keeps the right pane open with [message] in wide windows while this is composed. Call it from
 * root screen content whose list opens [PaneScreen]s, so the list keeps the same width before and
 * after the first pick.
 */
@Composable
fun ReserveEmptyDetailPane(message: String) {
  val host = LocalPaneHost.current ?: return
  DisposableEffect(host, message) {
    host.emptyDetailMessage = message
    onDispose {
      if (host.emptyDetailMessage == message) {
        host.emptyDetailMessage = null
      }
    }
  }
}
