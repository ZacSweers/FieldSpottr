// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The write a store from [QueryStore.of] makes over a state flow in a test: stores [value], fetched
 * at [fetchedAt], unless [keepsStored] says the value there should stay. The check and the write
 * are one update, as [QueryStore.writeFetched] requires.
 */
fun <T> MutableStateFlow<Fetched<T>?>.writeFetched(
  value: T,
  fetchedAt: Instant,
  keepsStored: (storedAt: Instant) -> Boolean,
) {
  update { current ->
    if (current != null && keepsStored(current.fetchedAt)) {
      current
    } else {
      Fetched(value, fetchedAt)
    }
  }
}

/**
 * The clear a store from [QueryStore.of] makes over a state flow in a test: removes the value
 * unless [keepsStored] says it should stay, in one update, as [QueryStore.clearFetched] requires.
 */
fun <T> MutableStateFlow<Fetched<T>?>.clearFetched(keepsStored: (storedAt: Instant) -> Boolean) {
  update { current ->
    if (current != null && keepsStored(current.fetchedAt)) {
      current
    } else {
      null
    }
  }
}
