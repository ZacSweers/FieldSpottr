// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import platform.MapKit.MKPointAnnotation

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun LocationMap(state: LocationMapScreen.State, modifier: Modifier) {
  val coordinate = CLLocationCoordinate2DMake(state.latitude, state.longitude)

  val mapView = remember {
    MKMapView().apply {
      val region =
        MKCoordinateRegionMakeWithDistance(
          centerCoordinate = coordinate,
          latitudinalMeters = 1000.0,
          longitudinalMeters = 1000.0,
        )
      setRegion(region, animated = false)

      val annotation =
        MKPointAnnotation().apply {
          setCoordinate(coordinate)
          setTitle(state.title)
        }
      addAnnotation(annotation)
    }
  }

  Scaffold(
    modifier = modifier,
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = state.onOpenInMaps,
        icon = { Icon(Icons.Outlined.Place, contentDescription = "Open in Maps") },
        text = { Text("Open in Maps") },
      )
    },
  ) { innerPadding ->
    UIKitView(
      factory = { mapView },
      modifier = Modifier.fillMaxSize(),
      update = { view ->
        val newCoordinate = CLLocationCoordinate2DMake(state.latitude, state.longitude)
        val region =
          MKCoordinateRegionMakeWithDistance(
            centerCoordinate = newCoordinate,
            latitudinalMeters = 1000.0,
            longitudinalMeters = 1000.0,
          )
        view.setRegion(region, animated = true)

        view.removeAnnotations(view.annotations)
        val annotation =
          MKPointAnnotation().apply {
            setCoordinate(newCoordinate)
            setTitle(state.title)
          }
        view.addAnnotation(annotation)
      },
      properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
    )
  }
}
