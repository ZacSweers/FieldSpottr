// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

// Google Maps URL first
// Format: https://www.google.com/maps/place/40°42'44.2"N+73°58'37.4"W/@40.712286,-73.9792062
private val gmapsPattern = "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)".toRegex()

// Apple Maps URL format
// Format: https://maps.apple.com/?...&ll=40.717599,-73.976666
private val amapsPattern = "ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)".toRegex()

fun extractCoordinatesFromUrl(gmapsUrl: String, amapsUrl: String): Pair<Double, Double>? {
  gmapsPattern.find(gmapsUrl)?.let { match ->
    val lat = match.groupValues[1].toDoubleOrNull()
    val lon = match.groupValues[2].toDoubleOrNull()
    if (lat != null && lon != null) return lat to lon
  }

  amapsPattern.find(amapsUrl)?.let { match ->
    val lat = match.groupValues[1].toDoubleOrNull()
    val lon = match.groupValues[2].toDoubleOrNull()
    if (lat != null && lon != null) return lat to lon
  }

  return null
}
