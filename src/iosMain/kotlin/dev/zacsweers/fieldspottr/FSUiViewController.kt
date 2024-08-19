// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.fieldspottr.di.FSComponent
import platform.UIKit.UIViewController

fun makeUiViewController(component: FSComponent): UIViewController =
  ComposeUIViewController(
    configure = {
      // https://youtrack.jetbrains.com/issue/CMP-1546
      @OptIn(ExperimentalComposeApi::class)
      platformLayers = false
    }
  ) {
    FieldSpottrApp(component, onRootPop = {})
  }
