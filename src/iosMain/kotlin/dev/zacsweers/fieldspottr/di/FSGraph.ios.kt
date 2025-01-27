// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.lattice.AppScope
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.SingleIn
import dev.zacsweers.lattice.createGraph

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface IosFSGraph : FSGraph {
  companion object {
    fun create() = createGraph<IosFSGraph>()
  }
}
