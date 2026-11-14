// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer

/**
 * Rows in memory, lost with the process. A row that is [isDone] stays observable for
 * [keepSettledFor], so a handle that re-attaches soon after still sees its outcome, and the next
 * write prunes it. Payloads are kept as objects, so no row is ever unreadable.
 */
internal fun <E> inMemoryOutboxStore(
  keepSettledFor: Duration = 1.minutes,
  time: TimeSource = TimeSource.Monotonic,
): OutboxStore<E> = InMemoryOutboxStore(keepSettledFor, time)

/**
 * An [OutboxStores] that keeps every outbox in memory, with one store per name for as long as this
 * set lives. It is the default store set, and the one tests and previews use. Rows hold each
 * payload as the object itself, so these stores use the serializer only to check the payload type.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryOutboxStores : OutboxStores {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val opened =
    AtomicReference(persistentHashMapOf<String, OpenedStore<InMemoryOutboxStore<*>>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      current[name]?.let {
        @Suppress("UNCHECKED_CAST")
        return it.checked(name, serializer) as OutboxStore<E>
      }
      // An unused in-memory store holds nothing, so the copy that loses this race is simply dropped
      val created = InMemoryOutboxStore<E>(1.minutes, TimeSource.Monotonic)
      val entry = OpenedStore<InMemoryOutboxStore<*>>(serializer.descriptor, created)
      if (opened.compareAndSet(current, current.putting(name, entry))) {
        return created
      }
    }
  }

  // Nothing but the stores this set hands out holds rows
  override suspend fun clearAll() {
    for (entry in opened.load().values) {
      entry.store.clear()
    }
  }
}

internal class InMemoryOutboxStore<E>(
  private val keepSettledFor: Duration,
  private val time: TimeSource,
) : OutboxStore<E> {
  private val rows = MutableStateFlow(Rows<E>())

  override suspend fun insert(id: EntryId, key: String, payload: E) {
    val row = OutboxEntry(id, key, payload, OutboxStatus.Queued)
    rows.update { current ->
      val pruned = current.pruned()
      require(id !in pruned.all) { "Row ${id.value} is already stored" }
      // A new row ends nothing, so changes does not name it
      pruned.changed(
        emptySet(),
        all = pruned.all.putting(id, Row(row, settledAt = null)),
        byKey = pruned.byKey.putting(key, (pruned.byKey[key] ?: persistentSetOf()).adding(id)),
      )
    }
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    var written = false
    rows.update { current ->
      val row = current.all[entry.id]
      written = row != null && row.entry.status == expected
      if (row == null || !written) {
        current
      } else {
        val updated =
          row.entry.copy(
            status = entry.status,
            attempts = entry.attempts,
            lastError = entry.lastError,
            reverted = entry.reverted,
            retryAt = entry.retryAt,
            committed = entry.committed,
            sentAt = entry.sentAt,
            reply = entry.reply,
          )
        val pruned = current.pruned()
        if (updated.status.isSettled) {
          // A Sent row waits in all until it is committed, and only then gets a settled time
          val settledAt = time.markNow().takeIf { updated.isDone }
          val key = updated.key
          val waiting = pruned.byKey[key]?.removing(entry.id)
          pruned.changed(
            setOf(entry.id).takeIf { updated.status != row.entry.status }.orEmpty(),
            all = pruned.all.putting(entry.id, Row(updated, settledAt)),
            byKey =
              if (waiting.isNullOrEmpty()) {
                pruned.byKey.removing(key)
              } else {
                pruned.byKey.putting(key, waiting)
              },
            settled =
              if (settledAt == null) {
                pruned.settled
              } else {
                pruned.settled.removing(entry.id).putting(entry.id, settledAt)
              },
          )
        } else {
          val ended =
            setOf(entry.id)
              .takeIf { updated.status.endsAttempt && updated.status != row.entry.status }
              .orEmpty()
          pruned.changed(ended, all = pruned.all.putting(entry.id, Row(updated, null)))
        }
      }
    }
    return written
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> =
    rows
      .map { current -> current.all.values.map { it.entry }.filterNot { it.isDone } }
      .distinctUntilChanged()

  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
    rows
      .map { current -> current.all[id]?.takeUnless { it.isExpired() }?.entry }
      .distinctUntilChanged()

  /**
   * Each write records the rows it ended or deleted, so this names them without reading any row. A
   * collector that fell further behind than [Rows.log] reaches gets [OutboxChange.Unknown].
   */
  override fun changes(): Flow<OutboxChange> = flow {
    var seen: Long? = null
    rows.collect { current ->
      val last = seen
      seen = current.version
      when {
        last == null -> emit(OutboxChange.Unknown)
        current.version == last -> Unit
        else -> {
          val since = current.log.filter { it.version > last }
          if (since.size.toLong() == current.version - last) {
            emit(OutboxChange.Rows(since.flatMapTo(HashSet()) { it.ids }))
          } else {
            emit(OutboxChange.Unknown)
          }
        }
      }
    }
  }

  // Reads only each key's waiting rows up to its head, so a change costs about one row per key
  override fun heads(): Flow<List<OutboxEntry<E>>> =
    rows
      .map { current -> current.byKey.keys.mapNotNull { current.headOf(it) } }
      .distinctUntilChanged()

  override suspend fun head(key: String): OutboxEntry<E>? = rows.value.headOf(key)

  // Rows are objects in memory, so a scan costs no decoding
  override suspend fun owed(key: String): List<OutboxEntry<E>> =
    rows.value.all.values.mapNotNull { row -> row.entry.takeIf { it.key == key && it.isOwed } }

  /** Drops every row, settled ones included, so each handle reads its row as absent. */
  fun clear() {
    rows.update { current ->
      current.changed(
        current.all.keys,
        all = persistentMapOf(),
        byKey = persistentMapOf(),
        settled = persistentMapOf(),
      )
    }
  }

  /** [key]'s oldest row that is Queued or Sending. Failed rows before it are skipped. */
  private fun Rows<E>.headOf(key: String): OutboxEntry<E>? {
    for (id in byKey[key].orEmpty()) {
      val row = all[id]?.entry ?: continue
      if (row.status.isSendable) {
        return row
      }
    }
    return null
  }

  /**
   * These rows without the settled ones that expired. [Rows.settled] is in settle order, so this
   * stops at the first row that has not expired, and costs nothing while none has.
   */
  private fun Rows<E>.pruned(): Rows<E> {
    var all = all
    var settled = settled
    val removed = HashSet<EntryId>()
    for ((id, settledAt) in this.settled) {
      if (settledAt.elapsedNow() < keepSettledFor) {
        break
      }
      all = all.removing(id)
      settled = settled.removing(id)
      removed += id
    }
    return if (removed.isEmpty()) {
      this
    } else {
      changed(removed, all = all, settled = settled)
    }
  }

  private fun Row<E>.isExpired(): Boolean =
    settledAt != null && settledAt.elapsedNow() >= keepSettledFor

  private class Row<E>(val entry: OutboxEntry<E>, val settledAt: TimeMark?)

  /**
   * Every row and two indexes over them. The maps keep insertion order, which is send order.
   *
   * @property all every row, settled ones included until they expire.
   * @property byKey each key's rows that are neither Sent nor Discarded, oldest first. A key with
   *   none has no entry.
   * @property settled when each row that is [isDone] settled, oldest first, for pruning.
   * @property version how many writes ended or deleted rows so far.
   * @property log the rows each of the newest such writes ended or deleted, oldest first, for
   *   [changes].
   */
  private class Rows<E>(
    val all: PersistentMap<EntryId, Row<E>> = persistentMapOf(),
    val byKey: PersistentMap<String, PersistentSet<EntryId>> = persistentMapOf(),
    val settled: PersistentMap<EntryId, TimeMark> = persistentMapOf(),
    val version: Long = 0,
    val log: PersistentList<Change> = persistentListOf(),
  ) {
    /**
     * These rows after a write that ended or deleted [ids], with the write recorded in [log]. A
     * write that ended nothing leaves [log] as it is.
     */
    fun changed(
      ids: Set<EntryId>,
      all: PersistentMap<EntryId, Row<E>> = this.all,
      byKey: PersistentMap<String, PersistentSet<EntryId>> = this.byKey,
      settled: PersistentMap<EntryId, TimeMark> = this.settled,
    ): Rows<E> {
      if (ids.isEmpty()) {
        return Rows(all, byKey, settled, version, log)
      }
      val next = version + 1
      var log = log.adding(Change(next, ids))
      if (log.size > MAX_CHANGES) {
        log = log.removingAt(0)
      }
      return Rows(all, byKey, settled, next, log)
    }
  }

  /** One write's [version] and the rows it ended or deleted. */
  private class Change(val version: Long, val ids: Set<EntryId>)
}

// The newest writes a store records for collectors of changes that fall behind
private const val MAX_CHANGES = 64
