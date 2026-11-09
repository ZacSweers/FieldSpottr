// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.HEIGHT_DP_MEDIUM_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.computeWindowSizeClass
import com.slack.circuit.runtime.screen.Screen

/*
 * Rules for showing screens side by side on wide windows, such as tablets and the iPhone Duo in
 * landscape, open foldables like the Pixel 9 Pro Fold, and the desktop window.
 *
 * The rules read only the window size, so Android, iOS, and desktop choose the same layout for the
 * same size. Medium windows, such as tablets in portrait or the iPhone Duo in portrait, keep the
 * phone layout because two panes there leave each one too narrow for the grid.
 */

/** Width of the Material 3 navigation rail, before window insets. */
val NavigationRailWidth: Dp = 80.dp

/** The narrowest pane worth showing on either side of a hinge. */
private val MinHingePaneWidth: Dp = 280.dp

/** How a [PaneScreen] shares a wide window with the root screen. */
enum class PaneStyle {
  /** A list's detail. The list keeps a narrow pane on the left and the detail fills the rest. */
  LIST_DETAIL,

  /** Extra information about the root screen's content, which keeps most of the width. */
  SUPPORTING,
}

/**
 * A screen that opens beside the root screen on wide windows, in the way its [paneStyle] describes.
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

/**
 * The span of a vertical hinge that separates the display, such as a foldable held half open like a
 * book. Panes split at the hinge so neither one folds across it.
 */
@Immutable data class PaneHinge(val start: Dp, val end: Dp)

@Immutable
data class PaneLayout(
  /** The window fits a navigation rail and side-by-side panes. */
  val isWide: Boolean,
  /**
   * The right pane's style, or null when one screen fills the window. With only the root on the
   * back stack, the right pane takes space only while [PaneHost.emptyDetailMessage] reserves it.
   */
  val detailStyle: PaneStyle?,
  /** Width of the list beside the rail in the left pane, for [PaneStyle.LIST_DETAIL]. */
  val listPaneWidth: Dp,
  /** Width of the right pane, for [PaneStyle.SUPPORTING]. */
  val supportingPaneWidth: Dp,
  /** The screen at the bottom of the right pane. Lists use it to highlight the open item. */
  val detailRoot: Screen?,
  /** A hinge to split the panes at in place of [listPaneWidth] or [supportingPaneWidth]. */
  val hinge: PaneHinge?,
) {
  /** The window uses side-by-side panes. */
  val isSplit: Boolean
    get() = detailStyle != null

  companion object {
    /** The phone layout. */
    val Single = PaneLayout(false, null, 0.dp, 0.dp, null, null)
  }
}

/**
 * Picks the layout for a window of [windowSize].
 *
 * Wide means Material's expanded width (840dp and up) with at least medium height (480dp and up),
 * using the window size class breakpoints from androidx.window. On most tablets that means
 * landscape.
 *
 * @param firstAboveRoot the screen directly above the root on the back stack, or null when the root
 *   is the only visible screen. When it's a [PaneScreen], it and everything above it open in the
 *   right pane.
 * @param separatingHinge a vertical hinge that separates the display, if any. The panes split at it
 *   when both sides have room for a pane.
 */
fun paneLayout(
  windowSize: DpSize,
  firstAboveRoot: Screen?,
  separatingHinge: PaneHinge? = null,
): PaneLayout {
  val sizeClass =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
      widthDp = windowSize.width.value,
      heightDp = windowSize.height.value,
    )
  val isWide =
    sizeClass.isAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND, HEIGHT_DP_MEDIUM_LOWER_BOUND)
  if (!isWide) {
    return PaneLayout.Single
  }
  val detailStyle =
    when (firstAboveRoot) {
      // The panes stay in place so the root's content can reserve the right pane without a
      // layout switch
      null -> PaneStyle.LIST_DETAIL
      is PaneScreen -> firstAboveRoot.paneStyle
      else -> null
    }
  val hinge = separatingHinge?.takeIf {
    detailStyle != null &&
      it.start >= MinHingePaneWidth &&
      windowSize.width - it.end >= MinHingePaneWidth
  }
  return PaneLayout(
    isWide = true,
    detailStyle = detailStyle,
    // The detail gets most of the width, so a grid beside the list keeps room for its columns
    listPaneWidth = ((windowSize.width - NavigationRailWidth) * 0.4f).coerceIn(280.dp, 400.dp),
    supportingPaneWidth = (windowSize.width * 0.33f).coerceIn(320.dp, 400.dp),
    detailRoot = firstAboveRoot.takeIf { detailStyle != null },
    hinge = hinge,
  )
}
