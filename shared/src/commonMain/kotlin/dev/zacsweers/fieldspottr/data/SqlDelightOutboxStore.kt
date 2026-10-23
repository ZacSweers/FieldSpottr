// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.OutboxRow
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.isSettled
import dev.zacsweers.fieldspottr.data.core.storedCause
import dev.zacsweers.fieldspottr.data.core.storedName
import dev.zacsweers.metro.SuspendLazy
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Every outbox's rows in the app's database, one partition of the `outboxRow` table per name. */
class SqlDelightOutboxStores(
  private val db: SuspendLazy<FSDatabase>,
  private val io: CoroutineDispatcher,
) : OutboxStores {
  override fun <E> open(name: String, codec: PayloadCodec<E>): OutboxStore<E> =
    SqlDelightOutboxStore(db, name, codec, io)
}

/**
 * Outbox rows in the app's database, kept across process death. Every outbox shares the `outboxRow`
 * table from OutboxRow.sq, and [outbox] identifies this outbox's rows, so a new outbox needs no
 * schema change. Concurrent writes from different keys rely on the driver to serialize them.
 */
@OptIn(ExperimentalUuidApi::class)
class SqlDelightOutboxStore<E>(
  private val db: SuspendLazy<FSDatabase>,
  private val outbox: String,
  private val codec: PayloadCodec<E>,
  /** Runs the database calls, which block. */
  private val io: CoroutineDispatcher,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
) : OutboxStore<E> {

  override suspend fun insert(key: String, payload: E): EntryId {
    // seq orders the rows. The Uuid identifies the row everywhere.
    val id = EntryId(Uuid.random().toString())
    withContext(io) {
      val database = db.await()
      database.transaction {
        database.outboxRowQueries.pruneSettled(outbox, cutoff())
        database.outboxRowQueries.insert(id.value, outbox, key, codec.encode(payload))
      }
    }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.status.isSettled }
    return withContext(io) {
      val queries = db.await().outboxRowQueries
      // The WHERE on the expected status is the compare. changes() reports whether it matched.
      queries.transactionWithResult {
        queries.compareAndSet(
          status = entry.status.storedName,
          cause = entry.status.storedCause,
          attempts = entry.attempts.toLong(),
          lastError = entry.lastError,
          reverted =
            if (entry.reverted) {
              1L
            } else {
              0L
            },
          retryAt = entry.retryAt?.toEpochMilliseconds(),
          settledAt = settledAt,
          outbox = outbox,
          id = entry.id.value,
          expected = expected.storedName,
        )
        queries.changes().executeAsOne() > 0
      }
    }
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> = flow {
    emitAll(db.await().outboxRowQueries.pending(outbox).asFlow().mapToList(io))
  }
    .map { rows -> rows.map { it.toEntry() } }
    .distinctUntilChanged()

  // An id that is not one of ours finds no row, so its handle settles Cancelled
  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> = flow {
    emitAll(db.await().outboxRowQueries.byId(outbox, id.value).asFlow().mapToOneOrNull(io))
  }
    .map { row -> row?.takeUnless { it.isExpired() }?.toEntry() }
    .distinctUntilChanged()

  /** Settled more than keepSettledFor ago. It reads as absent until the next insert deletes it. */
  private fun OutboxRow.isExpired(): Boolean = settledAt != null && settledAt < cutoff()

  private fun cutoff(): Long = (clock.now() - keepSettledFor).toEpochMilliseconds()

  private fun OutboxRow.toEntry() =
    OutboxEntry(
      id = EntryId(id),
      key = orderingKey,
      payload = codec.decode(payload),
      status = OutboxStatus(status, cause),
      attempts = attempts.toInt(),
      lastError = lastError,
      reverted = reverted != 0L,
      retryAt = retryAt?.let { Instant.fromEpochMilliseconds(it) },
    )
}
