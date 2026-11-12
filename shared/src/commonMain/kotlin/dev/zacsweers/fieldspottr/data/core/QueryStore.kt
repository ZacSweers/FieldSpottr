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

/**
 * A value, the time it was fetched, and whether it was marked stale since.
 *
 * Stored values must be immutable. Queries compare them with `==` to skip equal re-emissions, as
 * [DataQuery.state] describes, so a value type without a structural `equals` gets no such skip.
 */
data class Fetched<out T>(
  val value: T,
  /**
   * When the value was fetched. Null when it never was, like local changes over an evicted truth.
   */
  val fetchedAt: Instant?,
  /** Set by invalidate(). The next fetch's write clears it. */
  val invalidated: Boolean = false,
)

/**
 * Whether [a] and [b] show the same thing: the same fetch time and invalidated flag, and equal
 * values. The times and flags are compared first, so the values are compared with `==` only when
 * those match. [DataQuery.state], [DataQuery.stored] and [data] all skip a value that is the same
 * as the one before by this rule.
 */
internal fun sameFetched(a: Fetched<*>?, b: Fetched<*>?): Boolean {
  if (a === b) {
    return true
  }
  if (a == null || b == null) {
    return false
  }
  return a.fetchedAt == b.fetchedAt && a.invalidated == b.invalidated && a.value == b.value
}

/**
 * Whether a store keeps what it holds when it gets a conditional write or clear. It gets the stored
 * value's fetch time, null when it was never fetched, and its invalidated flag. It is cheap and may
 * run more than once, like a `MutableStateFlow.update` transform.
 */
typealias KeepsStored = (storedAt: Instant?, invalidated: Boolean) -> Boolean

/** Just the values, for readers that do not need the fetch time. */
fun <T> Flow<Fetched<T>?>.values(): Flow<T?> = map { it?.value }

/**
 * Where a [DataQuery] keeps its value, the time it was fetched and whether it was invalidated. The
 * query judges freshness from those, so a store that outlives the process keeps its data fresh
 * across restarts. The store alone decides what stays in memory, like the cap [inMemoryByKey]
 * takes. Freshness never frees anything.
 *
 * A store keeps what each write gives it: the value, its fetch time or none, and the invalidated
 * flag. A durable store keeps all three across a restart.
 *
 * A store that evicts values must not evict a key while its [data] is collected. A collected query
 * whose data goes away fetches again at once, so two such keys would then evict and refetch each
 * other without end.
 *
 * A query calls its store one write at a time. Different queries do not coordinate, so a store over
 * a shared resource, like a database, must handle concurrent calls.
 *
 * Calls run on the dispatcher of the query's data scope, usually `Dispatchers.Default`, so they
 * must not block it. A store that blocks, like a JDBC driver or file I/O, moves that work to an I/O
 * dispatcher it takes in its constructor. A store that only suspends, like DataStore, needs
 * nothing.
 *
 * A store implements [data], [writeFetched], [clearFetched] and [invalidate]. [write] and [clear]
 * call the conditional forms with a [KeepsStored] that is always false, and the `write` and
 * `writeFetched` extensions that take a value and a time call [write] and [writeFetched].
 *
 * A wrapper built with `by` delegation forwards [write] and [clear] straight to the wrapped store.
 * So a wrapper that changes how writes or clears behave overrides [write] and [clear] as well as
 * [writeFetched] and [clearFetched].
 */
interface QueryStore<T> {
  /** The stored value with its fetch time and flag, or null when nothing is stored. */
  val data: Flow<Fetched<T>?>

  /**
   * Stores [fetched], whatever is stored now. For an edit outside a fetch, like a seed. A query's
   * fetch uses [writeFetched], which this calls.
   */
  suspend fun write(fetched: Fetched<T>) {
    writeFetched(fetched) { _, _ -> false }
  }

  /**
   * A conditional write. Stores [fetched] unless [keepsStored] says the stored value should stay.
   * [keepsStored] gets the stored fetch time and invalidated flag, and is not called when nothing
   * is stored.
   *
   * The check and the write are one update, like one transaction. So when two queries share a
   * store, a fetch that read the server first never replaces what a later fetch stored meanwhile. A
   * query's fetch writes its value with a real fetch time and no invalidated flag. It keeps only a
   * stored value that is not invalidated and was fetched later than its own fetch but not ahead of
   * the clock. So it always replaces a value that was never fetched, was invalidated, or has a
   * fetch time ahead of the clock.
   */
  suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored)

  /** Removes the stored value, such as on logout. It calls [clearFetched]. */
  suspend fun clear() {
    clearFetched { _, _ -> false }
  }

  /**
   * [writeFetched] for a value that is gone: removes the stored value unless [keepsStored] says it
   * should stay, in one update. A writer that shares the store, like an optimistic store whose
   * change deletes the value, uses it so it never removes what another writer stored meanwhile.
   */
  suspend fun clearFetched(keepsStored: KeepsStored)

  /**
   * Keeps the stored value and its fetch time but sets its invalidated flag, so the next freshness
   * check fetches. The next fetch's write clears the flag.
   */
  suspend fun invalidate()

  companion object {
    /**
     * One value held in memory, lost with the process. [initial] seeds it, like a cached value with
     * no fetch time, `Fetched(value, fetchedAt = null)`, that shows at once and still fetches on
     * the first collect.
     */
    fun <T> inMemory(initial: Fetched<T>? = null): InMemoryQueryStore<T> = SingleValueStore(initial)

    /**
     * A value per key held in memory, lost with the process. [maxEntries] caps the keys that nobody
     * collects. Once more than [maxEntries] keys hold a value, a write drops the key nobody
     * collects that was fetched longest ago, or one never fetched first, and that key's query
     * fetches again the next time it is collected. A collected key is never dropped, so while more
     * keys are collected than [maxEntries], the store holds them all and goes over the cap. It
     * trims back down when a collector leaves.
     */
    fun <K : Any, T> inMemoryByKey(maxEntries: Int = Int.MAX_VALUE): InMemoryQueryStores<K, T> =
      InMemoryQueryStores(maxEntries)

    /**
     * A store from its parts, for a source of truth that merges a fetch instead of replacing it.
     *
     * [write] serves [QueryStore.writeFetched]. In one update, like one transaction, it reads the
     * stored fetch time and flag, asks `keepsStored` about them when something is stored, and
     * writes only when the answer is false. [clear] serves [QueryStore.clearFetched] the same way.
     * [QueryStore.write] and [QueryStore.clear] reach them with a `keepsStored` that is always
     * false.
     *
     * Without [invalidate], invalidating reads the value and writes it back with the invalidated
     * flag set. That is two steps, so a store that other writers share can lose a write between
     * them. Such a store passes an [invalidate] that sets the flag in one update.
     */
    fun <T> of(
      data: Flow<Fetched<T>?>,
      write: suspend (fetched: Fetched<T>, keepsStored: KeepsStored) -> Unit,
      clear: suspend (keepsStored: KeepsStored) -> Unit,
      invalidate: (suspend () -> Unit)? = null,
    ): QueryStore<T> {
      val writer = write
      val clearer = clear
      val invalidator = invalidate
      return object : QueryStore<T> {
        override val data: Flow<Fetched<T>?> = data

        override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) =
          writer(fetched, keepsStored)

        override suspend fun clearFetched(keepsStored: KeepsStored) = clearer(keepsStored)

        override suspend fun invalidate() {
          if (invalidator != null) {
            invalidator()
          } else {
            data.first()?.let { writer(it.copy(invalidated = true)) { _, _ -> false } }
          }
        }
      }
    }
  }
}

/** [QueryStore.write] of [value], fetched at [fetchedAt], or never fetched when that is null. */
suspend fun <T> QueryStore<T>.write(value: T, fetchedAt: Instant?) {
  write(Fetched(value, fetchedAt))
}

/**
 * A fetch's write: [QueryStore.writeFetched] of [value] fetched at [fetchedAt]. A fetch always has
 * a real time, and its write clears the invalidated flag.
 */
suspend fun <T> QueryStore<T>.writeFetched(
  value: T,
  fetchedAt: Instant,
  keepsStored: KeepsStored,
) {
  writeFetched(Fetched(value, fetchedAt), keepsStored)
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

  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    update { current ->
      if (current != null && keepsStored(current.fetchedAt, current.invalidated)) {
        current
      } else {
        fetched
      }
    }
  }

  override suspend fun clearFetched(keepsStored: KeepsStored) {
    update { current ->
      if (current != null && keepsStored(current.fetchedAt, current.invalidated)) {
        current
      } else {
        null
      }
    }
  }

  override suspend fun invalidate() {
    update { it?.copy(invalidated = true) }
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
 *
 * [maxEntries] caps the keys that nobody collects. A key counts as collected while anything
 * collects its store's `data`, like a query with a collector or a direct reader. Such a key is
 * never dropped, so while more keys are collected than the cap allows, the store holds them all and
 * goes over it. When a collector leaves and the store is over the cap, it drops keys nobody
 * collects until it is back under it, the key fetched longest ago first. The value and the count of
 * collectors per key live in one state, so a write and a collector that comes or goes never race.
 */
class InMemoryQueryStores<K : Any, T> internal constructor(private val maxEntries: Int) :
  (K) -> InMemoryQueryStore<T> {
  init {
    require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
  }

  private val stored = MutableStateFlow(KeyedValues<K, T>())

  /** [key]'s store. Each call returns a new view of the same value. */
  override fun invoke(key: K): InMemoryQueryStore<T> = KeyStore(key)

  /** Removes every key's value, such as on logout. Collected keys stay collected. */
  fun clear() {
    stored.update { it.copy(values = persistentHashMapOf()) }
  }

  /** How many keys hold a value. For tests, which check the cap. */
  internal val size: Int
    get() = stored.value.values.size

  /** One key's store, a view of that key in [stored]. */
  private inner class KeyStore(private val key: K) : InMemoryQueryStore<T>() {
    override val data: StateFlow<Fetched<T>?> = KeyState(key)

    override fun update(transform: (Fetched<T>?) -> Fetched<T>?) {
      stored.update { current ->
        val next = transform(current.values[key])
        if (next == null) {
          current.copy(values = current.values.removing(key))
        } else {
          current.copy(values = current.values.putting(key, next)).capped(kept = key)
        }
      }
    }
  }

  /**
   * These values with keys nobody collects dropped, the one fetched longest ago or never first,
   * until no more than [maxEntries] keys hold a value or only collected keys and [kept] are left.
   */
  private fun KeyedValues<K, T>.capped(kept: K?): KeyedValues<K, T> {
    if (values.size <= maxEntries) {
      return this
    }
    // A value never fetched counts as the oldest
    val droppable =
      values.entries
        .filter { it.key != kept && it.key !in collected }
        .sortedWith(compareBy(nullsFirst()) { it.value.fetchedAt })
    val extra = values.size - maxEntries
    if (droppable.isEmpty()) {
      return this
    }
    val trimmed = values.builder()
    droppable.take(extra).forEach { trimmed.remove(it.key) }
    return copy(values = trimmed.build())
  }

  /**
   * One key's value in [stored], as a state flow of its own. It reads the map when asked, so a view
   * needs no scope. Collecting it counts the key as collected until the collection ends.
   */
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private inner class KeyState(private val key: K) : StateFlow<Fetched<T>?> {
    override val value: Fetched<T>?
      get() = stored.value.values[key]

    override val replayCache: List<Fetched<T>?>
      get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Fetched<T>?>): Nothing {
      // Counted before the first read, so no write drops the key between the read and the count
      stored.update { it.collecting(key, 1) }
      try {
        stored.map { it.values[key] }.distinctUntilChanged().collect(collector)
        // State flows never complete, so neither does this view
        awaitCancellation()
      } finally {
        stored.update { it.collecting(key, -1).capped(kept = null) }
      }
    }
  }
}

/** Each key's value, and how many collectors each collected key has. */
private data class KeyedValues<K : Any, T>(
  val values: PersistentMap<K, Fetched<T>> = persistentHashMapOf(),
  val collected: PersistentMap<K, Int> = persistentHashMapOf(),
) {
  /** These values with [key]'s count of collectors moved by [by]. A count of 0 leaves the map. */
  fun collecting(key: K, by: Int): KeyedValues<K, T> {
    val count = (collected[key] ?: 0) + by
    return copy(
      collected =
        if (count <= 0) {
          collected.removing(key)
        } else {
          collected.putting(key, count)
        }
    )
  }
}
