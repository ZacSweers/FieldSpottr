// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import com.slack.circuit.sharedelements.SharedTransitionKey

data class PermitSharedElementKey(
  val fieldName: String,
  val index: Int,
  val name: String,
  val timeRange: String,
  val org: String,
  val isOverlap: Boolean = false,
) : SharedTransitionKey

/** Shared element key for the FAF list item ↔ AreaScreen background (always opaque). */
data class AreaContainerSharedElementKey(val groupName: String) : SharedTransitionKey

/** Shared element key for the FAF list item ↔ AreaScreen content (cross-fades inside bounds). */
data class AreaContentSharedElementKey(val groupName: String) : SharedTransitionKey
