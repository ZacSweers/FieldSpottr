// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.OutboxRowQueries
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OLD_CHANGE
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.OutboxChange
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.TaggedFormat
import dev.zacsweers.fieldspottr.data.core.UnreadableEntryException
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.data.core.assertOneTypePerName
import dev.zacsweers.fieldspottr.data.core.assertUnreadableRowIsHandled
import dev.zacsweers.fieldspottr.data.core.enqueue
import dev.zacsweers.fieldspottr.data.core.exchangingSender
import dev.zacsweers.fieldspottr.data.core.impl.UNKNOWN_REPLY
import dev.zacsweers.fieldspottr.data.core.replying
import dev.zacsweers.fieldspottr.data.core.sender
import dev.zacsweers.fieldspottr.data.core.testing.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitNamed
import dev.zacsweers.fieldspottr.data.core.testing.changesIn
import dev.zacsweers.fieldspottr.data.core.testing.discardDirectly
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.metro.suspendLazy
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Rule

class SqlDelightOutboxStoreTest : OutboxStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  private lateinit var clock: TestClock

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    return SqlDelightOutboxStore(
      queries = { temporaryDatabase.db().outboxRowQueries },
      outbox = "test",
      codec = String.serializer().asCodec(Json),
      // Real threads, so the concurrency test reaches the driver
      io = Dispatchers.IO,
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advanceStoreTime(duration: Duration) {
    clock.shift(duration)
  }

  // What SqlDelightOutboxStores.clearAll does for each store it opened
  override suspend fun TestScope.clearAll(store: OutboxStore<String>) {
    temporaryDatabase.db().outboxRowQueries.deleteAll()
    (store as SqlDelightOutboxStore<String>).recordCleared()
  }

  // A JSON object where the codec expects a string
  override suspend fun TestScope.insertUnreadable(
    store: OutboxStore<String>,
    key: String,
  ): EntryId {
    val id = EntryId("unreadable")
    temporaryDatabase.db().outboxRowQueries.insert(id.value, "test", key, """{"a":1}""", null)
    return id
  }

  @Test
  fun `the store set encodes payloads in its format`() = runTest {
    val queries: suspend () -> OutboxRowQueries = { temporaryDatabase.db().outboxRowQueries }
    val store =
      SqlDelightOutboxStores(queries, Dispatchers.IO, payloadFormat = TaggedFormat)
        .open("notes", String.serializer())

    store.insertNew("k", "hello")

    assertThat(store.entries().first().single().payload).isEqualTo("hello")
    // A second store over the same rows that returns each payload as stored
    val stored =
      object : PayloadCodec<String> {
        override fun encode(payload: String) = payload

        override fun decode(encoded: String) = encoded
      }
    val raw = SqlDelightOutboxStore(queries, "notes", stored, Dispatchers.IO)
    assertThat(raw.entries().first().single().payload).isEqualTo("tagged:\"hello\"")
  }

  @Test
  fun `the store set opens a name again for an equal payload type only`() {
    assertOneTypePerName(
      SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)
    )
  }

  @Test
  fun `a status a newer version wrote reads as failed and can be requeued`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    val store =
      SqlDelightOutboxStore(
        { db.await().outboxRowQueries },
        "future",
        String.serializer().asCodec(Json),
        Dispatchers.IO,
      )
    val id = store.insertNew("k", "a")
    // As a newer version would write it
    db
      .await()
      .outboxRowQueries
      .compareAndSet(
        status = "archived",
        cause = null,
        attempts = 0,
        lastError = null,
        reverted = 0,
        retryAt = null,
        settledAt = null,
        committed = 0,
        sentAt = null,
        reply = null,
        outbox = "future",
        id = id.value,
        expectedStatus = "queued",
        expectedCause = null,
      )
    val row = checkNotNull(store.entry(id).first())

    assertThat(row.status).isInstanceOf<OutboxStatus.Failed>()
    assertThat(store.update(row.copy(status = OutboxStatus.Queued), expected = row.status)).isTrue()
    assertThat(store.entry(id).first()?.status).isEqualTo(OutboxStatus.Queued)
  }

  @Test
  fun `a status a newer version wrote is owed on its key like a failed row`() = runTest {
    val queries = temporaryDatabase.db().outboxRowQueries
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    store.insertNew("j", "b")
    // As a newer version would write it, with a readable payload and no revert run yet
    queries.compareAndSet(
      status = "archived",
      cause = null,
      attempts = 0,
      lastError = null,
      reverted = 0,
      retryAt = null,
      settledAt = null,
      committed = 0,
      sentAt = null,
      reply = null,
      outbox = "test",
      id = id.value,
      expectedStatus = "queued",
      expectedCause = null,
    )

    assertThat(store.entry(id).first()?.status).isNotNull().isInstanceOf<OutboxStatus.Failed>()
    assertThat(store.owed().map { it.id }).isEqualTo(listOf(id))
    assertThat(store.owed("k").map { it.id }).isEqualTo(listOf(id))
    assertThat(store.owed("j")).isEmpty()
    // It reads as Failed, so it never sends
    assertThat(store.head("k")).isNull()
    assertThat(store.heads().first().map { it.payload }).isEqualTo(listOf("b"))
  }

  @Test
  fun `every read opens the queries on the io dispatcher`() = runTest {
    // A dispatcher object of its own, so the queries can tell whether they run on it
    val io = Dispatchers.IO.limitedParallelism(4)
    val outside = AtomicLong()
    val store =
      SqlDelightOutboxStore(
        queries = {
          if (currentCoroutineContext()[ContinuationInterceptor] !== io) {
            outside.incrementAndGet()
          }
          temporaryDatabase.db().outboxRowQueries
        },
        outbox = "test",
        codec = String.serializer().asCodec(Json),
        io = io,
      )
    val id = store.insertNew("k", "a")
    val row = checkNotNull(store.entry(id).first())

    store.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued)
    store.entries().first()
    store.heads().first()
    store.head("k")
    store.owed()
    store.owed("k")

    assertThat(outside.get()).isEqualTo(0L)
  }

  @Test
  fun `an insert whose caller is cancelled during the write still stores the row`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    lateinit var caller: Job
    // Cancels the caller from inside the insert, which still commits
    val cancelling =
      object : PayloadCodec<String> {
        override fun encode(payload: String) = payload.also { caller.cancel() }

        override fun decode(encoded: String) = encoded
      }
    val store =
      SqlDelightOutboxStore({ db.await().outboxRowQueries }, "cancel", cancelling, Dispatchers.IO)
    var inserted: Result<EntryId>? = null

    caller = launch { inserted = runCatching { store.insertNew("k", "a") } }
    caller.join()

    val rows = store.entries().first()
    assertThat(rows).hasSize(1)
    assertThat(inserted?.getOrNull()).isEqualTo(rows.single().id)
  }
}

/** A row stored before its payload class changed, in the database outbox. */
class SqlDelightUnreadableRowTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Test
  fun `an old row in a database outbox is reported once and blocks no other row`() = runTest {
    val queries = temporaryDatabase.db().outboxRowQueries
    queries.insert(
      id = "old",
      outbox = "changes",
      orderingKey = "k",
      payload = OLD_CHANGE,
      rejectKey = null,
    )
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)

    assertUnreadableRowIsHandled(stores, EntryId("old"))

    // It stays in the table
    assertThat(queries.byId("changes", "old").executeAsOneOrNull()).isNotNull()
  }

  @Test
  fun `a failed row that does not decode is reported like a queued one`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    queries.insert(
      id = "queued",
      outbox = "changes",
      orderingKey = "k",
      payload = OLD_CHANGE,
      rejectKey = null,
    )
    queries.insert(
      id = "failed",
      outbox = "changes",
      orderingKey = "k",
      payload = OLD_CHANGE,
      rejectKey = null,
    )
    queries.compareAndSet(
      status = "failed",
      cause = "Rejected",
      attempts = 1,
      lastError = null,
      reverted = 1,
      retryAt = null,
      settledAt = null,
      committed = 0,
      sentAt = null,
      reply = null,
      outbox = "changes",
      id = "failed",
      expectedStatus = "queued",
      expectedCause = null,
    )
    val failures = TestFailures()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = SqlDelightOutboxStores({ queries }, Dispatchers.IO),
        )
        .create(scope, setOf(OutboxKey<String>("changes").sender { _, _ -> }))

    // The sender alone reads the rows, with no handle and nothing collecting entries
    val reported =
      withTimeout(10.seconds) {
        while (failures.all.count { it is UnreadableEntryException } < 2) {
          delay(10.milliseconds)
        }
        failures.all.filterIsInstance<UnreadableEntryException>().map { it.id }
      }
    scope.cancel()

    assertThat(reported).containsExactlyInAnyOrder(EntryId("queued"), EntryId("failed"))
  }
}

/** How much the database outbox reads as its backlog grows, and how its queries find rows. */
class SqlDelightOutboxScaleTest {
  private lateinit var driver: CountingDriver

  @get:Rule
  val temporaryDatabase =
    TemporaryDatabase(
      { plain -> CountingDriver(plain).also { driver = it }.createFSDatabase() },
      FSDatabase.Schema,
    )

  @Test
  fun `draining one key reads a bounded number of rows per row`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val store =
      SqlDelightOutboxStore({ queries }, "scale", String.serializer().asCodec(Json), Dispatchers.IO)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val online = MutableStateFlow(false)
    val failures = TestFailures()
    val rows = OutboxKey<String>("store")
    val data =
      DataScope.Factory(onFailure = failures, online = online)
        .create(scope, setOf(rows.sender(store = store) { _, _ -> }))
    val outbox = data.outbox(rows)
    val handles = List(ROWS) { outbox.enqueue("p$it", key = "k") }
    withTimeout(60.seconds) { handles.forEach { it.stored.await() } }
    driver.rowsRead.set(0)

    online.value = true
    withTimeout(60.seconds) { handles.forEach { it.await() } }
    scope.cancel()

    // A full read of the pending rows per write would be about ROWS^2 rows
    assertThat(driver.rowsRead.get()).isLessThanOrEqualTo(10L * ROWS)
    failures.assertNone()
  }

  @Test
  fun `naming a row discarded deep in a backlog reads no other row`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val store =
      SqlDelightOutboxStore({ queries }, "scale", String.serializer().asCodec(Json), Dispatchers.IO)
    val ids = List(ROWS) { store.insertNew("k", "p$it") }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val changes = store.changesIn(scope)
    assertThat(changes.receive()).isEqualTo(OutboxChange.Unknown)
    driver.rowsRead.set(0)

    store.discardDirectly(ids.last())
    changes.awaitNamed(setOf(ids.last()), orUnknown = false)
    scope.cancel()

    // The discard's own reads, since naming the row reads none. Reading the rows before it would
    // be ROWS rows.
    assertThat(driver.rowsRead.get()).isLessThanOrEqualTo(10L)
  }

  @Test
  fun `a key's next row is one index search per status`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val plan = driver.planOf { queries.nextSendable("a", "k", -1) }

    assertThat(plan).contains("orderingKey=?")
    assertThat(plan).doesNotContain("TEMP B-TREE")
    assertThat(plan).doesNotContain("SCAN outboxRow")
  }

  @Test
  fun `the heads read an index of the outbox and fetch one row per key`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val plan = driver.planOf { queries.heads("a") }

    assertThat(plan).contains("USING COVERING INDEX outboxRow_key")
    assertThat(plan).doesNotContain("SCAN outboxRow")
  }

  @Test
  fun `a key's owed rows are read through the key's index entries`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val plan = driver.planOf { queries.owedOnKey("a", "k") }

    assertThat(plan).contains("USING INDEX outboxRow_key (outbox=? AND orderingKey=?)")
    assertThat(plan).doesNotContain("SCAN outboxRow")
  }

  private companion object {
    const val ROWS = 300
  }
}

/**
 * Counts every row a query hands back, and explains the plan of a query as it runs, with the SQL
 * and arguments SQLDelight generated for it.
 */
private class CountingDriver(private val driver: SqlDriver) : SqlDriver by driver {
  val rowsRead = AtomicLong()
  @Volatile private var explaining = false
  @Volatile private var plan: String? = null

  /** The plan SQLite chooses for the one query [run] makes. */
  suspend fun planOf(run: () -> Query<*>): String {
    plan = null
    explaining = true
    try {
      run().executeAsList()
    } finally {
      explaining = false
    }
    return checkNotNull(plan)
  }

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    if (explaining) {
      plan =
        driver
          .executeQuery(
            null,
            "EXPLAIN QUERY PLAN $sql",
            { cursor ->
              val lines = ArrayList<String>()
              while (cursor.next().value) {
                lines += cursor.getString(3).orEmpty()
              }
              QueryResult.Value(lines.joinToString("\n"))
            },
            parameters,
            binders,
          )
          .value
    }
    return driver.executeQuery(
      identifier,
      sql,
      { cursor ->
        mapper(
          object : SqlCursor by cursor {
            override fun next(): QueryResult<Boolean> =
              cursor.next().also {
                if (it.value) {
                  rowsRead.incrementAndGet()
                }
              }
          }
        )
      },
      parameters,
      binders,
    )
  }
}

/** What a [SqlDelightOutboxStore] reads back after its database is closed and opened again. */
class SqlDelightOutboxStoreReopenTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  /** A store over whatever database [temporaryDatabase] has open when each call runs. */
  private fun store(clock: TestClock): OutboxStore<String> =
    SqlDelightOutboxStore(
      queries = { temporaryDatabase.db().outboxRowQueries },
      outbox = "test",
      codec = String.serializer().asCodec(Json),
      io = Dispatchers.IO,
      clock = clock,
    )

  @Test
  fun `every read returns the same rows after a reopen`() = runTest {
    val clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    val before = store(clock)
    val queued = before.insertNew("a", "queued")
    val sending = before.insertNew("b", "sending")
    val sent = before.insertNew("c", "sent")
    val failed = before.insertNew("d", "failed")
    val done = before.insertNew("e", "done")
    val now = clock.now()
    before.write(queued) {
      it.copy(attempts = 2, lastError = "timeout", retryAt = now + 30.seconds)
    }
    before.write(sending) { it.copy(status = OutboxStatus.Sending, attempts = 1) }
    before.write(sent) { it.copy(status = OutboxStatus.Sent, sentAt = now, reply = "\"answer\"") }
    before.write(failed) { it.copy(status = OutboxStatus.Failed("rejected"), attempts = 3) }
    before.write(done) { it.copy(status = OutboxStatus.Sent, sentAt = now, committed = true) }
    val ids = listOf(queued, sending, sent, failed, done)
    val entries = before.entries().first()
    val owed = before.owed()
    val owedOnKey = before.owed("c")
    val heads = before.heads().first()
    val rows = ids.map { before.entry(it).first() }
    assertThat(entries.map { it.id }).isEqualTo(listOf(queued, sending, sent, failed))
    assertThat(owed.map { it.id }).isEqualTo(listOf(sent, failed))
    assertThat(heads.map { it.id }).isEqualTo(listOf(queued, sending))
    assertThat(rows.last()?.committed).isEqualTo(true)

    temporaryDatabase.reopen()

    val after = store(clock)
    assertThat(after.entries().first()).isEqualTo(entries)
    assertThat(after.owed()).isEqualTo(owed)
    assertThat(after.owed("c")).isEqualTo(owedOnKey)
    assertThat(after.heads().first()).isEqualTo(heads)
    assertThat(ids.map { after.entry(it).first() }).isEqualTo(rows)
  }

  @Test
  fun `a retry deadline between seconds reads back to the millisecond after a reopen`() = runTest {
    val clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    val before = store(clock)
    val id = before.insertNew("k", "a")
    val retryAt = Instant.parse("2026-06-10T12:00:30.123456789Z")
    before.write(id) { it.copy(attempts = 1, retryAt = retryAt) }

    temporaryDatabase.reopen()

    val after = store(clock).entry(id).first()
    // The store keeps epoch milliseconds, so the nanoseconds below them are dropped
    assertThat(after?.retryAt).isEqualTo(Instant.parse("2026-06-10T12:00:30.123Z"))
  }

  @Test
  fun `a reject key reads back after a reopen`() = runTest {
    val clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    store(clock).insert(EntryId("rejecting"), "k", "a", rejectKey = "r")

    temporaryDatabase.reopen()

    assertThat(store(clock).entry(EntryId("rejecting")).first()?.rejectKey).isEqualTo("r")
  }

  /** Writes what [change] makes of row [id] over the status it has. */
  private suspend fun OutboxStore<String>.write(
    id: EntryId,
    change: (OutboxEntry<String>) -> OutboxEntry<String>,
  ) {
    val row = checkNotNull(entry(id).first())
    assertThat(update(change(row), expected = row.status)).isTrue()
  }
}

/** [SqlDelightOutboxStores.clearAll], which one statement does for every outbox in the table. */
class SqlDelightOutboxStoresClearAllTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Test
  fun `clearing the set empties every outbox in the table`() = runTest {
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)
    val tags = stores.open("tags", String.serializer())
    val notes = stores.open("notes", String.serializer())
    val id = tags.insertNew("k", "a")
    notes.insertNew("k", "b")

    stores.clearAll()

    assertThat(tags.entries().first()).isEmpty()
    assertThat(notes.entries().first()).isEmpty()
    // A handle that follows the row reads it as absent, and settles Cancelled through that
    assertThat(tags.entry(id).first()).isNull()
  }

  @Test
  fun `clearing a set that opened no outbox deletes the rows an earlier set stored`() = runTest {
    val earlier =
      SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)
    earlier.open("tags", String.serializer()).insertNew("k", "a")
    earlier.open("notes", String.serializer()).insertNew("k", "b")
    // Like the set of a new process, which has opened neither outbox
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)

    stores.clearAll()

    assertThat(stores.open("tags", String.serializer()).entries().first()).isEmpty()
    assertThat(stores.open("notes", String.serializer()).entries().first()).isEmpty()
  }

  @Test
  fun `handles of rows a clear deletes settle cancelled`() = runBlocking {
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failures = TestFailures()
    val notes = OutboxKey<String>("notes")
    val outbox =
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(false), stores = stores)
        .create(scope, setOf(notes.sender { _, _ -> }))
        .outbox(notes)
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "other")
    // Keys insert on their own, so each row is waited for
    withTimeout(10.seconds) { listOf(a, b, c).forEach { it.stored.await() } }

    stores.clearAll()
    val settled = withTimeout(10.seconds) { listOf(a.await(), b.await(), c.await()) }
    scope.cancel()

    assertThat(settled).isEqualTo(List(3) { OperationState.Cancelled })
    failures.assertNone()
  }
}

/** The size limits that keep every outbox row readable through a cursor window. */
class SqlDelightOutboxStoreSizeTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Test
  fun `an insert above the size cap throws and stores nothing`() = runTest {
    val store =
      SqlDelightOutboxStores(
          { temporaryDatabase.db().outboxRowQueries },
          Dispatchers.IO,
          maxTextBytes = 64,
        )
        .open("notes", String.serializer())
    // 61 chars, the two JSON quotes and the one-byte key are exactly the cap
    store.insertNew("k", "a".repeat(61))
    // The same payload under a key one byte longer is over it
    assertFailsWith<IllegalArgumentException> {
      store.insert(EntryId("keyed"), "kk", "a".repeat(61))
    }

    // Fewer chars than the cap, but two UTF-8 bytes each
    val failure =
      assertFailsWith<IllegalArgumentException> {
        store.insert(EntryId("big"), "k", "é".repeat(40))
      }

    assertThat(failure.message.orEmpty()).contains("notes")
    assertThat(failure.message.orEmpty()).contains("big")
    assertThat(failure.message.orEmpty()).contains("64")
    assertThat(failure.message.orEmpty()).doesNotContain("é")
    assertThat(store.entries().first().map { it.payload }).isEqualTo(listOf("a".repeat(61)))
  }

  @Test
  fun `an insert whose reject key takes it above the size cap throws and stores nothing`() =
    runTest {
      val store =
        SqlDelightOutboxStores(
            { temporaryDatabase.db().outboxRowQueries },
            Dispatchers.IO,
            maxTextBytes = 64,
          )
          .open("notes", String.serializer())
      // The payload and key alone are exactly the cap, and the one-byte reject key is over it
      assertFailsWith<IllegalArgumentException> {
        store.insert(EntryId("rejecting"), "k", "a".repeat(61), rejectKey = "r")
      }

      assertThat(store.entry(EntryId("rejecting")).first()).isNull()
      assertThat(store.entries().first()).isEmpty()
      // Without the reject key, the same row fits
      store.insert(EntryId("plain"), "k", "a".repeat(61))
      assertThat(store.entries().first().map { it.id }).isEqualTo(listOf(EntryId("plain")))
    }

  @Test
  fun `an update stores an answer above the size cap as unknown`() = runTest {
    val store =
      SqlDelightOutboxStores(
          { temporaryDatabase.db().outboxRowQueries },
          Dispatchers.IO,
          maxTextBytes = 64,
        )
        .open("notes", String.serializer())
    val id = store.insertNew("k", "a")
    val queued = checkNotNull(store.entry(id).first())

    // From a sender with a store of its own, whose answers never pass the set's codec
    val sent = queued.copy(status = OutboxStatus.Sent, reply = "x".repeat(65))
    assertThat(store.update(sent, expected = OutboxStatus.Queued)).isTrue()

    assertThat(store.entry(id).first()?.reply).isEqualTo(UNKNOWN_REPLY)
  }

  @Test
  fun `an answer above the size cap is stored as unknown and reported`() = runBlocking {
    val stores =
      SqlDelightOutboxStores(
        { temporaryDatabase.db().outboxRowQueries },
        Dispatchers.IO,
        maxTextBytes = 64,
      )
    val echo = OutboxKey.replying<String, String>("echo")
    val rows = stores.open(echo.name, String.serializer())
    val id = rows.insertNew("k", "a")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failures = TestFailures()
    val senderFailures = TestFailures()

    // No handle is attached, so the row is written Sent with its answer
    DataScope.Factory(onFailure = failures, stores = stores)
      .create(
        scope,
        setOf(echo.exchangingSender(onFailure = senderFailures) { _, _ -> "é".repeat(40) }),
      )
    val sent = withTimeout(10.seconds) { rows.entry(id).first { it?.status == OutboxStatus.Sent } }
    scope.cancel()

    assertThat(sent?.reply).isEqualTo(UNKNOWN_REPLY)
    senderFailures.assertSingle<IllegalArgumentException>()
    failures.assertNone()
  }
}
