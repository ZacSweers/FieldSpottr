// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface JvmFSGraph : FSGraph {
  val json: Json
}
