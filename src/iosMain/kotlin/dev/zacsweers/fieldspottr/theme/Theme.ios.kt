// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
actual fun FSTheme(useDarkTheme: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
  val colorScheme =
    when {
      useDarkTheme -> darkScheme
      else -> lightScheme
    }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
