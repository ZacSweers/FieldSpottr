// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun platformSpecificMaterialColorScheme(
  useDarkTheme: Boolean,
  dynamicColor: Boolean,
): ColorScheme? = null

@Composable
actual fun PlatformSpecificThemeSideEffects() {

}