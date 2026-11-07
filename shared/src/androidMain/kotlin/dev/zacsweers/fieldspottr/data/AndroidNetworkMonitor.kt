// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** [NetworkMonitor] over the system's default network. Needs `ACCESS_NETWORK_STATE`. */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidNetworkMonitor(context: Context, @ForScope(AppScope::class) scope: CoroutineScope) :
  NetworkMonitor {
  override val online: StateFlow<Boolean> =
    checkNotNull(context.getSystemService(ConnectivityManager::class.java)).onlineState(scope)
}

/**
 * True only while the default network has validated internet access, so a captive portal does not
 * count. The callback is registered for as long as [scope] lives.
 */
internal fun ConnectivityManager.onlineState(scope: CoroutineScope): StateFlow<Boolean> =
  callbackFlow {
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
          trySend(false)
        }
      }
    registerDefaultNetworkCallback(callback)
    awaitClose { unregisterNetworkCallback(callback) }
  }
  .stateIn(scope, SharingStarted.Eagerly, initialValue = isValidated())

private fun ConnectivityManager.isValidated(): Boolean =
  getNetworkCapabilities(activeNetwork)
    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
