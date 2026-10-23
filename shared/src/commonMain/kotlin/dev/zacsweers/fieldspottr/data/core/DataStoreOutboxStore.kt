// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** The whole log of one outbox, in a small file that DataStore rewrites atomically. */
@Serializable
data class OutboxLog(val rows: List<Row> = emptyList()) {
  @Serializable
  data class Row(
    val id: String,
    val key: String,
    val payload: String,
    val status: String,
    val cause: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val reverted: Boolean = false,
    /** Epoch millis when the current backoff ends. */
    val retryAt: Long? = null,
    /** Epoch millis once sent or discarded. Pruned after keepSettledFor. */
    val settledAt: Long? = null,
  )
}

object OutboxLogSerializer : OkioSerializer<OutboxLog> {
  override val defaultValue = OutboxLog()

  override suspend fun readFrom(source: BufferedSource): OutboxLog =
    Json.decodeFromString(OutboxLog.serializer(), source.readUtf8())

  override suspend fun writeTo(t: OutboxLog, sink: BufferedSink) {
    sink.writeUtf8(Json.encodeToString(OutboxLog.serializer(), t))
  }
}

/**
 * The DataStore behind one outbox. DataStore allows one active instance per file, so build it once
 * per outbox per process. A graph-scoped repository does this by construction. DataStore does its
 * file I/O in [scope], so give it a dispatcher that suits blocking I/O, like the app scope plus an
 * I/O dispatcher. Cancelling [scope] releases the file.
 */
fun outboxDataStore(
  scope: CoroutineScope,
  path: () -> Path,
  fileSystem: FileSystem = FileSystem.SYSTEM,
): DataStore<OutboxLog> =
  DataStoreFactory.create(
    storage = OkioStorage(fileSystem, OutboxLogSerializer, producePath = path),
    scope = scope,
  )

/**
 * One DataStore file per outbox under [directory]. Suits an outbox of a few dozen rows that rewrite
 * as a whole. [scope] needs a dispatcher that suits blocking I/O, as with [outboxDataStore].
 */
class DataStoreOutboxStores(
  private val scope: CoroutineScope,
  private val directory: Path,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : OutboxStores {
  override fun <E> open(name: String, codec: PayloadCodec<E>): OutboxStore<E> =
    DataStoreOutboxStore(outboxDataStore(scope, { directory / "$name.outbox" }, fileSystem), codec)
}

/** Outbox rows in a DataStore file, kept across process death. */
@OptIn(ExperimentalUuidApi::class)
class DataStoreOutboxStore<E>(
  /** From [outboxDataStore], or one the app wires itself. */
  private val dataStore: DataStore<OutboxLog>,
  private val codec: PayloadCodec<E>,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
) : OutboxStore<E> {

  override suspend fun insert(key: String, payload: E): EntryId {
    // List order is send order. The Uuid identifies the row everywhere.
    val id = EntryId(Uuid.random().toString())
    val row =
      OutboxLog.Row(
        id = id.value,
        key = key,
        payload = codec.encode(payload),
        status = OutboxStatus.Queued.storedName,
      )
    // updateData runs one transform at a time, so the whole log changes atomically
    dataStore.updateData { log -> OutboxLog(rows = log.rows.filterNot { it.isExpired() } + row) }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.status.isSettled }
    var written = false
    dataStore.updateData { log ->
      val index = log.rows.indexOfFirst { it.id == entry.id.value }
      val row = log.rows.getOrNull(index)
      // The compare failed, so write nothing
      if (row == null || row.status != expected.storedName) {
        written = false
        return@updateData log
      }
      written = true
      val rows = log.rows.toMutableList()
      rows[index] =
        row.copy(
          status = entry.status.storedName,
          cause = entry.status.storedCause,
          attempts = entry.attempts,
          lastError = entry.lastError,
          reverted = entry.reverted,
          retryAt = entry.retryAt?.toEpochMilliseconds(),
          settledAt = settledAt,
        )
      log.copy(rows = rows)
    }
    return written
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> =
    dataStore.data
      .map { log -> log.rows.filter { it.settledAt == null }.map { it.toEntry() } }
      .distinctUntilChanged()

  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
    dataStore.data
      .map { log -> log.rows.firstOrNull { it.id == id.value } }
      .map { row -> row?.takeUnless { it.isExpired() }?.toEntry() }
      .distinctUntilChanged()

  /** Settled more than keepSettledFor ago. It reads as absent until the next insert drops it. */
  private fun OutboxLog.Row.isExpired(): Boolean = settledAt != null && settledAt < cutoff()

  private fun cutoff(): Long = (clock.now() - keepSettledFor).toEpochMilliseconds()

  private fun OutboxLog.Row.toEntry() =
    OutboxEntry(
      id = EntryId(id),
      key = key,
      payload = codec.decode(payload),
      status = OutboxStatus(status, cause),
      attempts = attempts,
      lastError = lastError,
      reverted = reverted,
      retryAt = retryAt?.let { Instant.fromEpochMilliseconds(it) },
    )
}
