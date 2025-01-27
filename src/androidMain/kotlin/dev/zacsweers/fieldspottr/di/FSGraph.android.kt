// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import android.content.Context
import dev.zacsweers.lattice.AppScope
import dev.zacsweers.lattice.BindsInstance
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.SingleIn

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface AndroidFSGraph : FSGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    operator fun invoke(@BindsInstance appContext: Context): AndroidFSGraph
  }
}
