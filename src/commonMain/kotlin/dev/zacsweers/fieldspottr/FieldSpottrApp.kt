// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuitx.gesturenavigation.GestureNavigationDecoration
import dev.zacsweers.fieldspottr.theme.FSTheme
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveSurface

@Composable
fun FieldSpottrApp(circuit: Circuit, onRootPop: () -> Unit) {
  CircuitCompositionLocals(circuit) {
    FSTheme {
      AdaptiveSurface(color = MaterialTheme.colorScheme.background) {
        val backStack = rememberSaveableBackStack(HomeScreen)
        val navigator = rememberCircuitNavigator(backStack) { onRootPop() }
        ContentWithOverlays {
          NavigableCircuitContent(
            navigator = navigator,
            backStack = backStack,
            decoration = GestureNavigationDecoration(onBackInvoked = navigator::pop),
          )
        }
      }
    }
  }
}
