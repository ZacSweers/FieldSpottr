// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.zacsweers.fieldspottr.OutboxRow
import dev.zacsweers.fieldspottr.OutboxRowQueries
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.data.core.isSettled
import dev.zacsweers.fieldspottr.data.core.storedCause
import dev.zacsweers.fieldspottr.data.core.storedName
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.json.Json

/**
 * Every outbox's rows in the app's database, one partition of the `outboxRow` table per name.
 * [queries] opens the generated queries on first use, for a database that opens lazily. A database
 * already open passes its queries to the other constructor.
 */
class SqlDelightOutboxStores(
  private val queries: suspend () -> OutboxRowQueries,
  private val io: CoroutineDispatcher,
  /**
   * Encodes each payload into its row. A binary format is stored as Base64, as [asCodec] describes.
   */
  private val payloadFormat: SerialFormat = Json,
) : OutboxStores {
  /** Over a database that is already open. */
  constructor(
    queries: OutboxRowQueries,
    io: CoroutineDispatcher,
    payloadFormat: SerialFormat = Json,
  ) : this({ queries }, io, payloadFormat)

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> =
    SqlDelightOutboxStore(queries, name, serializer.asCodec(payloadFormat), io)

  // One table holds every outbox's rows, so one statement clears them all
  override suspend fun clearAll() {
    withContext(io) { queries().deleteAll() }
  }
}

/**
 * Outbox rows in the app's database, kept across process death. Every outbox shares the `outboxRow`
 * table from OutboxRow.sq, and [outbox] identifies this outbox's rows, so a new outbox needs no
 * schema change. Concurrent writes from different keys rely on the driver to serialize them.
 */
@OptIn(ExperimentalUuidApi::class)
class SqlDelightOutboxStore<E>(
  private val queries: suspend () -> OutboxRowQueries,
  private val outbox: String,
  private val codec: PayloadCodec<E>,
  /** Runs the database calls, which block. */
  private val io: CoroutineDispatcher,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
) : OutboxStore<E> {

  override suspend fun insert(key: String, payload: E): EntryId {
    currentCoroutineContext().ensureActive()
    // seq orders the rows. The Uuid identifies the row everywhere.
    val id = EntryId(Uuid.random().toString())
    // Runs to the end once started, so a caller cancelled meanwhile still gets the id of the row it
    // stored, and a throw always means nothing was stored
    withContext(NonCancellable) {
      withContext(io) {
        val queries = queries()
        queries.transaction {
          queries.pruneSettled(outbox, cutoff())
          queries.insert(id.value, outbox, key, codec.encode(payload))
        }
      }
    }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.status.isSettled }
    return withContext(io) {
      val queries = queries()
      // Compares the decoded OutboxStatus, not the raw columns: a Failed row whose cause changed
      // since the expectation was built no longer matches, and a status written by a newer app
      // version still matches the Failed it decodes to. The row is read outside the transaction, so
      // the transaction below starts with its write, like every other write in this store.
      val row = queries.byId(outbox, entry.id.value).executeAsOneOrNull()
      if (row == null || OutboxStatus(row.status, row.cause) != expected) {
        return@withContext false
      }
      queries.transactionWithResult {
        // The WHERE on what was read makes the write a compare-and-set. changes() reports it.
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
          expectedStatus = row.status,
          expectedCause = row.cause,
        )
        queries.changes().executeAsOne() > 0
      }
    }
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> = flow {
    emitAll(queries().pending(outbox).asFlow().mapToList(io))
  }
    .map { rows -> rows.map { it.toEntry() } }
    .distinctUntilChanged()

  // An id that is not one of ours finds no row, so its handle settles Cancelled
  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> = flow {
    emitAll(queries().byId(outbox, id.value).asFlow().mapToOneOrNull(io))
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
