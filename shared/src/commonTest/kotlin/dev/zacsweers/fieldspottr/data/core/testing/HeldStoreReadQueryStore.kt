// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [QueryStore] over [delegate] whose next read of [data] can be held, like a database read that
 * waits for a slow disk. Writes go to [delegate] at once.
 *
 * [holdNextRead] holds the next collection of [data] that starts, and only that one, until
 * [release]. [HeldRead.BeforeSubscribe] holds it before it collects [delegate], so a store that
 * counts its collectors does not count it yet. [HeldRead.AfterFirstValue] holds it after it read
 * its first value from [delegate] and before it emits that value, so it delivers a value that may
 * be old by then.
 */
@OptIn(ExperimentalAtomicApi::class)
class HeldStoreReadQueryStore<T>(val delegate: QueryStore<T>) : QueryStore<T> by delegate {
  /** Where a held read waits. */
  enum class HeldRead {
    BeforeSubscribe,
    AfterFirstValue,
  }

  private class Hold(val where: HeldRead, val gate: CompletableDeferred<Unit>)

  // The hold for the next read, taken by the read that starts next
  private val next = AtomicReference<Hold?>(null)
  // The gate of the newest hold, which release opens
  private val gate = AtomicReference<CompletableDeferred<Unit>?>(null)
  private val started = AtomicInt(0)

  /** How many collections of [data] have started. */
  val reads: Int
    get() = started.load()

  /** Holds the next read of [data] at [where] until [release]. */
  fun holdNextRead(where: HeldRead) {
    val hold = Hold(where, CompletableDeferred())
    next.store(hold)
    gate.store(hold.gate)
  }

  /** Lets the held read go on. */
  fun release() {
    gate.exchange(null)?.complete(Unit)
  }

  override val data: Flow<Fetched<T>?> = flow {
    started.incrementAndFetch()
    val hold = next.exchange(null)
    if (hold?.where == HeldRead.BeforeSubscribe) {
      hold.gate.await()
    }
    var first = true
    delegate.data.collect { value ->
      if (first) {
        first = false
        if (hold?.where == HeldRead.AfterFirstValue) {
          hold.gate.await()
        }
      }
      emit(value)
    }
  }
}
