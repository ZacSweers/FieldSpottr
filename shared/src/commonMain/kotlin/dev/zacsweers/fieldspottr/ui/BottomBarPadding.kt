// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Space at the bottom of the window that covers screens, such as the bottom system inset, or the
 * native tab bar that floats over content on iOS. Below a docked navigation bar this is zero,
 * because the bar takes that space itself.
 *
 * Screens draw to the bottom of the window. Scrolling content adds this after its last item so that
 * item can scroll clear. Content that does not scroll ends this far above the bottom. A screen that
 * already pads for the bottom window inset must not add this on top of it.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }
