// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Clear
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Invalidate
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Write
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/**
 * A [QueryStore] that records each call it gets and passes it on to [delegate], so a test can
 * assert what a query did to its store, like that fresh data saw no write. A call is recorded once
 * the delegate returns, so one that throws is left out. Recording is safe from any thread.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecordingQueryStore<T>(private val delegate: QueryStore<T> = QueryStore.inMemory()) :
  QueryStore<T> {
  /** One call the store received. */
  sealed interface Call<out T> {
    data class Write<T>(val value: T, val fetchedAt: Instant) : Call<T>

    data object Invalidate : Call<Nothing>

    data object Clear : Call<Nothing>
  }

  // Compare-and-set only. Nothing observes it.
  private val recorded = AtomicReference(persistentListOf<Call<T>>())

  /** Every call, oldest first. */
  val calls: List<Call<T>>
    get() = recorded.load()

  /** What each write stored, oldest first. */
  val writes: List<Fetched<T>>
    get() = calls.mapNotNull { call ->
      when (call) {
        is Call.Write -> Fetched(call.value, call.fetchedAt)
        else -> null
      }
    }

  override val data: Flow<Fetched<T>?> = delegate.data

  override suspend fun write(value: T, fetchedAt: Instant) {
    delegate.write(value, fetchedAt)
    recorded.update { it.adding(Write(value, fetchedAt)) }
  }

  override suspend fun invalidate() {
    delegate.invalidate()
    recorded.update { it.adding(Invalidate) }
  }

  override suspend fun clear() {
    delegate.clear()
    recorded.update { it.adding(Clear) }
  }
}
