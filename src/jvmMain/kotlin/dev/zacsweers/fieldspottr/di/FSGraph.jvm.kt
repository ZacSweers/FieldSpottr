// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.lattice.AppScope
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.SingleIn
import kotlinx.serialization.json.Json

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface JvmFSGraph : FSGraph {
  val json: Json
}
