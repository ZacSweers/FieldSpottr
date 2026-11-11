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
 * The stored values without their fetch times, or null while nothing is stored. Collecting it never
 * fetches, so a screen reads its query's `data` instead.
 */
val <T> QueryStore<T>.fetchedData: Flow<T?>
  get() = data.values()

/**
 * Where a [DataQuery] keeps its value and the time it was fetched. The query judges freshness from
 * that time, so a store that outlives the process keeps its data fresh across restarts. The store
 * alone decides what stays in memory, like the cap [inMemoryByKey] takes. Freshness never frees
 * anything.
 *
 * A query calls its store one write at a time. Different queries do not coordinate, so a store over
 * a shared resource, like a database, must handle concurrent calls.
 *
 * Calls run on the dispatcher of the query's data scope, usually `Dispatchers.Default`, so they
 * must not block it. A store that blocks, like a JDBC driver or file I/O, moves that work to an I/O
 * dispatcher it takes in its constructor. A store that only suspends, like DataStore, needs
 * nothing.
 */
interface QueryStore<T> {
  /** The stored value and its fetch time, or null when nothing is stored. Emits on every change. */
  val data: Flow<Fetched<T>?>

  /**
   * Stores [value], fetched at [fetchedAt], whatever is stored now. For an edit outside a fetch,
   * like a seed. A query's fetch uses [writeFetched].
   */
  suspend fun write(value: T, fetchedAt: Instant)

  /**
   * A fetch's write. Stores [value], fetched at [fetchedAt], unless [keepsStored] says the stored
   * value should stay. [keepsStored] gets the stored fetch time, and is not called when nothing is
   * stored. It is cheap and may run more than once, like a `MutableStateFlow.update` transform.
   *
   * The check and the write are one update, like one transaction. So when two queries share a
   * store, a fetch that read the server first never replaces what a later fetch stored meanwhile.
   */
  suspend fun writeFetched(
    value: T,
    fetchedAt: Instant,
    keepsStored: (storedAt: Instant) -> Boolean,
  )

  /** Removes the stored value, such as on logout. */
  suspend fun clear()

  /**
   * [writeFetched] for a value that is gone: removes the stored value unless [keepsStored] says it
   * should stay, in one update. A writer that shares the store, like an optimistic store whose
   * change deletes the value, uses it so it never removes what another writer stored meanwhile.
   */
  suspend fun clearFetched(keepsStored: (storedAt: Instant) -> Boolean)

  /** Keeps the stored value but marks it stale, so the next freshness check fetches. */
  suspend fun invalidate()

  companion object {
    /**
     * The fetch time of an invalidated value. Every freshness rule counts it as stale, and it
     * survives a store that keeps only epoch milliseconds. A custom store writes it to mark data
     * stale, and returns it for data with no usable fetch time.
     */
    val INVALIDATED: Instant = Instant.fromEpochSeconds(0)

    /**
     * The fetch time of a value built only from local changes, like an optimistic store's waiting
     * changes over a truth that a capped store evicted. It is stale, and a query whose data on
     * screen drops to it fetches again at once, as it does when the data goes away.
     */
    internal val UNFETCHED: Instant = Instant.fromEpochSeconds(-1)

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
    fun <K : Any, T> inMemoryByKey(maxEntries: Int = Int.MAX_VALUE): InMemoryQueryStores<K, T> =
      InMemoryQueryStores(maxEntries)

    /**
     * A store from its parts, for a source of truth that merges a fetch instead of replacing it.
     *
     * [write] serves [QueryStore.write] and [QueryStore.writeFetched]. In one update, like one
     * transaction, it reads the stored fetch time, asks `keepsStored` about it when something is
     * stored, and writes only when the answer is false. [clear] serves [QueryStore.clear] and
     * [QueryStore.clearFetched] the same way. The unconditional calls pass a `keepsStored` that is
     * always false.
     *
     * Without [invalidate], invalidating reads the value and writes it back with a stale fetch
     * time. That is two steps, so a store that other writers share can lose a write between them.
     * Such a store passes an [invalidate] that marks the value stale in one update.
     */
    fun <T> of(
      data: Flow<Fetched<T>?>,
      clear: suspend (keepsStored: (storedAt: Instant) -> Boolean) -> Unit,
      invalidate: (suspend () -> Unit)? = null,
      write:
        suspend (value: T, fetchedAt: Instant, keepsStored: (storedAt: Instant) -> Boolean) -> Unit,
    ): QueryStore<T> {
      val writer = write
      val clearer = clear
      val invalidator = invalidate
      return object : QueryStore<T> {
        override val data: Flow<Fetched<T>?> = data

        override suspend fun write(value: T, fetchedAt: Instant) =
          writer(value, fetchedAt, REPLACES_STORED)

        override suspend fun writeFetched(
          value: T,
          fetchedAt: Instant,
          keepsStored: (storedAt: Instant) -> Boolean,
        ) = writer(value, fetchedAt, keepsStored)

        override suspend fun clear() = clearer(REPLACES_STORED)

        override suspend fun clearFetched(keepsStored: (storedAt: Instant) -> Boolean) =
          clearer(keepsStored)

        override suspend fun invalidate() {
          if (invalidator != null) {
            invalidator()
          } else {
            data.first()?.let { writer(it.value, INVALIDATED, REPLACES_STORED) }
          }
        }
      }
    }

    /** The `keepsStored` of an unconditional write or clear, which replaces whatever is stored. */
    private val REPLACES_STORED: (Instant) -> Boolean = { false }
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

  override suspend fun writeFetched(
    value: T,
    fetchedAt: Instant,
    keepsStored: (storedAt: Instant) -> Boolean,
  ) {
    update { current ->
      if (current != null && keepsStored(current.fetchedAt)) {
        current
      } else {
        Fetched(value, fetchedAt)
      }
    }
  }

  override suspend fun clear() {
    update { null }
  }

  override suspend fun clearFetched(keepsStored: (storedAt: Instant) -> Boolean) {
    update { current ->
      if (current != null && keepsStored(current.fetchedAt)) {
        current
      } else {
        null
      }
    }
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
class InMemoryQueryStores<K : Any, T> internal constructor(private val maxEntries: Int) :
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

  /**
   * Once more keys than [maxEntries] hold a value, drops the key whose value was fetched longest
   * ago, skipping [kept].
   */
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
