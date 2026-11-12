// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeepsStored
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Clear
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Invalidate
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Write
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
    data class Write<T>(val value: T, val fetchedAt: Instant?, val invalidated: Boolean = false) :
      Call<T>

    data object Invalidate : Call<Nothing>

    data object Clear : Call<Nothing>
  }

  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val recorded = AtomicReference(persistentListOf<Call<T>>())

  /**
   * Every call that returned, oldest first. [write] goes through [writeFetched], so both record a
   * [Write] of the value they were asked to store.
   */
  val calls: List<Call<T>>
    get() = recorded.load()

  /**
   * The value and fetch time of each write request, oldest first. A fetch's write that kept the
   * stored value is here too, so read [data] to check what the store holds.
   */
  val writes: List<Fetched<T>>
    get() = calls.mapNotNull { call ->
      when (call) {
        is Call.Write -> Fetched(call.value, call.fetchedAt, call.invalidated)
        else -> null
      }
    }

  private val collections = AtomicInt(0)

  /**
   * How many times [data] was collected, `first()` included. Each collection of a database-backed
   * store runs its query, so a test counts them to check what a query reads.
   */
  val dataCollections: Int
    get() = collections.load()

  override val data: Flow<Fetched<T>?> = flow {
    collections.incrementAndFetch()
    emitAll(delegate.data)
  }

  /**
   * Recorded as a [Write] of [fetched] once the delegate returns, whatever [keepsStored] answered.
   * The delegate may ask it more than once, so no single answer tells what the store kept.
   */
  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    delegate.writeFetched(fetched, keepsStored)
    recorded.update { it.adding(Write(fetched.value, fetched.fetchedAt, fetched.invalidated)) }
  }

  override suspend fun invalidate() {
    delegate.invalidate()
    recorded.update { it.adding(Invalidate) }
  }

  /** Recorded as a [Clear] once the delegate returns, whatever [keepsStored] answered. */
  override suspend fun clearFetched(keepsStored: KeepsStored) {
    delegate.clearFetched(keepsStored)
    recorded.update { it.adding(Clear) }
  }
}
