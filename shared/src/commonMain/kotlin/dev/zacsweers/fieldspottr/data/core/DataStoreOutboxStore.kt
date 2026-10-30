// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
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

private object OutboxLogSerializer : OkioSerializer<OutboxLog> {
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
 * as a whole. [scope] needs a dispatcher that suits blocking I/O, as with [outboxDataStore]. A name
 * becomes one file name in [directory], with any character but letters, digits, `-`, `_` and `.`
 * written as `%XX`, so no name reaches outside it.
 *
 * DataStore allows one active instance per file, so build one set per directory. Each name gets one
 * store for as long as this set lives, and an outbox built again for that name gets the same store.
 */
@OptIn(ExperimentalAtomicApi::class)
class DataStoreOutboxStores(
  private val scope: CoroutineScope,
  private val directory: Path,
  /**
   * Encodes each payload inside the file, which is JSON either way. A binary format is stored as
   * Base64, as [asCodec] describes.
   */
  private val payloadFormat: SerialFormat = Json,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : OutboxStores {
  // Compare-and-set only. Nothing observes it.
  private val opened = AtomicReference(persistentHashMapOf<String, Lazy<OutboxStore<*>>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      val entry =
        current[name]
          ?: lazy {
            DataStoreOutboxStore(
              outboxDataStore(scope, { directory / "${fileName(name)}.outbox" }, fileSystem),
              serializer.asCodec(payloadFormat),
            )
          }
      // Only the entry that wins the map builds its DataStore, so the file never has two
      if (entry === current[name] || opened.compareAndSet(current, current.putting(name, entry))) {
        @Suppress("UNCHECKED_CAST")
        return entry.value as OutboxStore<E>
      }
    }
  }
}

/**
 * [name] as one file name. Letters, digits, `-`, `_` and `.` stay as they are, and every other byte
 * of its UTF-8 form is written as `%XX`, so no name reaches outside the directory. A name of only
 * dots has its dots encoded too, since `.` and `..` name directories.
 */
private fun fileName(name: String): String {
  val onlyDots = name.isNotEmpty() && name.all { it == '.' }
  return buildString {
    for (byte in name.encodeToByteArray()) {
      val char = (byte.toInt() and 0xFF).toChar()
      val plain = (char.isLetterOrDigit() && char.code < 0x80) || char in "-_"
      if (plain || (char == '.' && !onlyDots)) {
        append(char)
      } else {
        append('%')
        append(HEX[(byte.toInt() shr 4) and 0xF])
        append(HEX[byte.toInt() and 0xF])
      }
    }
  }
}

private const val HEX = "0123456789ABCDEF"

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
    currentCoroutineContext().ensureActive()
    // updateData runs one transform at a time, so the whole log changes atomically. It runs to the
    // end once started, so a caller cancelled meanwhile still gets the id of the row it stored, and
    // a throw always means nothing was stored.
    withContext(NonCancellable) {
      dataStore.updateData { log -> OutboxLog(rows = log.rows.filterNot { it.isExpired() } + row) }
    }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    val settledAt = clock.now().toEpochMilliseconds().takeIf { entry.status.isSettled }
    var written = false
    dataStore.updateData { log ->
      val index = log.rows.indexOfFirst { it.id == entry.id.value }
      val row = log.rows.getOrNull(index)
      // Compares what the row means, so a stale failure cause misses and a status a newer version
      // wrote matches the Failed it reads as. The compare failed, so write nothing.
      if (row == null || OutboxStatus(row.status, row.cause) != expected) {
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
