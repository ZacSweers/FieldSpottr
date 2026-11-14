// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import dev.zacsweers.fieldspottr.OutboxRow
import dev.zacsweers.fieldspottr.OutboxRowQueries
import dev.zacsweers.fieldspottr.data.core.DefaultPayloadFormat
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OpenedStore
import dev.zacsweers.fieldspottr.data.core.OutboxChange
import dev.zacsweers.fieldspottr.data.core.OutboxChangeLog
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.UndecodableRows
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.data.core.decodeCatching
import dev.zacsweers.fieldspottr.data.core.isDone
import dev.zacsweers.fieldspottr.data.core.isSendable
import dev.zacsweers.fieldspottr.data.core.storedCause
import dev.zacsweers.fieldspottr.data.core.storedName
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentHashMapOf
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

/**
 * Every outbox's rows in the app's database, one partition of the `outboxRow` table per name.
 * [queries] opens the generated queries on first use, for a database that opens lazily. A database
 * already open passes its queries to the other constructor.
 *
 * Rows outlive app updates, so change a payload class with care. A new field needs a default value.
 * A renamed field needs `@JsonNames` with the old name, or a default. The default format ignores
 * fields the class no longer has. A row that still fails to decode is left out of every read and
 * reported once, and it stays stored until [clearAll].
 */
@OptIn(ExperimentalAtomicApi::class)
class SqlDelightOutboxStores(
  private val queries: suspend () -> OutboxRowQueries,
  private val io: CoroutineDispatcher,
  /**
   * Encodes each payload into its row. A binary format is stored as Base64, as [asCodec] describes.
   */
  private val payloadFormat: SerialFormat = DefaultPayloadFormat,
) : OutboxStores {
  /** Over a database that is already open. */
  constructor(
    queries: OutboxRowQueries,
    io: CoroutineDispatcher,
    payloadFormat: SerialFormat = DefaultPayloadFormat,
  ) : this({ queries }, io, payloadFormat)

  // Answers live in the same rows as payloads, so they use the same format
  override fun <R> replyCodec(serializer: KSerializer<R>): PayloadCodec<R> =
    serializer.asCodec(payloadFormat)

  // One store per name, so every outbox over a name shares what its store knows about rows that do
  // not decode, and each such row is reported once. Updated by compare-and-set only. Nothing
  // observes it, so it needs no StateFlow.
  private val opened =
    AtomicReference(persistentHashMapOf<String, OpenedStore<SqlDelightOutboxStore<*>>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      current[name]?.let {
        @Suppress("UNCHECKED_CAST")
        return it.checked(name, serializer) as OutboxStore<E>
      }
      // A store holds no resources until used, so the copy that loses this race is dropped
      val created = SqlDelightOutboxStore(queries, name, serializer.asCodec(payloadFormat), io)
      val entry = OpenedStore<SqlDelightOutboxStore<*>>(serializer.descriptor, created)
      if (opened.compareAndSet(current, current.putting(name, entry))) {
        return created
      }
    }
  }

  // One table holds every outbox's rows, so one statement clears them all
  override suspend fun clearAll() {
    withContext(io) {
      queries().deleteAll()
      // Right after the delete, with no suspension between, so a cancelled caller cannot skip it
      for (entry in opened.load().values) {
        entry.store.recordCleared()
      }
    }
    for (entry in opened.load().values) {
      entry.store.forgetUndecodable()
    }
  }
}

/**
 * Outbox rows in the app's database, kept across process death. Every outbox shares the `outboxRow`
 * table from OutboxRow.sq, and [outbox] identifies this outbox's rows, so a new outbox needs no
 * schema change. Concurrent writes from different keys rely on the driver to serialize them.
 *
 * Each row's payload decodes on its own. A row that does not decode, like one stored before its
 * payload class changed, is left out of [entries], [entry] and [heads], and handed on through
 * [onUndecodable]. It stays in the table.
 *
 * [changes] names the rows that writes through this store ended, from a record in memory. A write
 * that reaches the table another way, like through a second store set over the same database, is
 * not named, so build one [SqlDelightOutboxStores] per database.
 */
class SqlDelightOutboxStore<E>(
  private val queries: suspend () -> OutboxRowQueries,
  private val outbox: String,
  private val codec: PayloadCodec<E>,
  /** Runs the database calls, which block. */
  private val io: CoroutineDispatcher,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
) : OutboxStore<E> {
  // The rows this store cannot decode, found by its reads
  private val undecodable = UndecodableRows()
  // The rows this store's writes ended, for changes
  private val ended = OutboxChangeLog()

  override suspend fun insert(id: EntryId, key: String, payload: E) {
    currentCoroutineContext().ensureActive()
    // seq orders the rows, and the id identifies the row everywhere. A stored id fails the UNIQUE
    // constraint, which rolls the transaction back. The insert runs to the end once started, so a
    // throw always means nothing was stored.
    withContext(NonCancellable) {
      withContext(io) {
        val queries = queries()
        queries.transaction {
          queries.pruneSettled(outbox, cutoff())
          queries.insert(id.value, outbox, key, codec.encode(payload))
        }
      }
    }
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.isDone }
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
      val written = queries.transactionWithResult {
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
          committed =
            if (entry.committed) {
              1L
            } else {
              0L
            },
          sentAt = entry.sentAt?.toEpochMilliseconds(),
          reply = entry.reply,
          outbox = outbox,
          id = entry.id.value,
          expectedStatus = row.status,
          expectedCause = row.cause,
        )
        queries.changes().executeAsOne() > 0
      }
      // Right after the commit, with no suspension between, so a cancelled caller cannot skip it.
      // Only a write that ends the row's attempt is named, so one to Queued or Sending is not.
      if (written && !entry.status.isSendable && entry.status != expected) {
        ended.record(setOf(entry.id))
      }
      written
    }
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> = flow {
    emitAll(queries().pending(outbox).asFlow().map { withContext(io) { it.executeAsList() } })
  }
    .map { rows -> rows.mapNotNull { it.toEntryOrNull() } }
    .distinctUntilChanged()

  override suspend fun owed(): List<OutboxEntry<E>> =
    withContext(io) { queries().owed(outbox).executeAsList().mapNotNull { it.toEntryOrNull() } }

  override suspend fun owed(key: String): List<OutboxEntry<E>> =
    withContext(io) {
      queries().owedOnKey(outbox, key).executeAsList().mapNotNull { it.toEntryOrNull() }
    }

  // An id that is not one of ours finds no row, so its handle settles Cancelled
  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> = flow {
    emitAll(
      queries().byId(outbox, id.value).asFlow().map { withContext(io) { it.executeAsOneOrNull() } }
    )
  }
    .map { row -> row?.takeUnless { it.isExpired() }?.toEntryOrNull() }
    .distinctUntilChanged()

  /**
   * One query per change. SQLite walks the outbox's entries in the `outboxRow_key` index, which
   * holds no payloads, and returns one row per key, so a change decodes about one payload per key.
   * A head that does not decode is stepped past with one more query, and a row known not to decode
   * is stepped past without decoding.
   */
  override fun heads(): Flow<List<OutboxEntry<E>>> = flow {
    val queries = queries()
    emitAll(
      queries.heads(outbox).asFlow().map { query ->
        withContext(io) { query.executeAsList().mapNotNull { row -> readableFrom(queries, row) } }
      }
    )
  }
    .distinctUntilChanged()

  override suspend fun head(key: String): OutboxEntry<E>? =
    withContext(io) {
      val queries = queries()
      queries.nextSendable(outbox, key, after = -1).executeAsOneOrNull()?.let { row ->
        readableFrom(queries, row)
      }
    }

  override fun changes(): Flow<OutboxChange> = ended.changes()

  override fun onUndecodable(report: (id: EntryId, cause: Throwable) -> Unit): AutoCloseable =
    undecodable.listen(report)

  /** Forgets the rows found not to decode, after every row was deleted. */
  internal fun forgetUndecodable() {
    undecodable.clear()
  }

  /** Tells collectors of [changes] that any row may be gone, after every row was deleted. */
  internal fun recordCleared() {
    ended.recordAll()
  }

  /**
   * [row] as an entry, or else the first later sendable row on its key that decodes. The extra
   * queries block, so callers run this on [io].
   */
  private fun readableFrom(queries: OutboxRowQueries, row: OutboxRow): OutboxEntry<E>? {
    var next: OutboxRow? = row
    while (next != null) {
      next.toEntryOrNull()?.let {
        return it
      }
      next = queries.nextSendable(outbox, next.orderingKey, next.seq).executeAsOneOrNull()
    }
    return null
  }

  /** Settled more than keepSettledFor ago. It reads as absent until the next insert deletes it. */
  private fun OutboxRow.isExpired(): Boolean = settledAt != null && settledAt < cutoff()

  private fun cutoff(): Long = (clock.now() - keepSettledFor).toEpochMilliseconds()

  /**
   * This row as an entry, or null when its payload does not decode. A row known not to decode is
   * skipped without decoding. A row that is not done and fails to decode is recorded in
   * [undecodable].
   */
  private fun OutboxRow.toEntryOrNull(): OutboxEntry<E>? {
    if (id in undecodable) {
      return null
    }
    val decoded = codec.decodeCatching(payload)
    val failure = decoded.exceptionOrNull()
    if (failure != null) {
      if (settledAt == null) {
        undecodable.found(id, failure)
      }
      return null
    }
    return OutboxEntry(
      id = EntryId(id),
      key = orderingKey,
      payload = decoded.getOrThrow(),
      status = OutboxStatus(status, cause),
      attempts = attempts.toInt(),
      lastError = lastError,
      reverted = reverted != 0L,
      retryAt = retryAt?.let { Instant.fromEpochMilliseconds(it) },
      committed = committed != 0L,
      sentAt = sentAt?.let { Instant.fromEpochMilliseconds(it) },
      reply = reply,
    )
  }
}
