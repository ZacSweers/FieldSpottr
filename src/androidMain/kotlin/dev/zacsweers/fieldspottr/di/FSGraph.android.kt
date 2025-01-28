// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.SingleIn

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface AndroidFSGraph : FSGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    operator fun invoke(@Provides appContext: Context): AndroidFSGraph
  }
}
