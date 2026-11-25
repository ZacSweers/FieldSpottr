// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Rows stored before their payload class changed, in a file outbox. The SQLDelight store runs the
 * same checks in its JVM test.
 */
class UnreadableRowTest {
  private val fileSystem = FakeFileSystem()
  private val directory = "/outbox".toPath()

  @Test
  fun `an old row in a file outbox is reported once and blocks no other row`() = runTest {
    writeLog("changes", OutboxLog.Row("old", key = "k", payload = OLD_CHANGE, status = "queued"))
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)

    assertUnreadableRowIsHandled(stores, EntryId("old"))

    // It stays in the file
    val log = fileSystem.read(directory / "changes.outbox") { readUtf8() }
    assertThat(Json.decodeFromString(OutboxLog.serializer(), log).rows.map { it.id })
      .contains("old")
  }

  @Test
  fun `a failed row that does not decode is reported like a queued one`() = runTest {
    writeLog(
      "changes",
      OutboxLog.Row("queued", key = "k", payload = OLD_CHANGE, status = "queued"),
      OutboxLog.Row("failed", key = "k", payload = OLD_CHANGE, status = "failed", cause = "No"),
    )
    val failures = TestFailures()
    val bothReported = CompletableDeferred<Unit>()
    val data =
      DataScope.Factory(
          onFailure = { failure ->
            failures(failure)
            if (failures.all.count { it is UnreadableEntryException } == 2) {
              bothReported.complete(Unit)
            }
          },
          stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem),
          clock = testClock(),
        )
        .create(backgroundScope, setOf(ChangesOutboxKey.sender { _, _ -> }))

    // Nothing collects the outbox's entries
    data.outbox(ChangesOutboxKey)
    bothReported.await()

    assertThat(failures.all.filterIsInstance<UnreadableEntryException>().map { it.id })
      .containsExactlyInAnyOrder(EntryId("queued"), EntryId("failed"))
  }

  @Test
  fun `an optimistic store leaves an old row out and keeps showing its data`() = runTest {
    // A single store's row holds the change itself, on the order key every row shares
    writeLog("groups", OutboxLog.Row("old", key = "", OLD_CHANGE, status = "queued"))
    val failures = TestFailures()
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem),
          clock = testClock(),
        )
        .create(backgroundScope, setOf(Groups.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(setOf("x"), testClock().now()))
    val groups =
      data.optimisticStore(
        Groups,
        base = truth,
        apply = { current, change ->
          if (change.on) {
            current.orEmpty() + change.group
          } else {
            current.orEmpty() - change.group
          }
        },
      )

    assertThat(groups.data.first()?.value).isEqualTo(setOf("x"))
    groups.mutate(Change("b", on = true)).stored.awaitSucceeded()

    assertThat(groups.data.first { it?.value == setOf("x", "b") }).isNotNull()
    assertThat(groups.pending.first().map { it.mutation }).containsExactly(Change("b", on = true))
    failures.assertSingle<UnreadableEntryException>()
  }

  @Test
  fun `optimistic stores leave an old row out of every key and report it once`() = runTest {
    val payload = """{"key":"k","mutation":$OLD_CHANGE}"""
    writeLog("groups", OutboxLog.Row("old", key = "\"k\"", payload, status = "queued"))
    val failures = TestFailures()
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem),
          clock = testClock(),
        )
        .create(backgroundScope, setOf(GroupsByKey.sender { _, _ -> }))
    val groups =
      data.optimisticStores(
        GroupsByKey,
        base = QueryStore.inMemoryByKey<String, Set<String>>(),
        apply = { current, change -> current.orEmpty() + change.group },
      )

    assertThat(groups.pendingCount.first()).isEqualTo(0)
    assertThat(groups.pending("k").first()).isEmpty()
    groups.mutate("k", Change("b", on = true)).stored.awaitSucceeded()
    assertThat(groups.pending("k").first { it.isNotEmpty() }.map { it.mutation })
      .containsExactly(Change("b", on = true))
    assertThat(groups.pendingCount.first()).isEqualTo(1)

    // The outbox reported the row once, however many reads met it
    failures.assertSingle<UnreadableEntryException>()
  }

  @Test
  fun `an outbox over a store of its own names itself in its reports`() = runTest {
    writeLog("changes", OutboxLog.Row("old", key = "k", payload = OLD_CHANGE, status = "queued"))
    val store =
      DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
        .open("changes", Change.serializer())
    val reported = CompletableDeferred<UnreadableEntryException>()
    val data =
      DataScope.Factory(
          onFailure = { failure ->
            if (failure is UnreadableEntryException) {
              reported.complete(failure)
            }
          },
          clock = testClock(),
        )
        .create(backgroundScope, setOf(Labelled.sender(store = store) { _, _ -> }))

    data.outbox(Labelled)

    assertThat(reported.await().outbox).isEqualTo("labelled")
  }

  @Test
  fun `a row met before any listener is handed to the first listener`() = runTest {
    writeLog("changes", OutboxLog.Row("old", key = "k", payload = OLD_CHANGE, status = "queued"))
    val store =
      DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
        .open("changes", Change.serializer())
    assertThat(store.entries().first()).isEmpty()

    val reports = mutableListOf<EntryId>()
    store.onUndecodable { id, _ -> reports += id }

    assertThat(reports).containsExactly(EntryId("old"))
  }

  @Test
  fun `a listener set after a clear hears nothing of a row met before it`() = runTest {
    writeLog("changes", OutboxLog.Row("old", key = "k", payload = OLD_CHANGE, status = "queued"))
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    val store = stores.open("changes", Change.serializer())
    assertThat(store.entries().first()).isEmpty()
    stores.clearAll()

    val reports = mutableListOf<EntryId>()
    store.onUndecodable { id, _ -> reports += id }
    assertThat(store.entries().first()).isEmpty()

    assertThat(reports).isEmpty()
  }

  /**
   * A [CancellationException] thrown by a payload serializer fails the read that met it, and leaves
   * the row readable by the next read, since it says nothing about the stored payload. A payload
   * that fails to decode is still recorded and reported.
   */
  @Test
  fun `a cancellation thrown inside a decode in a file outbox leaves the row readable`() = runTest {
    val failing = FailOnceSerializer()
    val store =
      DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
        .open("plain", failing)
    val reports = mutableListOf<Pair<EntryId, Throwable>>()
    store.onUndecodable { id, cause -> reports += id to cause }
    val id = store.insertNew("k", "a")

    failing.next = CancellationException("Cancelled inside a decode")
    val thrown = runCatching { store.head("k") }.exceptionOrNull()

    assertThat(thrown).isNotNull().isInstanceOf<CancellationException>()
    assertThat(thrown?.message).isEqualTo("Cancelled inside a decode")
    assertThat(reports).isEmpty()
    assertThat(store.head("k")?.payload).isEqualTo("a")

    // A payload that fails to decode is still recorded and reported
    failing.next = SerializationException("Corrupt payload")
    assertThat(store.head("k")).isNull()
    assertThat(reports.map { it.first }).containsExactly(id)
    assertThat(reports.single().second.message).isEqualTo("Corrupt payload")
  }

  private fun writeLog(name: String, vararg rows: OutboxLog.Row) {
    fileSystem.createDirectories(directory)
    fileSystem.write(directory / "$name.outbox") {
      writeUtf8(Json.encodeToString(OutboxLog.serializer(), OutboxLog(rows.toList())))
    }
  }

  private companion object {
    val Groups = OutboxKey<Change>("groups")
    val GroupsByKey = OutboxKey<KeyedMutation<String, Change>>("groups")
    /** An outbox over a store of its own, which a store set opened as `changes`. */
    val Labelled = OutboxKey<Change>("labelled")
  }
}

/** A payload class whose field `on` was called `enabled` before. */
@Serializable internal data class Change(val group: String, val on: Boolean)

/** A [Change] as the version before the rename stored it, which no longer decodes. */
internal const val OLD_CHANGE = """{"group":"a","enabled":true}"""

/** The outbox called `changes`, whose stored rows hold [Change]s. */
private val ChangesOutboxKey = OutboxKey<Change>("changes")

/**
 * Checks an outbox called `changes` over [stores], which hold row [old] on key `k` in a format the
 * outbox cannot decode. Rows enqueued after it, on its key and on another, send. The full list
 * leaves it out. The failure handler hears about it once. [Outbox.operation], [Outbox.retry] and
 * [Outbox.discard] treat it as a row that does not exist.
 */
internal suspend fun TestScope.assertUnreadableRowIsHandled(stores: OutboxStores, old: EntryId) {
  val failures = TestFailures()
  val reported = CompletableDeferred<UnreadableEntryException>()
  val send = TestSend<Change>()
  val data =
    DataScope.Factory(
        onFailure = { failure ->
          failures(failure)
          if (failure is UnreadableEntryException) {
            reported.complete(failure)
          }
        },
        stores = stores,
        clock = testClock(),
      )
      .create(backgroundScope, setOf(ChangesOutboxKey.sender(send = send)))
  val outbox = data.outbox(ChangesOutboxKey, revert = send::revert)

  val sameKey = outbox.enqueue(Change("b", on = true), key = "k")
  val otherKey = outbox.enqueue(Change("c", on = false), key = "other")
  sameKey.awaitSucceeded()
  otherKey.awaitSucceeded()

  assertThat(send.sent).containsExactlyInAnyOrder(Change("b", on = true), Change("c", on = false))
  assertThat(outbox.entries.first()).isEmpty()
  val report = reported.await()
  assertThat(report.outbox).isEqualTo("changes")
  assertThat(report.id).isEqualTo(old)
  assertThat(report.cause).isNotNull().isInstanceOf<IllegalArgumentException>()

  outbox.operation(old).awaitCancelled()
  outbox.retry(old).awaitCancelled()
  assertThat(outbox.entries.first()).isEmpty()
  // Once, however many times the store read it
  failures.assertSingle<UnreadableEntryException>()
  failures.take()

  // A discard fails as for a row that does not exist, and that failure is reported too
  assertThat(outbox.discard(old).awaitFailed<IllegalStateException>()).hasMessage("$old is gone")
  assertThat(failures.take().map { it.message }).containsExactly("$old is gone")
  assertThat(send.reverted).isEmpty()
  // Only the readable rows were ever attempted
  assertThat(send.attempts).hasSize(2)
}

/** Decodes a string, and throws [next] instead from the next decode after it is set. */
private class FailOnceSerializer : KSerializer<String> {
  @Volatile var next: Throwable? = null

  override val descriptor = PrimitiveSerialDescriptor("FailOnce", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: String) {
    encoder.encodeString(value)
  }

  override fun deserialize(decoder: Decoder): String {
    next?.let {
      next = null
      throw it
    }
    return decoder.decodeString()
  }
}
