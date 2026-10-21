// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Instant
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A value and the time it was fetched. */
data class Fetched<out T>(val value: T, val fetchedAt: Instant)

/** Just the values, for readers that do not need the fetch time. */
fun <T> Flow<Fetched<T>?>.values(): Flow<T?> = map { it?.value }

/**
 * The fetch time an invalidated value is stored with. It is stale under every freshness rule and
 * survives a store that keeps only epoch milliseconds.
 */
internal val INVALIDATED: Instant = Instant.fromEpochSeconds(0)

/**
 * Where a [DataQuery] keeps its data, along with when it was fetched. The query decides freshness
 * from the stored time, so a store that outlives the process keeps its data fresh across restarts.
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
    /** One value held in memory, lost with the process. */
    fun <T> inMemory(): QueryStore<T> {
      val stored = MutableStateFlow<Fetched<T>?>(null)
      return of(
        stored.asStateFlow(),
        clear = { stored.value = null },
        invalidate = { stored.update { it?.copy(fetchedAt = INVALIDATED) } },
      ) { value, fetchedAt ->
        stored.value = Fetched(value, fetchedAt)
      }
    }

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

/** In-memory stores for many keys, from [QueryStore.inMemoryByKey]. */
class InMemoryStores<K : Any, T> internal constructor(private val maxEntries: Int) :
  (K) -> QueryStore<T> {
  init {
    require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
  }

  private val entries = MutableStateFlow(persistentHashMapOf<K, Fetched<T>>())

  override fun invoke(key: K): QueryStore<T> =
    QueryStore.of(
      entries.map { it[key] }.distinctUntilChanged(),
      clear = { entries.update { it.removing(key) } },
      invalidate = {
        entries.update { current ->
          val stored = current[key] ?: return@update current
          current.putting(key, stored.copy(fetchedAt = INVALIDATED))
        }
      },
    ) { value, fetchedAt ->
      entries.update { current ->
        val next = current.putting(key, Fetched(value, fetchedAt))
        if (next.size <= maxEntries) {
          return@update next
        }
        val oldest = next.entries.filter { it.key != key }.minBy { it.value.fetchedAt }.key
        next.removing(oldest)
      }
    }

  /** Removes every key's value, such as on logout. */
  fun clear() {
    entries.value = persistentHashMapOf()
  }
}
