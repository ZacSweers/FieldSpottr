// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.DataStore
import dev.zacsweers.fieldspottr.data.core.testing.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class InMemoryOutboxStoreTest : OutboxStoreContract() {
  private val time = TestTimeSource()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> =
    inMemoryOutboxStore(keepSettledFor, time)

  override fun advanceStoreTime(duration: Duration) {
    time += duration
  }
}

class DataStoreOutboxStoreTest : OutboxStoreContract() {
  private lateinit var clock: TestClock
  private lateinit var dataStore: DataStore<OutboxLog>
  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    dataStore = outboxDataStore(backgroundScope, { directory / "test.outbox" }, fileSystem)
    return DataStoreOutboxStore(
      dataStore = dataStore,
      codec = String.serializer().asCodec(Json),
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advanceStoreTime(duration: Duration) {
    clock.shift(duration)
  }

  // A JSON object where the codec expects a string
  override suspend fun TestScope.insertUnreadable(
    store: OutboxStore<String>,
    key: String,
  ): EntryId {
    val row = OutboxLog.Row("unreadable", key, payload = """{"a":1}""", status = "queued")
    dataStore.updateData { it.copy(rows = it.rows + row) }
    return EntryId(row.id)
  }

  @Test
  fun `a log with fields this version does not know still reads`() = runTest {
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val codec = String.serializer().asCodec(Json)
    val payload = Json.encodeToString(codec.encode("a"))
    // As a newer version would write it, with a field on the log and one on the row
    fileSystem.write(directory / "newer.outbox") {
      writeUtf8(
        """{"version":2,"rows":[""" +
          """{"id":"row","key":"k","payload":$payload,"status":"queued","priority":1}]}"""
      )
    }
    val store =
      DataStoreOutboxStore(
        outboxDataStore(backgroundScope, { directory / "newer.outbox" }, fileSystem),
        codec,
      )

    assertEquals(listOf("a"), store.entries().first().map { it.payload })
  }

  @Test
  fun `a log that does not parse starts empty and keeps working`() = runTest {
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    fileSystem.write(directory / "broken.outbox") { writeUtf8("""{"rows":[{"id":""") }
    val store =
      DataStoreOutboxStore(
        outboxDataStore(backgroundScope, { directory / "broken.outbox" }, fileSystem),
        String.serializer().asCodec(Json),
      )

    assertEquals(emptyList(), store.entries().first())
    store.insertNew("k", "a")
    assertEquals(listOf("a"), store.entries().first().map { it.payload })
  }

  @Test
  fun `a status a newer version wrote reads as failed and can be requeued`() = runTest {
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val dataStore = outboxDataStore(backgroundScope, { directory / "future.outbox" }, fileSystem)
    val codec = String.serializer().asCodec(Json)
    // As a newer version would write it
    dataStore.updateData {
      OutboxLog(listOf(OutboxLog.Row("row", key = "k", codec.encode("a"), status = "archived")))
    }
    val store = DataStoreOutboxStore(dataStore, codec)
    val row = checkNotNull(store.entry(EntryId("row")).first())

    assertIs<OutboxStatus.Failed>(row.status)
    assertTrue(store.update(row.copy(status = OutboxStatus.Queued), expected = row.status))
    assertEquals(OutboxStatus.Queued, store.entry(EntryId("row")).first()?.status)
  }

  @Test
  fun `an insert whose caller is cancelled as the write commits still stores the row`() = runTest {
    val directory = "/outbox".toPath()
    lateinit var caller: Job
    // Cancels the caller right after the file write commits, when the row is stored
    val committing =
      object : ForwardingFileSystem(fileSystem) {
        override fun atomicMove(source: Path, target: Path) {
          super.atomicMove(source, target)
          caller.cancel()
        }
      }
    committing.createDirectories(directory)
    val store =
      DataStoreOutboxStore(
        outboxDataStore(backgroundScope, { directory / "commit.outbox" }, committing),
        String.serializer().asCodec(Json),
      )
    var inserted: Result<EntryId>? = null

    caller = backgroundScope.launch { inserted = runCatching { store.insertNew("k", "a") } }
    caller.join()

    val rows = store.entries().first()
    assertEquals(1, rows.size)
    assertEquals(rows.single().id, inserted?.getOrNull())
  }

  /**
   * [OutboxStores.clearAll] on the two in-process store sets. The SQLDelight set has a JVM test.
   */
  class OutboxStoresClearAllTest {
    @Test
    fun `clearing an in-memory set empties every store it opened`() = runTest {
      val stores = InMemoryOutboxStores()
      val tags = stores.open("tags", String.serializer())
      val notes = stores.open("notes", String.serializer())
      val id = tags.insertNew("k", "a")
      notes.insertNew("k", "b")

      stores.clearAll()

      assertEquals(emptyList(), tags.entries().first())
      assertEquals(emptyList(), notes.entries().first())
      // A handle that follows the row reads it as absent, and settles Cancelled through that
      assertNull(tags.entry(id).first())
    }

    @Test
    fun `clearing a file set empties the stores it built and deletes the files no one opened`() =
      runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/outbox".toPath()
        fileSystem.createDirectories(directory)
        // An earlier process left rows for an outbox this one never builds
        val earlier = CoroutineScope(Job())
        DataStoreOutboxStores(earlier, directory, fileSystem = fileSystem)
          .open("old", String.serializer())
          .insertNew("k", "a")
        earlier.cancel()
        val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
        val tags = stores.open("tags", String.serializer())
        val id = tags.insertNew("k", "b")

        stores.clearAll()

        assertEquals(emptyList(), tags.entries().first())
        assertNull(tags.entry(id).first())
        assertFalse(fileSystem.exists(directory / "old.outbox"))
        // The store this set built keeps its file, now empty, since its DataStore still owns it
        assertTrue(fileSystem.exists(directory / "tags.outbox"))
      }
  }
}

/** [OutboxStores.clearAll] on the two in-process store sets. The SQLDelight set has a JVM test. */
class OutboxStoresClearAllTest {
  @Test
  fun `clearing an in-memory set empties every store it opened`() = runTest {
    val stores = InMemoryOutboxStores()
    val tags = stores.open("tags", String.serializer())
    val notes = stores.open("notes", String.serializer())
    val id = tags.insertNew("k", "a")
    notes.insertNew("k", "b")

    stores.clearAll()

    assertEquals(emptyList(), tags.entries().first())
    assertEquals(emptyList(), notes.entries().first())
    // A handle that follows the row reads it as absent, and settles Cancelled through that
    assertNull(tags.entry(id).first())
  }

  @Test
  fun `clearing a file set empties the stores it built and deletes the files no one opened`() =
    runTest {
      val fileSystem = FakeFileSystem()
      val directory = "/outbox".toPath()
      fileSystem.createDirectories(directory)
      // An earlier process left rows for an outbox this one never builds
      val earlier = CoroutineScope(Job())
      DataStoreOutboxStores(earlier, directory, fileSystem = fileSystem)
        .open("old", String.serializer())
        .insertNew("k", "a")
      earlier.cancel()
      val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
      val tags = stores.open("tags", String.serializer())
      val id = tags.insertNew("k", "b")

      stores.clearAll()

      assertEquals(emptyList(), tags.entries().first())
      assertNull(tags.entry(id).first())
      assertFalse(fileSystem.exists(directory / "old.outbox"))
      // The store this set built keeps its file, now empty, since its DataStore still owns it
      assertTrue(fileSystem.exists(directory / "tags.outbox"))
    }
}

/** [OutboxStores.open] on the two in-process store sets. The SQLDelight set has a JVM test. */
class OutboxStoresPayloadTypeTest {
  @Test
  fun `an in-memory set opens a name again for an equal payload type only`() = runTest {
    assertOneTypePerName(InMemoryOutboxStores())
  }

  @Test
  fun `a file set opens a name again for an equal payload type only`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    assertOneTypePerName(DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem))
  }
}

/**
 * Checks that [stores] returns the same store for a name opened again with a new serializer object
 * of the same type, and throws for a serializer of another type.
 */
internal fun assertOneTypePerName(stores: OutboxStores) {
  val first = stores.open("notes", ListSerializer(String.serializer()))

  assertSame(first, stores.open("notes", ListSerializer(String.serializer())))
  val failure =
    assertFailsWith<IllegalStateException> {
      stores.open("notes", ListSerializer(Int.serializer()))
    }
  assertContains(failure.message.orEmpty(), "notes")
  assertContains(failure.message.orEmpty(), "kotlin.String")
  assertContains(failure.message.orEmpty(), "kotlin.Int")
}

/** [UndecodableRows], which both durable stores use to hand on rows that do not decode. */
class UndecodableRowsTest {
  @Test
  fun `a listener that throws still hears every waiting row`() {
    val rows = UndecodableRows()
    rows.found("a", IllegalArgumentException("a"))
    rows.found("b", IllegalArgumentException("b"))
    val heard = ArrayList<EntryId>()

    rows.listen { id, _ ->
      heard += id
      throw IllegalStateException("The failure handler rethrows")
    }
    rows.found("c", IllegalArgumentException("c"))

    assertEquals(listOf(EntryId("a"), EntryId("b"), EntryId("c")), heard)
  }
}
