// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import dev.zacsweers.fieldspottr.di.FSComponent
import dev.zacsweers.fieldspottr.theme.FSTheme

@Composable
fun FieldSpottrApp(component: FSComponent, onRootPop: () -> Unit) {
  FSTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      val backStack = rememberSaveableBackStack(HomeScreen)
      val navigator = rememberCircuitNavigator(backStack) { onRootPop() }
      CircuitCompositionLocals(component.circuit) {
        ContentWithOverlays {
          NavigableCircuitContent(navigator = navigator, backStack = backStack)
        }
      }
    }
  }
}
