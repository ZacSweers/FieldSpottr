// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.parcel.CommonParcelize

@CommonParcelize
data class LocationMapScreen(
  val latitude: Double,
  val longitude: Double,
  val title: String,
  val gmapsUrl: String,
  val amapsUrl: String,
) : Screen {
  data class State(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val onOpenInMaps: () -> Unit,
  ) : CircuitUiState
}

@Composable
fun LocationMapPresenter(screen: LocationMapScreen): LocationMapScreen.State {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  return LocationMapScreen.State(
    latitude = screen.latitude,
    longitude = screen.longitude,
    title = screen.title,
    onOpenInMaps = {
      val url =
        when (dev.zacsweers.fieldspottr.util.CurrentPlatform) {
          dev.zacsweers.fieldspottr.util.Platform.Native -> screen.amapsUrl
          else -> screen.gmapsUrl
        }
      uriHandler.openUri(url)
    },
  )
}

@Composable expect fun LocationMap(state: LocationMapScreen.State, modifier: Modifier = Modifier)
