// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("UnusedReceiverParameter")
val Icons.Schedule: ImageVector
  get() {
    if (_Schedule24Px != null) {
      return _Schedule24Px!!
    }
    _Schedule24Px =
      ImageVector.Builder(
          name = "Schedule24Px",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
        )
        .apply {
          path(fill = SolidColor(Color.Black)) {
            moveTo(612f, 668f)
            lineTo(668f, 612f)
            lineTo(520f, 464f)
            lineTo(520f, 280f)
            lineTo(440f, 280f)
            lineTo(440f, 496f)
            lineTo(612f, 668f)
            close()
            moveTo(480f, 880f)
            quadTo(397f, 880f, 324f, 848.5f)
            quadTo(251f, 817f, 197f, 763f)
            quadTo(143f, 709f, 111.5f, 636f)
            quadTo(80f, 563f, 80f, 480f)
            quadTo(80f, 397f, 111.5f, 324f)
            quadTo(143f, 251f, 197f, 197f)
            quadTo(251f, 143f, 324f, 111.5f)
            quadTo(397f, 80f, 480f, 80f)
            quadTo(563f, 80f, 636f, 111.5f)
            quadTo(709f, 143f, 763f, 197f)
            quadTo(817f, 251f, 848.5f, 324f)
            quadTo(880f, 397f, 880f, 480f)
            quadTo(880f, 563f, 848.5f, 636f)
            quadTo(817f, 709f, 763f, 763f)
            quadTo(709f, 817f, 636f, 848.5f)
            quadTo(563f, 880f, 480f, 880f)
            close()
            moveTo(480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            quadTo(480f, 480f, 480f, 480f)
            close()
            moveTo(480f, 800f)
            quadTo(613f, 800f, 706.5f, 706.5f)
            quadTo(800f, 613f, 800f, 480f)
            quadTo(800f, 347f, 706.5f, 253.5f)
            quadTo(613f, 160f, 480f, 160f)
            quadTo(347f, 160f, 253.5f, 253.5f)
            quadTo(160f, 347f, 160f, 480f)
            quadTo(160f, 613f, 253.5f, 706.5f)
            quadTo(347f, 800f, 480f, 800f)
            close()
          }
        }
        .build()

    return _Schedule24Px!!
  }

@Suppress("ObjectPropertyName") private var _Schedule24Px: ImageVector? = null
