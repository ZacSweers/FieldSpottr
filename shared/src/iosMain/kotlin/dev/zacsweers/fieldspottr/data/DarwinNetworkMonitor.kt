// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * [NetworkMonitor] over `NWPathMonitor`. It starts as online and the first path update corrects
 * that, since the monitor reports only on its own queue. The monitor runs for as long as [scope]
 * lives.
 */
@OptIn(ExperimentalForeignApi::class)
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DarwinNetworkMonitor(@ForScope(AppScope::class) scope: CoroutineScope) : NetworkMonitor {
  override val online: StateFlow<Boolean> = callbackFlow {
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { path ->
      if (path != null) {
        trySend(nw_path_get_status(path) == nw_path_status_satisfied)
      }
    }
    // A serial queue of its own. The monitor keeps it alive while it runs.
    nw_path_monitor_set_queue(
      monitor,
      dispatch_queue_create("dev.zacsweers.fieldspottr.network", attr = null),
    )
    nw_path_monitor_start(monitor)
    awaitClose { nw_path_monitor_cancel(monitor) }
  }
    .stateIn(scope, SharingStarted.Eagerly, initialValue = true)
}
