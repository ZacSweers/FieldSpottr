// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
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
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveIconButton
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveScaffold
import io.github.alexzhirkevich.cupertino.adaptive.icons.AdaptiveIcons
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.ChevronBackward

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
  AdaptiveScaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(state.title, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic) },
        navigationIcon = {
          AdaptiveIconButton(onClick = { state.onBackPressed() }) {
            Icon(AdaptiveIcons.Outlined.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { innerPadding ->
    CircuitContent(state.contentScreen, modifier = Modifier.padding(innerPadding))
  }
}

@Suppress("UnusedReceiverParameter")
private val AdaptiveIcons.Outlined.ArrowBack
  @Composable
  get() =
    AdaptiveIcons.vector(
      material = { Icons.AutoMirrored.Outlined.ArrowBack },
      cupertino = { CupertinoIcons.Outlined.ChevronBackward },
    )
