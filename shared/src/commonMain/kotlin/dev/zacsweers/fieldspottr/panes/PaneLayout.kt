// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.slack.circuit.runtime.screen.Screen

/*
 * Rules for showing screens side by side on wide windows, such as open foldables, the iPhone Duo
 * inner display, tablets, and the desktop window.
 *
 * The rules read only the window size, so Android, iOS, and desktop choose the same layout for the
 * same size. On iOS the thresholds line up with the regular width and height size classes.
 */

/** The narrowest window that shows a navigation rail and side-by-side panes. */
val WideMinWidth: Dp = 600.dp

/** The shortest window that shows a rail and panes. Phones in landscape stay below it. */
val WideMinHeight: Dp = 480.dp

/** Width of the Material 3 navigation rail, before window insets. */
val NavigationRailWidth: Dp = 80.dp

/** How a [PaneScreen] shares a wide window with the root screen. */
enum class PaneStyle(
  /**
   * The narrowest window that shows this pane beside the root screen. Narrower windows show the
   * screen full width, as on phones.
   */
  val minWindowWidth: Dp
) {
  /** A list's detail. The list keeps a narrow pane on the left and the detail fills the rest. */
  LIST_DETAIL(600.dp),

  /** Extra information about the root screen's content, which keeps most of the width. */
  SUPPORTING(840.dp),
}

/**
 * A screen that opens beside the root screen when the window is wide enough for its [paneStyle].
 * Screens pushed over it open in the same pane.
 */
interface PaneScreen : Screen {
  val paneStyle: PaneStyle
}

/** Where a back stack record renders. Screens read it to adapt back buttons and shared elements. */
enum class PaneRole {
  /** The only visible screen, as on phones. */
  SINGLE,
  /** The root screen, in the left pane. */
  PRIMARY,
  /** The bottom screen of the right pane, opened from the root screen. */
  DETAIL_ROOT,
  /** A screen pushed over another screen in the right pane. */
  DETAIL_STACKED,
}

@Immutable
data class PaneLayout(
  /** The window fits a navigation rail and side-by-side panes. */
  val isWide: Boolean,
  /** The right pane's style, or null when one screen fills the window. */
  val detailStyle: PaneStyle?,
  /** Width of the list beside the rail in the left pane, for [PaneStyle.LIST_DETAIL]. */
  val listPaneWidth: Dp,
  /** Width of the right pane, for [PaneStyle.SUPPORTING]. */
  val supportingPaneWidth: Dp,
  /** The screen at the bottom of the right pane. Lists use it to highlight the open item. */
  val detailRoot: Screen?,
) {
  /** The root screen and a right pane share the window. */
  val isSplit: Boolean
    get() = detailStyle != null

  companion object {
    /** The phone layout. */
    val Single = PaneLayout(false, null, 0.dp, 0.dp, null)
  }
}

/**
 * Picks the layout for a window of [windowSize].
 *
 * @param firstAboveRoot the screen directly above the root on the back stack, or null when the root
 *   is the only visible screen. Its [PaneStyle] decides whether it opens in the right pane.
 * @param wantsEmptyDetail the root screen reserves the right pane while nothing is open there, so
 *   its list keeps the same width before and after a pick.
 */
fun paneLayout(windowSize: DpSize, firstAboveRoot: Screen?, wantsEmptyDetail: Boolean): PaneLayout {
  val isWide = windowSize.width >= WideMinWidth && windowSize.height >= WideMinHeight
  if (!isWide) {
    return PaneLayout.Single
  }
  val detailStyle =
    when {
      firstAboveRoot == null -> {
        if (wantsEmptyDetail) {
          PaneStyle.LIST_DETAIL
        } else {
          null
        }
      }
      firstAboveRoot is PaneScreen && windowSize.width >= firstAboveRoot.paneStyle.minWindowWidth ->
        firstAboveRoot.paneStyle
      else -> null
    }
  return PaneLayout(
    isWide = true,
    detailStyle = detailStyle,
    listPaneWidth = ((windowSize.width - NavigationRailWidth) * 0.4f).coerceIn(280.dp, 400.dp),
    supportingPaneWidth = (windowSize.width * 0.33f).coerceIn(320.dp, 400.dp),
    detailRoot = firstAboveRoot.takeIf { detailStyle != null },
  )
}
