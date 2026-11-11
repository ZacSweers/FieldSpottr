// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Material 3's emphasized easing, for motion that starts and ends on screen, like a button growing
 * into an editor or a value changing in place. Compose Material 3 keeps its easing tokens internal,
 * so this repeats the token's curve.
 */
val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
