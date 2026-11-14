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
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.TaggedFormat
import dev.zacsweers.fieldspottr.data.core.UnreadableEntryException
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.data.core.assertOneTypePerName
import dev.zacsweers.fieldspottr.data.core.assertUnreadableRowIsHandled
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
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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

  override suspend fun TestScope.clearAll(store: OutboxStore<String>) {
    SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO).clearAll()
  }

  // A JSON object where the codec expects a string
  override suspend fun TestScope.insertUnreadable(
    store: OutboxStore<String>,
    key: String,
  ): EntryId {
    val id = EntryId("unreadable")
    temporaryDatabase.db().outboxRowQueries.insert(id.value, "test", key, """{"a":1}""")
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
  fun `an insert whose caller is cancelled during the write still stores the row`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    lateinit var caller: Job
    // Cancels the caller from inside the transaction, which still commits
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
    queries.insert(id = "old", outbox = "changes", orderingKey = "k", payload = OLD_CHANGE)
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)

    assertUnreadableRowIsHandled(stores, EntryId("old"))

    // It stays in the table
    assertThat(queries.byId("changes", "old").executeAsOneOrNull()).isNotNull()
  }

  @Test
  fun `a failed row that does not decode is reported like a queued one`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    queries.insert(id = "queued", outbox = "changes", orderingKey = "k", payload = OLD_CHANGE)
    queries.insert(id = "failed", outbox = "changes", orderingKey = "k", payload = OLD_CHANGE)
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

    // The discard's own reads and one change record. Reading the rows before it would be ROWS rows.
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

/** How [SqlDelightOutboxStore.changes] hears writes made through other stores over its table. */
class SqlDelightOutboxChangesTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Test
  fun `a row another store set discards behind a queued row settles its handle cancelled`() =
    runBlocking {
      val queries = temporaryDatabase.db().outboxRowQueries
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val failures = TestFailures()
      val notes = OutboxKey<String>("notes")
      val outbox =
        DataScope.Factory(
            onFailure = failures,
            online = MutableStateFlow(false),
            stores = SqlDelightOutboxStores(queries, Dispatchers.IO),
          )
          .create(scope, setOf(notes.sender { _, _ -> }))
          .outbox(notes)
      val a = outbox.enqueue("a", key = "k")
      val b = outbox.enqueue("b", key = "k")
      withTimeout(10.seconds) { b.stored.await() }

      // Another store set over the same database. The key's head stays a.
      SqlDelightOutboxStores(queries, Dispatchers.IO)
        .open("notes", String.serializer())
        .discardDirectly(b.id)
      val settled = withTimeout(10.seconds) { b.await() }
      scope.cancel()

      assertThat(settled).isEqualTo(OperationState.Cancelled)
      assertThat(a.state.value).isEqualTo(OperationState.Queued)
      failures.assertNone()
    }

  @Test
  fun `a write through another store set is named and another outbox's write is not`() =
    runBlocking {
      val queries = temporaryDatabase.db().outboxRowQueries
      val reader =
        SqlDelightOutboxStores(queries, Dispatchers.IO).open("notes", String.serializer())
      val writers = SqlDelightOutboxStores(queries, Dispatchers.IO)
      val notes = writers.open("notes", String.serializer())
      val tags = writers.open("tags", String.serializer())
      val note = notes.insertNew("k", "a")
      val tag = tags.insertNew("k", "b")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val changes = reader.changesIn(scope)
      assertThat(changes.receive()).isEqualTo(OutboxChange.Unknown)

      tags.discardDirectly(tag)
      notes.discardDirectly(note)
      val change = withTimeout(10.seconds) { changes.receive() }
      scope.cancel()

      assertThat(change).isEqualTo(OutboxChange.Rows(setOf(note)))
    }

  @Test
  fun `a reader that falls behind the kept records gets Unknown`() = runBlocking {
    val queries = temporaryDatabase.db().outboxRowQueries
    val store = SqlDelightOutboxStores(queries, Dispatchers.IO).open("notes", String.serializer())
    val id = store.insertNew("k", "a")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val changes = Channel<OutboxChange>(Channel.UNLIMITED)
    val resume = CompletableDeferred<Unit>()
    // Stops after the first change, like a reader that falls behind
    scope.launch {
      store.changes().collect {
        changes.send(it)
        resume.await()
      }
    }
    assertThat(changes.receive()).isEqualTo(OutboxChange.Unknown)

    store.discardDirectly(id)
    // The records of a thousand writes to another outbox, then an insert, which prunes the oldest
    queries.transaction {
      repeat(1_000) { queries.recordChange("tags", "t$it") }
    }
    store.insertNew("k", "b")
    resume.complete(Unit)
    val change = withTimeout(10.seconds) { changes.receive() }
    scope.cancel()

    assertThat(change).isEqualTo(OutboxChange.Unknown)
  }
}
