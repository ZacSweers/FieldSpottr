// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Queries addressed by key, from [DataScope.keyed]. Nothing here hands out the [DataQuery] itself,
 * so a dropped query is never kept alive by a stale reference while a new one serves the same key.
 *
 * A read by key returns a new flow on each call, so hold it in a value, or `remember` it by key in
 * a composable, where the instance matters.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface KeyedDataQueries<K : Any, T> {
  /** [key]'s [DataQuery.state]. Collecting it keeps the key's query but starts no work. */
  fun state(key: K): Flow<QueryState<T>>

  /** [key]'s [DataQuery.stateAutoFetch]. Collecting this may fetch. */
  fun stateAutoFetch(key: K): Flow<QueryState<T>>

  /** [key]'s [DataQuery.data]. Collecting it keeps the key's query but starts no work. */
  fun data(key: K): Flow<Fetched<T>?>

  /**
   * Every key's [data] in one map. Collecting it keeps each key's query but starts no work.
   * Duplicate keys count once, and an empty collection of keys emits one empty map.
   */
  fun data(keys: Collection<K>): Flow<Map<K, Fetched<T>?>> = combinedByKey(keys) { data(it) }

  /** [key]'s [DataQuery.dataAutoFetch]. Collecting this may fetch. */
  fun dataAutoFetch(key: K): Flow<Fetched<T>?> =
    stateAutoFetch(key).map { it.fetched }.distinctUntilChanged(::sameFetched)

  /**
   * Every key's [dataAutoFetch] in one map, collected in parallel so stale keys fetch at the same
   * time. Duplicate keys count once, and an empty collection of keys emits one empty map.
   */
  fun dataAutoFetch(keys: Collection<K>): Flow<Map<K, Fetched<T>?>> =
    combinedByKey(keys) { dataAutoFetch(it) }

  /** [key]'s [DataQuery.status]. Collecting it keeps the key's query but starts no fetch. */
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
   * [DataQuery.requestFetch] for [key]. An unforced request suits work that needs a key's data
   * fresh without collecting it, like a sync.
   */
  fun requestFetch(key: K, force: Boolean): Operation

  /**
   * [requestFetch] for every key. The handle settles once every request has, failed if any failed.
   */
  fun requestFetch(keys: Collection<K>, force: Boolean): Operation =
    Operation.all(keys.distinct().map { requestFetch(it, force) })

  /**
   * [key]'s [DataQuery.invalidate]. The mark lives in the store, so it holds even though nothing is
   * collecting the key.
   */
  fun invalidate(key: K): Operation

  /** Marks every key's stored data stale without fetching. */
  fun invalidate(keys: Collection<K>): Operation =
    Operation.all(keys.distinct().map { invalidate(it) })

  /**
   * Marks the data of every key stale without fetching, including keys this set cannot list. Keys
   * in use now, with a collector or with work queued or running, are invalidated through their
   * queries, as [invalidate] does. For every other key, this set records the time of the call, and
   * a value fetched at or before that time counts as stale. A fetch in flight now has an earlier
   * fetch time, so its result counts as stale too.
   *
   * The record lives in memory for the rest of the process. The owner of a store that outlives the
   * process, and needs those keys stale after a restart, also marks its own rows, like by setting
   * their invalidated flag. The handle settles once the keys in use are marked.
   */
  fun invalidateAll(): Operation

  /**
   * [requestFetch] for every key in use now, with a collector or with work queued or running. The
   * handle settles once every request has, failed if any failed.
   */
  fun requestFetchAll(force: Boolean): Operation
}

/**
 * One map of every distinct key's latest value from [flowFor]. An empty collection of keys emits
 * one empty map.
 */
private fun <K : Any, T> combinedByKey(
  keys: Collection<K>,
  flowFor: (K) -> Flow<Fetched<T>?>,
): Flow<Map<K, Fetched<T>?>> {
  val distinct = keys.distinct()
  if (distinct.isEmpty()) {
    return flowOf(emptyMap())
  }
  // combine needs a reified element type, which T is not here
  val flows: List<Flow<Any?>> = distinct.map { flowFor(it) }
  return combine(flows) { values ->
    @Suppress("UNCHECKED_CAST")
    distinct.indices.associate { i -> distinct[i] to values[i] as Fetched<T>? }
  }
}

/** [KeyedDataQueries.requestFetch] for [key] with `force = true`: fetches now. */
fun <K : Any, T> KeyedDataQueries<K, T>.refresh(key: K): Operation = requestFetch(key, force = true)

/** [KeyedDataQueries.requestFetch] for [key] with `force = false`: fetches only if stale. */
fun <K : Any, T> KeyedDataQueries<K, T>.fetchIfStale(key: K): Operation =
  requestFetch(key, force = false)

/**
 * [KeyedDataQueries.invalidate] for [key], then a forced [KeyedDataQueries.requestFetch], as
 * [DataQuery.invalidateAndRefresh] describes. The handle follows the fetch. A later call for [key]
 * stops this call's fetch, and this call's handle then settles `Cancelled`.
 */
fun <K : Any, T> KeyedDataQueries<K, T>.invalidateAndRefresh(key: K): Operation {
  invalidate(key)
  return refresh(key)
}

/** [refresh] for every key. The handle settles once every request has, failed if any failed. */
fun <K : Any, T> KeyedDataQueries<K, T>.refresh(keys: Collection<K>): Operation =
  requestFetch(keys, force = true)

/** [fetchIfStale] for every key. The handle settles once every request has. */
fun <K : Any, T> KeyedDataQueries<K, T>.fetchIfStale(keys: Collection<K>): Operation =
  requestFetch(keys, force = false)
