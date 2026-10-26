// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.impl.inMemoryOutboxStore
import dev.zacsweers.fieldspottr.data.core.testing.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    return DataStoreOutboxStore(
      dataStore = outboxDataStore(backgroundScope, { directory / "test.outbox" }, fileSystem),
      codec = String.serializer().asCodec(Json),
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advanceStoreTime(duration: Duration) {
    clock.shift(duration)
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
  fun `an insert whose caller is cancelled as the write commits returns the id`() = runTest {
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

    caller = backgroundScope.launch { inserted = runCatching { store.insert("k", "a") } }
    caller.join()

    val rows = store.entries().first()
    assertEquals(1, rows.size)
    assertEquals(rows.single().id, inserted?.getOrNull())
  }
}
