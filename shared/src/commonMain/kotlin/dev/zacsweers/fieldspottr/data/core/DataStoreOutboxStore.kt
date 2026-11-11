// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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

/**
 * Reads and writes the log as JSON. Fields this version does not know, like ones a newer version
 * added, are ignored. A log that does not parse at all throws [CorruptionException], which the
 * DataStore's corruption handler turns into an empty log.
 */
private object OutboxLogSerializer : OkioSerializer<OutboxLog> {
  private val json = Json { ignoreUnknownKeys = true }

  override val defaultValue = OutboxLog()

  override suspend fun readFrom(source: BufferedSource): OutboxLog {
    val text = source.readUtf8()
    if (text.isBlank()) {
      return OutboxLog()
    }
    return try {
      json.decodeFromString(OutboxLog.serializer(), text)
    } catch (e: SerializationException) {
      throw CorruptionException("Unreadable outbox log", e)
    } catch (e: IllegalArgumentException) {
      throw CorruptionException("Unreadable outbox log", e)
    }
  }

  override suspend fun writeTo(t: OutboxLog, sink: BufferedSink) {
    sink.writeUtf8(json.encodeToString(OutboxLog.serializer(), t))
  }
}

/**
 * The DataStore behind one outbox. DataStore allows one active instance per file, so build it once
 * per outbox per process. A graph-scoped repository does this by construction. DataStore does its
 * file I/O in [scope], so give it a dispatcher that suits blocking I/O, like the app scope plus an
 * I/O dispatcher. Cancelling [scope] releases the file.
 *
 * A file that does not parse as a log at all, like one cut short by a full disk, is replaced with
 * an empty log. Its rows are lost then, so their changes never send and their reverts never run.
 * The rest of the outbox keeps working. A single row whose payload no longer decodes is kept, as
 * [DataStoreOutboxStore] describes.
 */
fun outboxDataStore(
  scope: CoroutineScope,
  path: () -> Path,
  fileSystem: FileSystem = FileSystem.SYSTEM,
): DataStore<OutboxLog> =
  DataStoreFactory.create(
    storage = OkioStorage(fileSystem, OutboxLogSerializer, producePath = path),
    corruptionHandler = ReplaceFileCorruptionHandler { OutboxLog() },
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
 *
 * Rows outlive app updates, so change a payload class with care. A new field needs a default value.
 * A renamed field needs `@JsonNames` with the old name, or a default. The default format ignores
 * fields the class no longer has. A row that still fails to decode is left out of every read and
 * reported once, and it stays stored until [clearAll].
 */
@OptIn(ExperimentalAtomicApi::class)
class DataStoreOutboxStores(
  private val scope: CoroutineScope,
  private val directory: Path,
  /**
   * Encodes each payload inside the file, which is JSON either way. A binary format is stored as
   * Base64, as [asCodec] describes.
   */
  private val payloadFormat: SerialFormat = DefaultPayloadFormat,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : OutboxStores {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val opened =
    AtomicReference(persistentHashMapOf<String, OpenedStore<Lazy<DataStoreOutboxStore<*>>>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      current[name]?.let {
        @Suppress("UNCHECKED_CAST")
        return it.checked(name, serializer).value as OutboxStore<E>
      }
      val store = lazy {
        DataStoreOutboxStore(
          outboxDataStore(scope, { directory / "${fileName(name)}.outbox" }, fileSystem),
          serializer.asCodec(payloadFormat),
        )
      }
      val entry = OpenedStore<Lazy<DataStoreOutboxStore<*>>>(serializer.descriptor, store)
      // Only the entry that wins the map builds its DataStore, so the file never has two
      if (opened.compareAndSet(current, current.putting(name, entry))) {
        return store.value
      }
    }
  }

  /**
   * Clears every outbox file under [directory]. A store this set has built in this process owns its
   * file through a DataStore, and only that DataStore may write the file, so such a store is
   * cleared through it. A file left behind by an earlier process, which no store here has opened,
   * has no DataStore and is deleted directly. The deletes run on the caller's dispatcher.
   */
  override suspend fun clearAll() {
    val built = opened.load().mapValues { it.value.store }.filterValues { it.isInitialized() }
    val ownedFiles = built.keys.mapTo(HashSet()) { "${fileName(it)}.outbox" }
    for (path in fileSystem.listOrNull(directory).orEmpty()) {
      if (path.name.endsWith(".outbox") && path.name !in ownedFiles) {
        fileSystem.delete(path, mustExist = false)
      }
    }
    for (entry in built.values) {
      entry.value.clear()
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

/**
 * Outbox rows in a DataStore file, kept across process death. Each row's payload decodes on its
 * own. A row that does not decode, like one stored before its payload class changed, is left out of
 * [entries], [entry] and [heads], and handed on through [onUndecodable]. It stays in the file.
 */
@OptIn(ExperimentalUuidApi::class)
class DataStoreOutboxStore<E>(
  /** From [outboxDataStore], or one the app wires itself. */
  private val dataStore: DataStore<OutboxLog>,
  private val codec: PayloadCodec<E>,
  private val keepSettledFor: Duration = 1.minutes,
  private val clock: Clock = Clock.System,
) : OutboxStore<E> {
  // The rows this store failed to decode, so reads skip them without decoding again
  private val undecodable = UndecodableRows()

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
      // Compares the decoded OutboxStatus, not the raw columns: a Failed row whose cause changed
      // since the expectation was built no longer matches, and a status written by a newer app
      // version still matches the Failed it decodes to. On a mismatch the log is left as it is.
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
      .map { log -> log.rows.filter { it.settledAt == null }.mapNotNull { it.toEntryOrNull() } }
      .distinctUntilChanged()

  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
    dataStore.data
      .map { log -> log.rows.firstOrNull { it.id == id.value } }
      .map { row -> row?.takeUnless { it.isExpired() }?.toEntryOrNull() }
      .distinctUntilChanged()

  /**
   * DataStore reads and parses the whole log on each change either way, so this walks it and
   * decodes only each key's head. A row known not to decode is skipped without decoding.
   */
  override fun heads(): Flow<List<OutboxEntry<E>>> =
    dataStore.data.map { log -> headsOf(log, key = null) }.distinctUntilChanged()

  override suspend fun head(key: String): OutboxEntry<E>? =
    headsOf(dataStore.data.first(), key).firstOrNull()

  override fun onUndecodable(report: (id: EntryId, cause: Throwable) -> Unit): AutoCloseable =
    undecodable.listen(report)

  /**
   * The head of each key in [log], or of [key] alone when it is not null. A row that fails to
   * decode is skipped, and one known not to decode is skipped without decoding.
   */
  private fun headsOf(log: OutboxLog, key: String?): List<OutboxEntry<E>> {
    val heads = LinkedHashMap<String, OutboxEntry<E>>()
    for (row in log.rows) {
      val sendable = row.status == SENDING || row.status == QUEUED
      val otherKey = key != null && row.key != key
      if (!sendable || otherKey || row.key in heads) {
        continue
      }
      row.toEntryOrNull()?.let { heads[row.key] = it }
    }
    return heads.values.toList()
  }

  /** Drops every row, settled ones included, so each handle reads its row as absent. */
  suspend fun clear() {
    dataStore.updateData { OutboxLog() }
    undecodable.clear()
  }

  /** Settled more than keepSettledFor ago. It reads as absent until the next insert drops it. */
  private fun OutboxLog.Row.isExpired(): Boolean = settledAt != null && settledAt < cutoff()

  private fun cutoff(): Long = (clock.now() - keepSettledFor).toEpochMilliseconds()

  /**
   * This row as an entry, or null when its payload does not decode. A row known not to decode is
   * skipped without decoding. A row neither Sent nor Discarded that fails to decode is recorded in
   * [undecodable].
   */
  private fun OutboxLog.Row.toEntryOrNull(): OutboxEntry<E>? {
    if (id in undecodable) {
      return null
    }
    val decoded = codec.decodeCatching(payload)
    val failure = decoded.exceptionOrNull()
    if (failure != null) {
      if (!OutboxStatus(status, cause).isSettled) {
        undecodable.found(id, failure)
      }
      return null
    }
    return OutboxEntry(
      id = EntryId(id),
      key = key,
      payload = decoded.getOrThrow(),
      status = OutboxStatus(status, cause),
      attempts = attempts,
      lastError = lastError,
      reverted = reverted,
      retryAt = retryAt?.let { Instant.fromEpochMilliseconds(it) },
    )
  }
}

private val QUEUED = OutboxStatus.Queued.storedName
private val SENDING = OutboxStatus.Sending.storedName
