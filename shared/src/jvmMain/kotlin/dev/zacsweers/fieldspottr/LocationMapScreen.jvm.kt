// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slack.circuit.codegen.annotations.CircuitInject
import dev.zacsweers.metro.AppScope

@CircuitInject(LocationMapScreen::class, AppScope::class)
@Composable
actual fun LocationMap(state: LocationMapScreen.State, modifier: Modifier) {
  error("Should not be called on JVM")
}
