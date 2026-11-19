// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** [DataStoreOutboxStore.clear] and the rows it found not to decode. */
class DataStoreOutboxStoreClearTest {
  private val fake = FakeFileSystem()
  private val file = "/outbox/changes.outbox".toPath()

  /** Runs after each write moves its new file into place. */
  private var afterMove: (() -> Unit)? = null

  // DataStore writes a new file and moves it into place, so the move is when a write lands
  private val fileSystem =
    object : ForwardingFileSystem(fake) {
      override fun atomicMove(source: Path, target: Path) {
        super.atomicMove(source, target)
        afterMove?.invoke()
      }
    }

  @Test
  fun `a clear whose caller is cancelled after the write forgets the rows that did not decode`() =
    runTest {
      val store = storeWithUndecodableRow()
      lateinit var caller: Job
      afterMove = { caller.cancel() }

      caller = launch { store.clear() }
      caller.join()

      assertThat(caller.isCancelled).isTrue()
      assertThat(storedRows()).isEmpty()
      val reported = mutableListOf<EntryId>()
      store.onUndecodable { id, _ -> reported += id }
      assertThat(reported).isEmpty()
    }

  @Test
  fun `a caller cancelled after one store's clear starts no clear of another store`() = runTest {
    val directory = "/outbox".toPath()
    for (name in listOf("tags", "notes")) {
      val log = OutboxLog(listOf(OutboxLog.Row(name, key = "k", "\"a\"", status = "queued")))
      fake.createDirectories(directory)
      fake.write(directory / "$name.outbox") {
        writeUtf8(Json.encodeToString(OutboxLog.serializer(), log))
      }
    }
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    stores.open("tags", String.serializer())
    stores.open("notes", String.serializer())
    lateinit var caller: Job
    afterMove = { caller.cancel() }

    caller = launch { stores.clearAll() }
    caller.join()

    assertThat(caller.isCancelled).isTrue()
    // The first store's clear finished, and the second store's clear never started
    val left = listOf("tags", "notes").flatMap { storedRows(directory / "$it.outbox") }
    assertThat(left.size).isEqualTo(1)
  }

  /**
   * A store over a file that holds row `old`, which does not decode. A read has recorded it while
   * no listener was set, so it waits for one.
   */
  private suspend fun TestScope.storeWithUndecodableRow(): DataStoreOutboxStore<Change> {
    val log = OutboxLog(listOf(OutboxLog.Row("old", key = "k", OLD_CHANGE, status = "queued")))
    fake.createDirectories(file.parent!!)
    fake.write(file) { writeUtf8(Json.encodeToString(OutboxLog.serializer(), log)) }
    val store =
      DataStoreOutboxStore(
        outboxDataStore(backgroundScope, { file }, fileSystem),
        Change.serializer().asCodec(DefaultPayloadFormat),
      )
    assertThat(store.head("k")).isNull()
    return store
  }

  private fun storedRows(path: Path = file): List<String> {
    val log = fake.read(path) { readUtf8() }
    return Json.decodeFromString(OutboxLog.serializer(), log).rows.map { it.id }
  }
}
