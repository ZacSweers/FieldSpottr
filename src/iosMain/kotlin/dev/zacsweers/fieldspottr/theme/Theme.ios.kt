// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveTheme
import io.github.alexzhirkevich.cupertino.adaptive.CupertinoThemeSpec
import io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi
import io.github.alexzhirkevich.cupertino.adaptive.MaterialThemeSpec
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme.shapes
import io.github.alexzhirkevich.cupertino.theme.darkColorScheme
import io.github.alexzhirkevich.cupertino.theme.lightColorScheme

@OptIn(ExperimentalAdaptiveApi::class)
@Composable
actual fun FSTheme(useDarkTheme: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
  val colorScheme =
    when {
      useDarkTheme -> darkScheme
      else -> lightScheme
    }

  val primaryColor = colorScheme.primary
  AdaptiveTheme(
    material = MaterialThemeSpec(colorScheme = colorScheme),
    cupertino = CupertinoThemeSpec.Default(
      colorScheme = if (useDarkTheme) {
        darkColorScheme(accent = primaryColor)
      } else {
        lightColorScheme(accent = primaryColor)
      },
      shapes = io.github.alexzhirkevich.cupertino.theme.Shapes(
        extraSmall = shapes.extraSmall,
        small = shapes.small,
        medium = shapes.medium,
        large = shapes.large,
        extraLarge = shapes.extraLarge
      )
    ),
    content = content,
  )
}
