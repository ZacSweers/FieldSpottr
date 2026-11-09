// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device can reach the internet, for the data scope factory's online state. True while
 * a network is up and validated, so a captive portal counts as offline. Outboxes drain only while
 * it is true, and a reconnect sends what waited at once. Each platform binds one into the graph.
 */
interface NetworkMonitor {
  val online: StateFlow<Boolean>
}
