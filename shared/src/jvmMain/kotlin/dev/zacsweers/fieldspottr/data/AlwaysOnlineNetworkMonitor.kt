// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The desktop has no network state worth watching, so every send goes out and a failure backs off
 * on its own, as before the monitor existed.
 */
@Inject
@ContributesBinding(AppScope::class)
class AlwaysOnlineNetworkMonitor : NetworkMonitor {
  override val online: StateFlow<Boolean> = MutableStateFlow(true)
}
