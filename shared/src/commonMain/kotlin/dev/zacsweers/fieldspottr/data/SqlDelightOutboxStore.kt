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
import dev.zacsweers.fieldspottr.data.core.impl.UNKNOWN_REPLY
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
import okio.utf8Size

/**
 * Every outbox's rows in the app's database, one partition of the `outboxRow` table per name.
 * [queries] opens the generated queries on first use, for a database that opens lazily. A database
 * already open passes its queries to the other constructor.
 *
 * Rows outlive app updates, so change a payload class with care, as
 * [DataStoreOutboxStores][dev.zacsweers.fieldspottr.data.core.DataStoreOutboxStores] describes.
 *
 * Android reads each row through a cursor window of about 2 MB, and no query can read a row larger
 * than the window. So no row holds a payload with its key, or an answer, larger than
 * [maxTextBytes]. An insert above that throws, so the outbox undoes its change and fails its
 * handle. A larger answer fails to encode, so the outbox reports it and stores the answer as
 * unknown. A row an older version stored above the cap still cannot be read on Android until
 * [clearAll] deletes it.
 */
@OptIn(ExperimentalAtomicApi::class)
class SqlDelightOutboxStores(
  private val queries: suspend () -> OutboxRowQueries,
  private val io: CoroutineDispatcher,
  /**
   * Encodes each payload into its row. A binary format is stored as Base64, as [asCodec] describes.
   */
  private val payloadFormat: SerialFormat = DefaultPayloadFormat,
  /** The largest payload with its key, and the largest answer, a row holds, in UTF-8 bytes. */
  private val maxTextBytes: Int = MAX_OUTBOX_TEXT_BYTES,
) : OutboxStores {
  /** Over a database that is already open. */
  constructor(
    queries: OutboxRowQueries,
    io: CoroutineDispatcher,
    payloadFormat: SerialFormat = DefaultPayloadFormat,
    maxTextBytes: Int = MAX_OUTBOX_TEXT_BYTES,
  ) : this({ queries }, io, payloadFormat, maxTextBytes)

  /**
   * Answers live in the same rows as payloads, so they use the same format and cap. An answer above
   * the cap fails to encode, and the outbox then stores it as unknown.
   */
  override fun <R> replyCodec(serializer: KSerializer<R>): PayloadCodec<R> {
    val codec = serializer.asCodec(payloadFormat)
    return object : PayloadCodec<R> {
      override fun encode(payload: R): String {
        val encoded = codec.encode(payload)
        require(encoded.utf8Size() <= maxTextBytes) {
          "An answer of type ${serializer.descriptor.serialName} is larger than the outbox " +
            "row limit of $maxTextBytes UTF-8 bytes"
        }
        return encoded
      }

      override fun decode(encoded: String): R = codec.decode(encoded)
    }
  }

  // One store per name, so every outbox over a name shares what its store knows about rows that do
  // not decode, and each such row is reported once. Nothing observes it, so it needs no StateFlow.
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
      val created =
        SqlDelightOutboxStore(
          queries,
          name,
          serializer.asCodec(payloadFormat),
          io,
          maxTextBytes = maxTextBytes,
        )
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
      // Right after the delete, with no suspension between, so a cancelled caller cannot skip them.
      // A failed delete skips them, so the rows that did not decode stay reported.
      for (entry in opened.load().values) {
        entry.store.forgetUndecodable()
        entry.store.recordCleared()
      }
    }
  }
}

/**
 * Outbox rows in the app's database, kept across process death. Every outbox shares the `outboxRow`
 * table from OutboxRow.sq, and [outbox] identifies this outbox's rows, so a new outbox needs no
 * schema change. Concurrent writes from different keys rely on the driver to serialize them.
 *
 * Each row's payload decodes on its own, so a row that does not decode is handled as
 * [OutboxStore.onUndecodable] describes.
 *
 * [changes] names only the writes made through this store, as [OutboxChangeLog] describes, so build
 * one [SqlDelightOutboxStores] per database.
 *
 * [insert] throws for a payload and key larger than [maxTextBytes], and [update] stores a larger
 * answer as unknown, so no row outgrows Android's cursor window, as [SqlDelightOutboxStores]
 * describes.
 */
class SqlDelightOutboxStore<E>(
  private val queries: suspend () -> OutboxRowQueries,
  private val outbox: String,
  private val codec: PayloadCodec<E>,
  /** Runs the database calls, which block. */
  private val io: CoroutineDispatcher,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
  /** The largest payload with its key, and the largest answer, this store keeps, in UTF-8 bytes. */
  private val maxTextBytes: Int = MAX_OUTBOX_TEXT_BYTES,
) : OutboxStore<E> {
  // The rows this store cannot decode, found by its reads
  private val undecodable = UndecodableRows()
  // The rows this store's writes ended, for changes
  private val ended = OutboxChangeLog()

  override suspend fun insert(id: EntryId, key: String, payload: E) {
    currentCoroutineContext().ensureActive()
    // seq orders the rows. A stored id fails the UNIQUE constraint, which rolls the transaction
    // back. The insert runs to the end once started, as OutboxStore.insert requires.
    withContext(NonCancellable) {
      withContext(io) {
        val encoded = codec.encode(payload)
        // Before any database work, so a row too large to read back stores nothing
        val size = encoded.utf8Size() + key.utf8Size()
        require(size <= maxTextBytes) {
          "Outbox $outbox cannot store entry ${id.value}. Its payload and key are $size UTF-8 " +
            "bytes, and the limit is $maxTextBytes."
        }
        val queries = queries()
        queries.transaction {
          queries.pruneSettled(outbox, cutoff())
          queries.insert(id.value, outbox, key, encoded)
        }
      }
    }
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.isDone }
    return withContext(io) {
      val queries = queries()
      // Compares the decoded OutboxStatus, as DataStoreOutboxStore.update does. The row is read
      // outside the transaction, so the transaction below starts with its write, like every other
      // write in this store.
      val row = queries.byId(outbox, entry.id.value).executeAsOneOrNull()
      if (row == null || row.isExpired() || OutboxStatus(row.status, row.cause) != expected) {
        return@withContext false
      }
      // The set's codec refuses a larger answer before it gets here. A sender with a store of its
      // own encodes its answers itself, so a larger one is stored as unknown here.
      val reply =
        if (entry.reply != null && entry.reply.utf8Size() > maxTextBytes) {
          UNKNOWN_REPLY
        } else {
          entry.reply
        }
      val written = queries.transactionWithResult {
        // Deletes the expired rows first, so a row that expired since the read is gone and the
        // write below finds nothing
        queries.pruneSettled(outbox, cutoff())
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
          reply = reply,
          outbox = outbox,
          id = entry.id.value,
          expectedStatus = row.status,
          expectedCause = row.cause,
        )
        queries.changes().executeAsOne() > 0
      }
      // Right after the commit, as OutboxChangeLog requires. Only a write that ends the row's
      // attempt is named, so one to Queued or Sending is not.
      if (written && !entry.status.isSendable && entry.status != expected) {
        ended.record(setOf(entry.id))
      }
      written
    }
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> = flow {
    emitAll(queriesOnIo().pending(outbox).asFlow().map { withContext(io) { it.executeAsList() } })
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
      queriesOnIo().byId(outbox, id.value).asFlow().map {
        withContext(io) { it.executeAsOneOrNull() }
      }
    )
  }
    .map { row -> row?.takeUnless { it.isExpired() }?.toEntryOrNull() }
    .distinctUntilChanged()

  /**
   * One query per change. SQLite walks the outbox's entries in the `outboxRow_key` index, which
   * holds no payloads, and returns one row per key, so a change decodes about one payload per key.
   * A head that does not decode is stepped past with one more query.
   */
  override fun heads(): Flow<List<OutboxEntry<E>>> = flow {
    val queries = queriesOnIo()
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
   * The queries, opened on [io]. The first call may open the database, which blocks, so a flow that
   * needs the queries before it reads calls this instead of [queries].
   */
  private suspend fun queriesOnIo(): OutboxRowQueries = withContext(io) { queries() }

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

/**
 * The default largest payload or answer an outbox row holds, in UTF-8 bytes. Both at this size,
 * with the longest error text the outbox keeps, still fit in Android's cursor window of about 2 MB.
 */
internal const val MAX_OUTBOX_TEXT_BYTES = 900 * 1024
