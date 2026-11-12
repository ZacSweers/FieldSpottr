// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.InMemoryQueryStore
import dev.zacsweers.fieldspottr.data.core.KeepsStored
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [QueryStore] whose collectors and writers see each change at a different time from the store,
 * like a database store that runs its calls on another dispatcher.
 *
 * Collectors see each change [lag] late. A collection's first value comes at once, so a new read
 * shows what is stored now. Every write and clear returns [writeReturnsAfter] after it changed
 * [delegate], so its collectors can see the new value before the writer goes on. Writes change
 * [delegate] at once, so a query's status can change before its collectors see the write that went
 * with it.
 *
 * It implements the conditional forms itself, without `by` delegation, so the default [write] and
 * [clear] and the value and time extensions all reach them.
 */
class LaggingQueryStore<T>(
  private val lag: Duration = Duration.ZERO,
  private val writeReturnsAfter: Duration = Duration.ZERO,
  /** The values without the lag, to read and seed what is stored now. */
  val delegate: InMemoryQueryStore<T> = QueryStore.inMemory(),
) : QueryStore<T> {
  override val data: Flow<Fetched<T>?> = flow {
    var first = true
    delegate.data.collect {
      if (!first) {
        delay(lag)
      }
      first = false
      emit(it)
    }
  }

  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    delegate.writeFetched(fetched, keepsStored)
    delay(writeReturnsAfter)
  }

  override suspend fun clearFetched(keepsStored: KeepsStored) {
    delegate.clearFetched(keepsStored)
    delay(writeReturnsAfter)
  }

  override suspend fun invalidate() {
    delegate.invalidate()
  }
}
