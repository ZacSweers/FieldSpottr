// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.app.Application
import android.os.StrictMode
import androidx.work.Configuration
import com.bugsnag.android.Bugsnag
import dev.zacsweers.fieldspottr.data.work.OutboxDrainWorkerFactory
import dev.zacsweers.fieldspottr.di.AndroidFSGraph
import dev.zacsweers.fieldspottr.di.FSGraph
import dev.zacsweers.metro.createGraphFactory

/**
 * Builds the graph and configures WorkManager on demand, so the drain worker gets the graph's
 * outbox owners. The manifest removes WorkManager's automatic initializer for that.
 */
class FieldSpottrApplication : Application(), Configuration.Provider {

  internal lateinit var fsGraph: FSGraph

  // WorkManager asks for this on its first use, which the graph's scheduler defers until a row
  // waits, so the graph exists by then. Reading the owner set constructs the owners not built yet,
  // and their outboxes start draining.
  override val workManagerConfiguration: Configuration
    get() =
      Configuration.Builder()
        .setWorkerFactory(
          OutboxDrainWorkerFactory(fsGraph.dataScopeFactory) { fsGraph.outboxOwners }
        )
        .build()

  override fun onCreate() {
    super.onCreate()
    BuildConfig.BUGSNAG_NOTIFIER_KEY?.takeIf { BuildConfig.IS_RELEASE }
      ?.let {
        if (!Bugsnag.isStarted()) {
          Bugsnag.start(this, it)
        }
      }
    fsGraph = createGraphFactory<AndroidFSGraph.Factory>()(this)
    if (!BuildConfig.IS_RELEASE) {
      StrictMode.enableDefaults()
    }
  }
}
