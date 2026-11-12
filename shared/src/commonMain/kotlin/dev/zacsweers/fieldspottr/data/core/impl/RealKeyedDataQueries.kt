// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.QueryState
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** How many keys still hold a query. For tests, which cannot see eviction any other way. */
internal val KeyedDataQueries<*, *>.keysHeld: Int
  get() = (this as RealKeyedDataQueries<*, *>).keysHeld

/** How many keys have a hold-off record. For tests, which cannot see the records any other way. */
internal val KeyedDataQueries<*, *>.holdOffsHeld: Int
  get() = (this as RealKeyedDataQueries<*, *>).holdOffsHeld

/** How many keys the newest invalidateAll() remembers. For tests, which cannot see the set. */
internal val KeyedDataQueries<*, *>.keysRemembered: Int
  get() = (this as RealKeyedDataQueries<*, *>).keysRemembered

/**
 * What a key's query gets from its set, beside the holds on its entry: the hold-off record and the
 * [KeyedDataQueries.invalidateAll] rule, which both outlive the query.
 */
internal class KeyContext(
  val holds: QueryHolds,
  val holdOff: FetchHoldOff,
  val staleMark: StaleMark,
)

/**
 * The newest [KeyedDataQueries.invalidateAll] call: its clock time [at], its [ticket], and the keys
 * that have written a fetch started after it that the time rule alone would still read as stale.
 * The mark no longer covers those keys.
 */
private class AllInvalidated<K : Any>(
  val at: Instant,
  val ticket: Long,
  val refreshed: PersistentSet<K>,
)

/**
 * [KeyedDataQueries] that builds each key's query with [build] on first use and drops it once no
 * caller and no unfinished work holds it, as [KeyEntry] describes. [build] gets the holds the query
 * takes on its entry, and records this set keeps for the key across queries. [clock] times
 * [invalidateAll] calls.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealKeyedDataQueries<K : Any, T>(
  private val clock: Clock,
  private val build: (key: K, context: KeyContext) -> RealDataQuery<T>,
) : KeyedDataQueries<K, T> {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val entries = AtomicReference(persistentHashMapOf<K, KeyEntry<T>>())
  // Each key whose automatic fetches failed and have not succeeded since. It outlives the key's
  // query, so a query rebuilt inside the window or after a decline still holds off. Updated by
  // compare-and-set only.
  private val holdOffs = AtomicReference(HoldOffs<K>())
  // The newest invalidateAll() call, or null before the first. Updated by compare-and-set only.
  private val allInvalidated = AtomicReference<AllInvalidated<K>?>(null)
  // Orders invalidateAll() calls and fetch starts across keys, since clock times can tie
  private val tickets = AtomicLong(0)

  val keysHeld: Int
    get() = entries.load().size

  /** How many keys have a hold-off record. For tests, which check that spent records go. */
  val holdOffsHeld: Int
    get() = holdOffs.load().size

  /** How many keys the newest invalidateAll() remembers. For tests, which check the set's bound. */
  val keysRemembered: Int
    get() = allInvalidated.load()?.refreshed?.size ?: 0

  override fun state(key: K): Flow<QueryState<T>> = flow {
    val entry = acquire(key)
    try {
      emitAll(entry.query.state)
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

  /**
   * Records the call first, so a key whose query is built or dropped while the keys in use are
   * listed is covered either way. A new record forgets which keys fetched since the last call. The
   * record with the higher ticket wins, so an older call on another thread that stores last never
   * undoes a newer one.
   */
  override fun invalidateAll(): Operation {
    val ticket = tickets.incrementAndFetch()
    val record = AllInvalidated<K>(clock.now(), ticket, persistentSetOf())
    allInvalidated.update { current ->
      if (current != null && current.ticket > ticket) {
        current
      } else {
        record
      }
    }
    return invalidate(entries.load().keys)
  }

  override fun requestFetchAll(force: Boolean): Operation = requestFetch(entries.load().keys, force)

  /** The entry for [key], held until [KeyEntry.release]. Builds it on first use. */
  private fun acquire(key: K): KeyEntry<T> {
    while (true) {
      val current = entries.load()
      var entry = current[key]
      if (entry == null) {
        // Building a query starts nothing, so the copy that loses this race is simply dropped
        val created = KeyEntry({ holds -> build(key, contextFor(key, holds)) }) { remove(key, it) }
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

  /** What [key]'s query gets from this set, with [holds] on its entry. */
  private fun contextFor(key: K, holds: QueryHolds): KeyContext =
    KeyContext(holds, KeyHoldOff(key), KeyStaleMark(key))

  /**
   * [key]'s view of [allInvalidated]. Until a fetch that started after the newest call writes, the
   * key's data counts as stale when it was fetched at or before the call's time, or when that time
   * is ahead of the clock, since a clock set back cannot order the two. A fetch that started after
   * the call and writes a value that this time rule still reads as stale, which only a clock set
   * back or a tie can cause, is remembered. From then on the key's data is judged by freshness
   * alone. So the set remembers no keys while the clock moves forward. Before the first call,
   * fetches take no tickets and nothing is tracked.
   *
   * The set remembers at most [MAX_REMEMBERED_KEYS] keys. When a new one would pass that cap, it
   * forgets the key remembered longest ago, and the time rule applies to that key again. So its
   * data counts as stale and its next check fetches once more. The cap costs an extra fetch, never
   * stale data shown as fresh.
   */
  private inner class KeyStaleMark(private val key: K) : StaleMark {
    override fun fetchStarted(): Long =
      if (allInvalidated.load() == null) {
        0
      } else {
        tickets.incrementAndFetch()
      }

    override fun fetchWritten(started: Long, fetchedAt: Instant, now: Instant) {
      allInvalidated.update { current ->
        val remembers =
          current != null &&
            started > current.ticket &&
            key !in current.refreshed &&
            byTime(current, fetchedAt, now)
        if (remembers) {
          var refreshed = current.refreshed.adding(key)
          // Insertion ordered, so the first is the key remembered longest ago
          if (refreshed.size > MAX_REMEMBERED_KEYS) {
            refreshed = refreshed.removing(refreshed.first())
          }
          AllInvalidated(current.at, current.ticket, refreshed)
        } else {
          current
        }
      }
    }

    override fun isStale(fetchedAt: Instant, now: Instant): Boolean {
      val current = allInvalidated.load() ?: return false
      if (key in current.refreshed) {
        return false
      }
      return byTime(current, fetchedAt, now)
    }

    /** The time rule alone: fetched at or before the call, or the call ahead of the clock. */
    private fun byTime(current: AllInvalidated<K>, fetchedAt: Instant, now: Instant): Boolean =
      fetchedAt <= current.at || current.at > now
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

  /**
   * [key]'s hold-off record in [holdOffs]. A cleared record leaves the map, and every read or write
   * drops the records of all keys that are spent, so keys that fail and are never fetched again do
   * not stay for the life of the process. Declined records are never spent, so [HoldOffs] keeps at
   * most [MAX_DECLINED_HOLD_OFFS] of them.
   */
  private inner class KeyHoldOff(private val key: K) : FetchHoldOff() {
    override fun load(now: Instant): HoldOff? {
      pruneSpent(now)
      return holdOffs.load()[key]
    }

    override fun store(next: HoldOff?, now: Instant) {
      holdOffs.update { it.withoutSpent(now).storing(key, next) }
    }
  }

  private fun pruneSpent(now: Instant) {
    holdOffs.update { it.withoutSpent(now) }
  }
}

/**
 * A keyed set's hold-off records. [windows] holds the records with a window, which time prunes.
 * [declined] holds the records whose policy declined, oldest decline first. Time never prunes
 * those, so a new one past [MAX_DECLINED_HOLD_OFFS] drops the one declined longest ago. That key's
 * next check fetches on its own once more, and a failure it then declines records it again. A key
 * is in at most one of the two maps.
 */
private class HoldOffs<K>(
  val windows: PersistentMap<K, HoldOff> = persistentHashMapOf(),
  val declined: PersistentMap<K, HoldOff> = persistentMapOf(),
) {
  val size: Int
    get() = windows.size + declined.size

  operator fun get(key: K): HoldOff? = windows[key] ?: declined[key]

  /** These records with [key]'s replaced by [next], or cleared when null. */
  fun storing(key: K, next: HoldOff?): HoldOffs<K> {
    val windows = windows.removing(key)
    // Removed first, so a key declined again moves to the end
    var declined = declined.removing(key)
    return when {
      next == null -> HoldOffs(windows, declined)
      next.declined -> {
        declined = declined.putting(key, next)
        if (declined.size > MAX_DECLINED_HOLD_OFFS) {
          declined = declined.removing(declined.keys.first())
        }
        HoldOffs(windows, declined)
      }
      else -> HoldOffs(windows.putting(key, next), declined)
    }
  }

  /** These records without the spent windows. */
  fun withoutSpent(now: Instant): HoldOffs<K> {
    if (windows.values.none { it.isSpent(now) }) {
      return this
    }
    val kept = windows.builder().apply { values.removeAll { it.isSpent(now) } }.build()
    return HoldOffs(kept, declined)
  }
}

/** The most declined hold-off records a keyed set keeps, as [HoldOffs] describes. */
internal const val MAX_DECLINED_HOLD_OFFS = 1024

/**
 * The most keys an [KeyedDataQueries.invalidateAll] record remembers as fetched after it, as the
 * set's stale mark describes. A key it forgets counts as stale again.
 */
internal const val MAX_REMEMBERED_KEYS = 1024

/**
 * A key's query and what holds it: each caller from acquire to release, the query's watcher while
 * it runs, and each operation started until its command finishes, automatic checks and cancelled
 * commands included. The last release drops the entry, so a key's next query never runs beside
 * unfinished work from the last one, and nothing has to look for idle entries later.
 */
@OptIn(ExperimentalAtomicApi::class)
private class KeyEntry<T>(
  build: (QueryHolds) -> RealDataQuery<T>,
  private val onDropped: (KeyEntry<T>) -> Unit,
) : QueryHolds {
  // Callers and unfinished operations, or -1 once dropped. A dropped entry is never acquired again.
  private val holds = AtomicInt(0)
  val query = build(this)

  override fun tryAcquire(): Boolean {
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
  override fun holdUntilSettled(operation: Operation) {
    holds.incrementAndFetch()
    operation.invokeOnSettled(::release)
  }

  /** Lets go of one hold, and drops the entry when it was the last. */
  override fun release() {
    if (holds.decrementAndFetch() == 0 && holds.compareAndSet(0, -1)) {
      onDropped(this)
    }
  }
}
