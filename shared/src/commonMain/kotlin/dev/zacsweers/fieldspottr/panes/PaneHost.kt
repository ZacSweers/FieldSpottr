// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.slack.circuit.runtime.screen.Screen

/*
 * The names here follow circuitx-adaptive's PaneHost.kt, so the switch to that library changes
 * imports only: PaneHost.placeholder, PaneState, LocalPaneState, LocalPaneHost, LocalPaneRole.
 */

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
   * Reserves the right pane and draws it while nothing is open there. While it's set, wide windows
   * keep the right pane even when it's empty, so the root's list keeps the same width before and
   * after a pick. Null gives the root the full width until a pick.
   *
   * Root content that switches what it shows, such as tabs, sets this when the switch happens, in
   * the event handler. The next frame then composes the new content and lays out the new width
   * together, with no frame in between at the old width.
   *
   * This is state. Set the same lambda instance each time, or each set recomposes the pane.
   */
  var placeholder: (@Composable () -> Unit)? by mutableStateOf(null)
}

/**
 * The layout that a [PaneNavDecoration] chose for the composition that reads it. Screens read it
 * from [LocalPaneState].
 */
@Immutable
data class PaneState(
  /** The screen at the bottom of the right pane. Lists use it to highlight the open item. */
  val detailRoot: Screen?,
  /**
   * How many panes the window shows now. It is 1 while the root has the full width, because nothing
   * is open beside it and no placeholder is set.
   */
  val paneCount: Int = 1,
) {
  /** The window shows more than one pane. */
  val isSplit: Boolean
    get() = paneCount > 1

  companion object {
    /** One record fills the window. */
    val Single = PaneState(detailRoot = null)
  }
}

/** The window's [PaneHost], or null outside the app's navigation content, such as in previews. */
val LocalPaneHost = staticCompositionLocalOf<PaneHost?> { null }

/** The [PaneState] of the nearest [PaneNavDecoration]. It is [PaneState.Single] outside one. */
val LocalPaneState = compositionLocalOf { PaneState.Single }

/**
 * The distance from the window's start edge to the start of [PaneNavDecoration], such as a
 * navigation rail beside it. The decoration measures hinges and the start inset from the window's
 * edge, so it subtracts this.
 */
val LocalPaneWindowStart = staticCompositionLocalOf { 0.dp }

/**
 * The size of the window that holds [PaneNavDecoration], or [DpSize.Unspecified] when the
 * decoration fills the window apart from [LocalPaneWindowStart]. The decoration classifies the
 * whole window, so navigation that takes space beside or below it, such as a navigation bar, does
 * not change the layout.
 */
val LocalPaneWindowSize = staticCompositionLocalOf { DpSize.Unspecified }

/** Where the current back stack record renders. */
val LocalPaneRole = compositionLocalOf { PaneRole.SINGLE }
