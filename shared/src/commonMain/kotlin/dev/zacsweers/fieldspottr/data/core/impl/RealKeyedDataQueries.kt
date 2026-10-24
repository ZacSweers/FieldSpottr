// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** How many keys still hold a query. For tests, which cannot see eviction any other way. */
internal val KeyedDataQueries<*, *>.keysHeld: Int
  get() = (this as RealKeyedDataQueries<*, *>).keysHeld

/**
 * [KeyedDataQueries] that builds each key's query with [build] on first use and drops it once no
 * caller and no unfinished work holds it, as [KeyEntry] describes.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealKeyedDataQueries<K : Any, T>(private val build: (K) -> RealDataQuery<T>) :
  KeyedDataQueries<K, T> {
  // Compare-and-set only. Nothing observes it.
  private val entries = AtomicReference(persistentHashMapOf<K, KeyEntry<T>>())

  val keysHeld: Int
    get() = entries.load().size

  override fun data(key: K): Flow<Fetched<T>?> = flow {
    val entry = acquire(key)
    try {
      emitAll(entry.query.data(onRequest = entry::holdUntilSettled))
    } finally {
      entry.release()
    }
  }

  override fun stored(key: K): Flow<Fetched<T>?> = flow {
    val entry = acquire(key)
    try {
      emitAll(entry.query.stored)
    } finally {
      entry.release()
    }
  }

  override fun status(key: K): Flow<FetchStatus> = flow {
    val entry = acquire(key)
    try {
      emitAll(entry.query.status)
    } finally {
      entry.release()
    }
  }

  override fun requestFetch(key: K, force: Boolean): Operation {
    val entry = acquire(key)
    try {
      return entry.query.requestFetch(force).also(entry::holdUntilSettled)
    } finally {
      entry.release()
    }
  }

  override fun invalidate(key: K): Operation {
    val entry = acquire(key)
    try {
      return entry.query.invalidate().also(entry::holdUntilSettled)
    } finally {
      entry.release()
    }
  }

  override fun mutate(
    key: K,
    requireFresh: Boolean,
    block: suspend (store: QueryStore<T>) -> Unit,
  ): Operation {
    val entry = acquire(key)
    try {
      return entry.query.mutate(requireFresh, block).also(entry::holdUntilSettled)
    } finally {
      entry.release()
    }
  }

  /** The entry for [key], held until [KeyEntry.release]. Builds it on first use. */
  private fun acquire(key: K): KeyEntry<T> {
    while (true) {
      val current = entries.load()
      var entry = current[key]
      if (entry == null) {
        // Building a query starts nothing, so the copy that loses this race is simply dropped
        val created = KeyEntry(build(key)) { remove(key, it) }
        if (!entries.compareAndSet(current, current.putting(key, created))) {
          continue
        }
        entry = created
      }
      if (entry.tryAcquire()) {
        return entry
      }
      // Dropped between the load and the acquire: clear it out and build a fresh one
      remove(key, entry)
    }
  }

  private fun remove(key: K, entry: KeyEntry<T>) {
    entries.update {
      if (it[key] === entry) {
        it.removing(key)
      } else {
        it
      }
    }
  }
}

/**
 * A key's query and what holds it: each caller from acquire to release, and each operation a caller
 * started until its command finishes, automatic refetches and cancelled commands included. The last
 * release drops the entry, so a key's next query never runs beside unfinished work from the last
 * one, and nothing has to look for idle entries later.
 */
@OptIn(ExperimentalAtomicApi::class)
private class KeyEntry<T>(
  val query: RealDataQuery<T>,
  private val onDropped: (KeyEntry<T>) -> Unit,
) {
  // Callers and unfinished operations, or -1 once dropped. A dropped entry is never acquired again.
  private val holds = AtomicInt(0)

  fun tryAcquire(): Boolean {
    while (true) {
      val count = holds.load()
      if (count < 0) {
        return false
      }
      if (holds.compareAndSet(count, count + 1)) {
        return true
      }
    }
  }

  /**
   * Holds the entry until [operation]'s command finishes, cancelled or not. Only called while a
   * caller holds the entry, so it never revives a dropped one.
   */
  fun holdUntilSettled(operation: Operation) {
    holds.incrementAndFetch()
    operation.invokeOnSettled(::release)
  }

  /** Lets go of one hold, and drops the entry when it was the last. */
  fun release() {
    if (holds.decrementAndFetch() == 0 && holds.compareAndSet(0, -1)) {
      onDropped(this)
    }
  }
}
