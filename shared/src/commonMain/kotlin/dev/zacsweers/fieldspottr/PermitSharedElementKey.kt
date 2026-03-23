// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import com.slack.circuit.sharedelements.SharedTransitionKey

data class PermitSharedElementKey(
  val name: String,
  val timeRange: String,
  val org: String,
  val isOverlap: Boolean = false,
) : SharedTransitionKey
