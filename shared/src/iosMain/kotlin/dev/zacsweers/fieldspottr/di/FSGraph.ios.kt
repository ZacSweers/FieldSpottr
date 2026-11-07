// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
interface IosFSGraph : FSGraph {
  /**
   * Foreground drains do the work on iOS. A `BGTaskScheduler` request from `rowsWaiting` would add
   * a best-effort background drain, and needs the task registered in the iOS app first.
   */
  @Provides fun provideOutboxScheduler(): OutboxScheduler = OutboxScheduler.None

  companion object {
    fun create() = createGraph<IosFSGraph>()
  }
}
