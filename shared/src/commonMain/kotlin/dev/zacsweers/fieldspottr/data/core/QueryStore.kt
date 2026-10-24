// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Instant
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A value and the time it was fetched. */
data class Fetched<out T>(val value: T, val fetchedAt: Instant)

/** Just the values, for readers that do not need the fetch time. */
fun <T> Flow<Fetched<T>?>.values(): Flow<T?> = map { it?.value }

/**
 * Where a [DataQuery] keeps its data, along with when it was fetched. The query decides freshness
 * from the stored time, so a store that outlives the process keeps its data fresh across restarts.
 * The store alone decides what stays in memory and for how long, such as the cap that
 * [inMemoryByKey] takes. Freshness never frees anything.
 *
 * One query calls its store one write at a time, since its writes run in order on its own key.
 * Different queries do not coordinate, so stores over one shared resource, like a database, see
 * concurrent calls and must allow them or take turns themselves.
 *
 * Calls come from the query's runner, on the dispatcher of the scope the query was built with,
 * usually `Dispatchers.Default`, so they must not block that thread. A store whose calls block,
 * like a JDBC driver or file I/O, moves them with `withContext` onto a dispatcher it was given. A
 * store whose calls only suspend, like DataStore or an in-memory store, needs nothing.
 */
interface QueryStore<T> {
  /** The stored value and its fetch time, or null when nothing is stored. Emits on every change. */
  val data: Flow<Fetched<T>?>

  /** Stores [value], fetched at [fetchedAt], in place of what was there. */
  suspend fun write(value: T, fetchedAt: Instant)

  /** Removes the stored value, such as on logout. */
  suspend fun clear()

  /** Keeps the stored value but marks it stale, so the next freshness check fetches. */
  suspend fun invalidate()

  companion object {
    /**
     * The fetch time an invalidated value is stored with. It is stale under every freshness rule
     * and survives a store that keeps only epoch milliseconds. A custom store writes it to mark its
     * data stale, and reads it back for data with no usable fetch time.
     */
    val INVALIDATED: Instant = Instant.fromEpochSeconds(0)

    /**
     * One value held in memory, lost with the process. [initial] seeds it, like a cached value
     * stored with [INVALIDATED] so it shows at once and still fetches on the first collect.
     */
    fun <T> inMemory(initial: Fetched<T>? = null): InMemoryQueryStore<T> = SingleValueStore(initial)

    /**
     * A value per key held in memory, lost with the process. Once more than [maxEntries] keys hold
     * a value, a write drops the key fetched longest ago, and that key's query fetches again the
     * next time it is collected. Keep [maxEntries] above the number of keys collected at once.
     */
    fun <K : Any, T> inMemoryByKey(maxEntries: Int = Int.MAX_VALUE): InMemoryStores<K, T> =
      InMemoryStores(maxEntries)

    /**
     * A store from its parts, for a source of truth that merges a fetch rather than replacing it.
     * Without [invalidate], invalidating rewrites the stored value with a stale fetch time.
     */
    fun <T> of(
      data: Flow<Fetched<T>?>,
      clear: suspend () -> Unit,
      invalidate: (suspend () -> Unit)? = null,
      write: suspend (value: T, fetchedAt: Instant) -> Unit,
    ): QueryStore<T> {
      val writer = write
      val clearer = clear
      val invalidator = invalidate
      return object : QueryStore<T> {
        override val data: Flow<Fetched<T>?> = data

        override suspend fun write(value: T, fetchedAt: Instant) = writer(value, fetchedAt)

        override suspend fun clear() = clearer()

        override suspend fun invalidate() {
          if (invalidator != null) {
            invalidator()
          } else {
            data.first()?.let { writer(it.value, INVALIDATED) }
          }
        }
      }
    }
  }
}

/**
 * A value held in memory: the one from [QueryStore.inMemory], or one key's from
 * [QueryStore.inMemoryByKey]. Code that owns the store can read [data] without collecting it and
 * edit it in place. Screens still read the query's data, since only collecting that runs the
 * staleness check.
 */
sealed class InMemoryQueryStore<T> : QueryStore<T> {
  /** The stored value, readable without collecting. Reading it never fetches. */
  abstract override val data: StateFlow<Fetched<T>?>

  /**
   * Changes the stored value in place, such as for an optimistic edit. [transform] may run more
   * than once, as with [MutableStateFlow.update]. The change skips the query's ordering, so a fetch
   * already in flight can replace it. In a store from [QueryStore.inMemoryByKey], a change that
   * stores a value counts toward the cap, like a write.
   */
  abstract fun update(transform: (Fetched<T>?) -> Fetched<T>?)

  override suspend fun write(value: T, fetchedAt: Instant) {
    update { Fetched(value, fetchedAt) }
  }

  override suspend fun clear() {
    update { null }
  }

  override suspend fun invalidate() {
    update { it?.copy(fetchedAt = QueryStore.INVALIDATED) }
  }
}

/** The store from [QueryStore.inMemory], which keeps its value in a state flow of its own. */
private class SingleValueStore<T>(initial: Fetched<T>?) : InMemoryQueryStore<T>() {
  override val data: StateFlow<Fetched<T>?>
    field = MutableStateFlow(initial)

  override fun update(transform: (Fetched<T>?) -> Fetched<T>?) {
    data.update(transform)
  }
}

/**
 * In-memory stores for many keys, from [QueryStore.inMemoryByKey]. Every key's value lives in one
 * map, and each store is a view of its key, so stores for one key always agree.
 */
class InMemoryStores<K : Any, T> internal constructor(private val maxEntries: Int) :
  (K) -> InMemoryQueryStore<T> {
  init {
    require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
  }

  private val stored = MutableStateFlow(persistentHashMapOf<K, Fetched<T>>())

  /** [key]'s store. Each call returns a new view of the same value. */
  override fun invoke(key: K): InMemoryQueryStore<T> = KeyStore(key)

  /** Removes every key's value, such as on logout. */
  fun clear() {
    stored.value = persistentHashMapOf()
  }

  /** One key's store, a view of that key in [stored]. */
  private inner class KeyStore(private val key: K) : InMemoryQueryStore<T>() {
    override val data: StateFlow<Fetched<T>?> = KeyState(stored, key)

    override fun update(transform: (Fetched<T>?) -> Fetched<T>?) {
      stored.update { current ->
        val next = transform(current[key])
        if (next == null) {
          current.removing(key)
        } else {
          current.putting(key, next).capped(kept = key)
        }
      }
    }
  }

  /** Drops the key fetched longest ago, other than [kept], once more than the cap hold a value. */
  private fun PersistentMap<K, Fetched<T>>.capped(kept: K): PersistentMap<K, Fetched<T>> {
    if (size <= maxEntries) {
      return this
    }
    val oldest = this.entries.filter { it.key != kept }.minBy { it.value.fetchedAt }.key
    return removing(oldest)
  }
}

/**
 * One key's value in [stored], as a state flow of its own. It reads the map when asked, so a view
 * needs no scope.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class KeyState<K : Any, T>(
  private val stored: StateFlow<Map<K, Fetched<T>>>,
  private val key: K,
) : StateFlow<Fetched<T>?> {
  override val value: Fetched<T>?
    get() = stored.value[key]

  override val replayCache: List<Fetched<T>?>
    get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<Fetched<T>?>): Nothing {
    stored.map { it[key] }.distinctUntilChanged().collect(collector)
    // State flows never complete, so neither does this view
    awaitCancellation()
  }
}
