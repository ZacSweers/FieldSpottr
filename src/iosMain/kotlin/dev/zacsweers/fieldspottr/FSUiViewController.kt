// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.fieldspottr.di.FSGraph
import platform.UIKit.UIViewController

fun makeUiViewController(graph: FSGraph): UIViewController = ComposeUIViewController {
  graph.fieldSpottrApp(onRootPop = {})
}
