// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuit.runtime.presenter.presenterOf
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.theme.FSTheme

@Composable
fun FieldSpottrApp(permitRepository: PermitRepository, onRootPop: () -> Unit) {
  FSTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      val circuit = remember {
        Circuit.Builder()
          .addPresenter<HomeScreen, HomeScreen.State> { _, _, _ ->
            presenterOf { HomePresenter(permitRepository) }
          }
          .addUi<HomeScreen, HomeScreen.State> { state, modifier -> Home(state, modifier) }
          .build()
      }
      val backStack = rememberSaveableBackStack(HomeScreen)
      val navigator = rememberCircuitNavigator(backStack) { onRootPop() }
      CircuitCompositionLocals(circuit) {
        ContentWithOverlays {
          NavigableCircuitContent(navigator = navigator, backStack = backStack)
        }
      }
    }
  }
}
