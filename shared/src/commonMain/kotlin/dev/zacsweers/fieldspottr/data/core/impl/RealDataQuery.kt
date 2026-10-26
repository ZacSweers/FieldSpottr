// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/** [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. */
@OptIn(DelicateDataLayerApi::class)
internal class RealDataQuery<T>(
  private val actions: RealDataActions,
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock,
) : DataQuery<T> {
  // Private, so only this query's commands chain on it
  private val key = Any()

  override val data: Flow<Fetched<T>?> = data(onRequest = {})

  override val stored: Flow<Fetched<T>?> = store.data

  /**
   * [data], handing [onRequest] each request it makes, the ones after the store empties included.
   * Every command this query runs reaches a caller as an [Operation] this way or as a return value,
   * which [KeyedDataQueries] relies on to keep a key's query until its work is done.
   */
  internal fun data(onRequest: (Operation) -> Unit): Flow<Fetched<T>?> = flow {
    onRequest(fetchIfStale())
    emitAll(
      store.data.onEach {
        if (it == null) {
          onRequest(fetchIfStale())
        }
      }
    )
  }

  override val status: StateFlow<FetchStatus>
    field = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

  override fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /** Joins a forced fetch that has not settled, else fetches now. See [requestFetch]. */
  private fun fetchNow(): Operation =
    actions.runOrJoin(
      Ordering.Keyed(key, OnPrevious.Cancel),
      Request.Forced,
      joins = { it == Request.Forced },
    ) {
      fetchOnce(forced = true)
    }

  /**
   * Single flight. Joins the check or fetch that is the newest command on this query's key, else
   * checks the stored time and fetches if stale.
   */
  private fun fetchIfStale(): Operation =
    actions.runOrJoin(Ordering.Keyed(key), Request.Check, joins = { it is Request }, ::checkStale)

  // The newest command on the key from here on, so a later request waits for it and checks the
  // store again instead of joining a fetch that started before the data changed
  override fun invalidate(): Operation = actions.run(Ordering.Keyed(key)) { store.invalidate() }

  override fun mutate(
    requireFresh: Boolean,
    block: suspend (store: QueryStore<T>) -> Unit,
  ): Operation =
    actions.run(Ordering.Keyed(key)) {
      if (requireFresh && isStale(store.data.first()?.fetchedAt)) {
        fetchOnce(forced = false)
      }
      block(store)
    }

  // Reads the store on this query's key, so it never races a fetch
  private suspend fun checkStale() {
    if (isStale(store.data.first()?.fetchedAt)) {
      fetchOnce(forced = false)
    }
  }

  private fun isStale(at: Instant?): Boolean {
    if (at == null || at <= QueryStore.INVALIDATED) {
      return true
    }
    val now = clock.now()
    // A time in the future means the clock moved back or the record is bad
    return at > now || freshness.isStale(at, now)
  }

  private suspend fun fetchOnce(forced: Boolean) {
    status.value = FetchStatus.Fetching(forced)
    try {
      val fetchedAt = clock.now()
      val value = fetch()
      // A fetch superseded while it ran never writes, even one whose fetch ignored cancellation
      currentCoroutineContext().ensureActive()
      store.write(value, fetchedAt)
      status.value = FetchStatus.Idle
    } catch (t: Throwable) {
      // Cancelled by a newer forced fetch, which owns the status
      currentCoroutineContext().ensureActive()
      status.value = FetchStatus.Failed(t)
      // The handle settles Failed and the failure reporter hears it
      throw t
    }
  }

  /** What a request on this query's key is, so a later request can tell whether to join it. */
  private enum class Request {
    Check,
    Forced,
  }
}
