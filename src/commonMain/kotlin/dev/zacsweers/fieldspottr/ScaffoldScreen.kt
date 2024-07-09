// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.parcel.CommonParcelize

@CommonParcelize
data class ScaffoldScreen(val title: String = "Field Spottr", val contentScreen: Screen) : Screen {
  data class State(val title: String, val contentScreen: Screen, val onBackPressed: () -> Unit) :
    CircuitUiState
}

@Composable
fun ScaffoldPresenter(screen: ScaffoldScreen, navigator: Navigator): ScaffoldScreen.State {
  return ScaffoldScreen.State(screen.title, screen.contentScreen, navigator::pop)
}

@Composable
fun ScaffoldScreenContent(state: ScaffoldScreen.State, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(state.title, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic) },
        navigationIcon = {
          // TODO platform-default back button?
          IconButton(onClick = { state.onBackPressed() }) {
            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Close")
          }
        },
      )
    },
  ) { innerPadding ->
    CircuitContent(state.contentScreen, modifier = Modifier.padding(innerPadding))
  }
}
