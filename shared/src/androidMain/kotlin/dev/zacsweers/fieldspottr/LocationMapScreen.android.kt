// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
actual fun LocationMap(state: LocationMapScreen.State, modifier: Modifier) {
  val hasApiKey = !BuildConfig.MAPS_API_KEY.isNullOrBlank()

  if (hasApiKey) {
    val position = LatLng(state.latitude, state.longitude)
    val cameraPositionState = rememberCameraPositionState {
      this.position = CameraPosition.fromLatLngZoom(position, 15f)
    }

    GoogleMap(modifier = modifier.fillMaxSize(), cameraPositionState = cameraPositionState) {
      val markerState = remember { MarkerState(position = position) }
      Marker(state = markerState, title = state.title)
    }
  } else {
    // Fallback to URI behavior when API key is missing
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = state.title,
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "Latitude: ${state.latitude}\nLongitude: ${state.longitude}",
          style = MaterialTheme.typography.bodyLarge,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = 16.dp),
        )
        Text(
          text = "Google Maps API key not configured.\nClick below to open location in browser.",
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(bottom = 16.dp),
        )
        Button(onClick = state.onOpenInMaps) { Text("Open in Browser") }
      }
    }
  }
}
