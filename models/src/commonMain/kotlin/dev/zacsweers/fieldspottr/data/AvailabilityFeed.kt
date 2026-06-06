// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityManifest(
  val version: Int = VERSION,
  val generatedAt: Long? = null,
  val areas: List<AvailabilityManifestArea> = emptyList(),
) {
  companion object {
    const val VERSION = 1
  }
}

@Serializable
data class AvailabilityManifestArea(
  val areaName: String? = null,
  val areaId: String? = null,
  val path: String? = null,
  val hash: String,
  val generatedAt: Long? = null,
) {
  val resolvedAreaName: String
    get() = areaName ?: areaId ?: error("Manifest area is missing areaName/areaId")

  val resolvedPath: String
    get() = path ?: "availability/areas/$resolvedAreaName.json"
}

@Serializable
data class AvailabilityAreaFeed(
  val areaName: String,
  val generatedAt: Long? = null,
  val rows: List<AvailabilityFeedRow> = emptyList(),
)

@Serializable
data class AvailabilityFeedRow(
  val areaName: String? = null,
  val groupName: String,
  val fieldId: String,
  val start: Long,
  val end: Long,
  val title: String,
  val org: String = "",
  val status: String = "",
  val kind: String = "availability",
  val sourceId: String? = null,
  @SerialName("advisory") val advisoryText: String? = null,
)
