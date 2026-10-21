// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.update
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** [keyed] with one [freshness] rule for every key. */
fun <K : Any, T> DataQuery.Factory.keyed(
  store: (key: K) -> QueryStore<T>,
  fetch: suspend (key: K) -> T,
  freshness: Freshness,
  maxFetches: Int = Int.MAX_VALUE,
): KeyedDataQueries<K, T> = keyed(store, fetch, { freshness }, maxFetches)

/**
 * One [DataQuery] per key, each over the [store] for that key, shared by every caller for the key
 * so concurrent collectors share its fetches and its status. [freshness] can differ per key, like a
 * shorter window for today than for later dates. [maxFetches] caps the fetches in flight across all
 * keys. It does not cap store writes, which still run at the same time for different keys.
 *
 * A key's query is dropped once nothing uses it and no fetch it started is still running. Its data
 * stays in its store, so the next query for the key picks up the stored value and fetch time.
 */
fun <K : Any, T> DataQuery.Factory.keyed(
  store: (key: K) -> QueryStore<T>,
  fetch: suspend (key: K) -> T,
  freshness: (key: K) -> Freshness,
  maxFetches: Int = Int.MAX_VALUE,
): KeyedDataQueries<K, T> {
  require(maxFetches > 0) { "maxFetches must be positive, was $maxFetches" }
  val permits =
    if (maxFetches == Int.MAX_VALUE) {
      null
    } else {
      Semaphore(maxFetches)
    }
  return KeyedDataQueries { key ->
    val keyFetch: suspend () -> T =
      if (permits == null) {
        { fetch(key) }
      } else {
        // The fetch time is taken before the wait, so a queued fetch reads as older, never fresher
        { permits.withPermit { fetch(key) } }
      }
    create(store(key), keyFetch, freshness(key))
  }
}

/**
 * Queries addressed by key. Nothing here hands out the [DataQuery] itself, so a dropped query is
 * never kept alive by a stale reference while a new one serves the same key.
 */
@OptIn(ExperimentalAtomicApi::class)
class KeyedDataQueries<K : Any, T> internal constructor(private val build: (K) -> DataQuery<T>) {
  // Compare-and-set only. Nothing observes it.
  private val entries = AtomicReference(persistentHashMapOf<K, KeyEntry<T>>())

  /** [key]'s stored value and fetch time. Collecting it fetches first when stale. */
  fun data(key: K): Flow<Fetched<T>?> = flow {
    val entry = acquire(key)
    try {
      // Pinned, so the entry outlives the fetch even if this collector leaves first
      entry.pin(entry.query.requestFetch(force = false))
      emitAll(entry.query.data)
    } finally {
      release(key, entry)
    }
  }

  /**
   * Every key's data in one map, collected in parallel so stale keys fetch at the same time.
   * Duplicate keys collapse, and no keys emits an empty map.
   */
  fun data(keys: Collection<K>): Flow<Map<K, Fetched<T>?>> {
    val distinct = keys.distinct()
    if (distinct.isEmpty()) {
      return flowOf(emptyMap())
    }
    // combine needs a reified element type, which T is not here
    val flows: List<Flow<Any?>> = distinct.map { data(it) }
    return combine(flows) { values ->
      @Suppress("UNCHECKED_CAST")
      distinct.indices.associate { i -> distinct[i] to values[i] as Fetched<T>? }
    }
  }

  /** [key]'s fetch status. Collecting it keeps the key's query but starts no fetch. */
  fun status(key: K): Flow<FetchStatus> = flow {
    val entry = acquire(key)
    try {
      emitAll(entry.query.status)
    } finally {
      release(key, entry)
    }
  }

  /** [FetchStatus.Failed] if any key failed, else [FetchStatus.Fetching] if any is fetching. */
  fun status(keys: Collection<K>): Flow<FetchStatus> {
    val distinct = keys.distinct()
    if (distinct.isEmpty()) {
      return flowOf(FetchStatus.Idle)
    }
    return combine(distinct.map { status(it) }) { statuses ->
      statuses.firstOrNull { it is FetchStatus.Failed }
        ?: statuses.firstOrNull { it is FetchStatus.Fetching }
        ?: FetchStatus.Idle
    }
  }

  /**
   * [DataQuery.requestFetch] for [key]. A forced request fetches now, even when the data is fresh.
   * An unforced one joins a fetch already in flight, else fetches only if the stored data is stale,
   * for work that needs a key's data fresh without collecting it, like a sync.
   */
  fun requestFetch(key: K, force: Boolean): Operation {
    val entry = acquire(key)
    try {
      return entry.query.requestFetch(force).also(entry::pin)
    } finally {
      release(key, entry)
    }
  }

  /**
   * [requestFetch] for every key. The handle settles once every request has, failed if any failed.
   */
  fun requestFetch(keys: Collection<K>, force: Boolean): Operation =
    Operation.all(keys.distinct().map { requestFetch(it, force) })

  /**
   * Marks [key]'s stored data stale without fetching, so its next collector fetches. The mark lives
   * in the store, so it holds even though nothing is collecting the key.
   */
  fun invalidate(key: K): Operation {
    val entry = acquire(key)
    try {
      return entry.query.invalidate().also(entry::pin)
    } finally {
      release(key, entry)
    }
  }

  /** Marks every key's stored data stale without fetching. */
  fun invalidate(keys: Collection<K>): Operation =
    Operation.all(keys.distinct().map { invalidate(it) })

  /** The entry for [key], counted as in use until [release]. Builds it on first use. */
  private fun acquire(key: K): KeyEntry<T> {
    // Entries left behind by a fetch that outlived its users go once it settles
    for ((idleKey, idle) in entries.load()) {
      if (idle.tryDrop()) {
        remove(idleKey, idle)
      }
    }
    while (true) {
      val current = entries.load()
      var entry = current[key]
      if (entry == null) {
        // Building a query starts nothing, so the copy that loses this race is simply dropped
        val created = KeyEntry(build(key))
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

  private fun release(key: K, entry: KeyEntry<T>) {
    entry.release()
    if (entry.tryDrop()) {
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

@OptIn(ExperimentalAtomicApi::class)
private class KeyEntry<T>(val query: DataQuery<T>) {
  // Callers using the query, or -1 once dropped. A dropped entry is never acquired again.
  private val users = AtomicInt(0)
  // Fetches the entry must outlive, so a fetch nobody waits on keeps its status visible
  private val pins = AtomicReference(persistentListOf<Operation>())

  fun tryAcquire(): Boolean {
    while (true) {
      val count = users.load()
      if (count < 0) {
        return false
      }
      if (users.compareAndSet(count, count + 1)) {
        return true
      }
    }
  }

  fun release() {
    users.decrementAndFetch()
  }

  fun pin(operation: Operation) {
    pins.update { current ->
      (current.filter { it.state.value !is OperationState.Terminal } + operation).toPersistentList()
    }
  }

  fun tryDrop(): Boolean =
    users.load() == 0 &&
      pins.load().all { it.state.value is OperationState.Terminal } &&
      query.status.value !is FetchStatus.Fetching &&
      users.compareAndSet(0, -1)
}
