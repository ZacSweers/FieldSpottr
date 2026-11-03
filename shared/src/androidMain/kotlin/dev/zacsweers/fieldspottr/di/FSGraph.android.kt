// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import android.content.Context
import androidx.work.WorkManager
import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import dev.zacsweers.fieldspottr.data.work.WorkManagerOutboxScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@DependencyGraph(AppScope::class)
interface AndroidFSGraph : FSGraph {
  /**
   * Rows that wait queue a WorkManager request, so a drain runs once the network is back even if
   * the app was killed meanwhile. WorkManager is read on the first report, after the graph exists.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideOutboxScheduler(context: Context): OutboxScheduler =
    WorkManagerOutboxScheduler(lazy { WorkManager.getInstance(context) })

  @DependencyGraph.Factory
  fun interface Factory {
    operator fun invoke(@Provides appContext: Context): AndroidFSGraph
  }
}
