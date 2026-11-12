// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeepsStored
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The write a store from [QueryStore.of] makes over a state flow in a test: stores [fetched],
 * unless [keepsStored] says the value there should stay. The check and the write are one update, as
 * [QueryStore.writeFetched] requires.
 */
fun <T> MutableStateFlow<Fetched<T>?>.writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
  update { current ->
    if (current != null && keepsStored(current.fetchedAt, current.invalidated)) {
      current
    } else {
      fetched
    }
  }
}

/** [writeFetched] of [value], fetched at [fetchedAt]. */
fun <T> MutableStateFlow<Fetched<T>?>.writeFetched(
  value: T,
  fetchedAt: Instant,
  keepsStored: KeepsStored,
) {
  writeFetched(Fetched(value, fetchedAt), keepsStored)
}

/**
 * The clear a store from [QueryStore.of] makes over a state flow in a test: removes the value
 * unless [keepsStored] says it should stay, in one update, as [QueryStore.clearFetched] requires.
 */
fun <T> MutableStateFlow<Fetched<T>?>.clearFetched(keepsStored: KeepsStored) {
  update { current ->
    if (current != null && keepsStored(current.fetchedAt, current.invalidated)) {
      current
    } else {
      null
    }
  }
}
