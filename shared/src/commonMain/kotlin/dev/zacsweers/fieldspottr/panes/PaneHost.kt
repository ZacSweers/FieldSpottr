// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Pane state shared by [PaneNavDecoration], [PaneAwareNavigator], and the screens that take part in
 * the split layout. The app creates one per window.
 *
 * @param emptyDetailMessage the reservation for the root's first visible content, so the first
 *   frame already lays out with it.
 */
@Stable
class PaneHost(emptyDetailMessage: String? = null) {
  /** The layout the decoration applied last. [PaneAwareNavigator] reads it when a screen opens. */
  var layout: PaneLayout by mutableStateOf(PaneLayout.Single)
    internal set

  /**
   * Text for the right pane while nothing is open there. While it's set, wide windows keep the
   * right pane even when it's empty, so the root's list keeps the same width before and after a
   * pick.
   *
   * Root content that switches what it shows, such as tabs, updates this when the switch happens,
   * in the event handler. The next frame then composes the new content and lays out the new width
   * together, with no frame in between at the old width.
   */
  var emptyDetailMessage: String? by mutableStateOf(emptyDetailMessage)
}

/** The window's [PaneHost], or null outside the app's navigation content, such as in previews. */
val LocalPaneHost = staticCompositionLocalOf<PaneHost?> { null }

/** The layout of the window the current screen renders in. */
val LocalPaneLayout = compositionLocalOf { PaneLayout.Single }

/** Where the current back stack record renders. */
val LocalPaneRole = compositionLocalOf { PaneRole.SINGLE }
