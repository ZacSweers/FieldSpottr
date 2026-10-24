// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Queries addressed by key, from [DataScope.keyed]. Nothing here hands out the [DataQuery] itself,
 * so a dropped query is never kept alive by a stale reference while a new one serves the same key.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface KeyedDataQueries<K : Any, T> {
  /**
   * [key]'s stored value and fetch time. Collecting this may fetch, like [DataQuery.data]. Use
   * [stored] to watch without starting any work.
   */
  fun data(key: K): Flow<Fetched<T>?>

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

  /**
   * [key]'s stored value and fetch time, without checking freshness or fetching. Collecting it
   * keeps the key's query but starts no work.
   */
  fun stored(key: K): Flow<Fetched<T>?>

  /** [key]'s fetch status. Collecting it keeps the key's query but starts no fetch. */
  fun status(key: K): Flow<FetchStatus>

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
  fun requestFetch(key: K, force: Boolean): Operation

  /**
   * [requestFetch] for every key. The handle settles once every request has, failed if any failed.
   */
  fun requestFetch(keys: Collection<K>, force: Boolean): Operation =
    Operation.all(keys.distinct().map { requestFetch(it, force) })

  /**
   * Marks [key]'s stored data stale without fetching, so its next collector fetches. The mark lives
   * in the store, so it holds even though nothing is collecting the key.
   */
  fun invalidate(key: K): Operation

  /** Marks every key's stored data stale without fetching. */
  fun invalidate(keys: Collection<K>): Operation =
    Operation.all(keys.distinct().map { invalidate(it) })

  /**
   * [DataQuery.mutate] for [key]. [block] gets the key's store and runs after any fetch in flight
   * for the key, and fetches requested for the key after this returns run after it.
   */
  fun mutate(
    key: K,
    requireFresh: Boolean,
    block: suspend (store: QueryStore<T>) -> Unit,
  ): Operation
}
