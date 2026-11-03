// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import kotlinx.serialization.json.Json

@DependencyGraph(AppScope::class)
interface JvmFSGraph : FSGraph {
  val json: Json

  /** The desktop process lives as long as its window, so the in-process drainer is enough. */
  @Provides fun provideOutboxScheduler(): OutboxScheduler = OutboxScheduler.None
}
