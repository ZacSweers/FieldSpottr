// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.examples

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxSender
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.awaitOrThrow
import dev.zacsweers.fieldspottr.data.core.inMemoryOutboxStore
import dev.zacsweers.fieldspottr.data.core.sender
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// The guide's delete-with-undo example, built and tested.

@Serializable @JvmInline value class NoteId(val value: String)

@Serializable
sealed interface NoteCommand {
  @Serializable data class Delete(val noteId: NoteId) : NoteCommand
}

internal val NoteOutboxKey = OutboxKey<NoteCommand>("notes")

private val UNDO_WINDOW = 5.seconds

// Deletes on the server, then drops the hidden row. The app data scope drains it, also before
// NoteRepository exists.
internal fun notesSender(
  api: NoteApi,
  db: NoteQueries,
  io: CoroutineDispatcher,
): OutboxSender<*> = NoteOutboxKey.sender { command, id ->
  when (command) {
    is NoteCommand.Delete -> {
      api.delete(command.noteId, idempotencyKey = id.value)
      withContext(io) { db.delete(command.noteId) }
    }
  }
}

class NoteRepository(
  data: DataScope,
  // Lists select WHERE hidden = 0
  private val db: NoteQueries,
  private val io: CoroutineDispatcher,
) {
  private val actions = data.newActionRunner()
  // A failed insert, or a delete the server rejects for good, brings the note back
  private val outbox = data.outbox(NoteOutboxKey, revert = { command, _ -> revert(command) })

  /**
   * Hides the note now and queues the server delete once the window passes. The handle settles
   * Cancelled when an undo won, and succeeds once the row is stored.
   */
  fun delete(noteId: NoteId): Operation {
    // Gone from every list at once. An undo cancels the wait.
    val window =
      actions.run(Ordering.Keyed(noteId, OnPrevious.Cancel)) {
        setHidden(noteId, true)
        delay(UNDO_WINDOW)
      }
    // Waits for the window on the same key. An undo cancels only Cancel commands, so it never
    // cancels this one. A late undo waits behind it and changes nothing.
    val commit =
      actions.run(Ordering.Keyed(noteId)) {
        if (window.await() == OperationState.Succeeded) {
          // Fails with the insert's cause, after the outbox's revert brought the note back
          outbox.enqueue(NoteCommand.Delete(noteId), key = noteId.value).stored.awaitOrThrow()
        } else {
          // An undo won, also one that landed as the wait ended
          setHidden(noteId, false)
        }
      }
    // Cancelled when an undo won, else how the commit ended
    return Operation.all(window, commit)
  }

  /** Cancels a delete still inside its window. The block is empty: the Cancel ordering does it. */
  fun undo(noteId: NoteId): Operation = actions.run(Ordering.Keyed(noteId, OnPrevious.Cancel)) {}

  private suspend fun setHidden(noteId: NoteId, hidden: Boolean) =
    withContext(io) { db.setHidden(noteId, hidden) }

  private suspend fun revert(command: NoteCommand) =
    when (command) {
      is NoteCommand.Delete -> setHidden(command.noteId, false)
    }
}

interface NoteApi {
  suspend fun delete(noteId: NoteId, idempotencyKey: String)
}

/** Stands in for the SQLDelight queries. Each note maps to whether it is hidden. */
class NoteQueries(noteIds: Set<NoteId>) {
  val notes = MutableStateFlow(noteIds.associateWith { false })

  /** The notes a list shows. */
  val visible: Set<NoteId>
    get() = notes.value.filterValues { hidden -> !hidden }.keys

  fun setHidden(noteId: NoteId, hidden: Boolean) {
    notes.update {
      if (noteId in it) {
        it + (noteId to hidden)
      } else {
        it
      }
    }
  }

  fun delete(noteId: NoteId) {
    notes.update { it - noteId }
  }
}

/** Records each server delete. */
class FakeNoteApi : NoteApi {
  val deleted = mutableListOf<NoteId>()

  override suspend fun delete(noteId: NoteId, idempotencyKey: String) {
    deleted += noteId
  }
}

class DeleteWithUndoTest {
  private val api = FakeNoteApi()
  private val db = NoteQueries(setOf(NOTE))
  private lateinit var data: TestDataScope

  /**
   * A repository whose outbox sends with [notesSender], or keeps its rows in [rows] and sends
   * nothing when [rows] is set.
   */
  private fun TestScope.repository(
    online: Boolean = true,
    rows: OutboxStore<NoteCommand>? = null,
  ): NoteRepository {
    val io = StandardTestDispatcher(testScheduler)
    val sender =
      if (rows == null) {
        notesSender(api, db, io)
      } else {
        NoteOutboxKey.sender(store = rows) { _, _ -> }
      }
    data = testDataScope(online = online, senders = setOf(sender))
    return NoteRepository(data, db, io)
  }

  @Test
  fun `an undo inside the window restores the note and queues nothing`() = runTest {
    val notes = repository()

    val delete = notes.delete(NOTE)
    runCurrent()
    assertThat(db.visible).isEmpty()

    advanceTimeBy(UNDO_WINDOW / 2)
    notes.undo(NOTE).awaitSucceeded()

    delete.awaitCancelled()
    assertThat(db.visible).isEqualTo(setOf(NOTE))
    advanceTimeBy(UNDO_WINDOW * 2)
    assertThat(data.outboxStore<NoteCommand>(NoteOutboxKey.name).entries().first()).isEmpty()
    assertThat(api.deleted).isEmpty()
  }

  @Test
  fun `an undo after the window leaves the queued deletion alone`() = runTest {
    val notes = repository(online = false)

    val delete = notes.delete(NOTE)
    delete.awaitSucceeded()
    notes.undo(NOTE).awaitSucceeded()

    // Stored and waiting for the network, and still hidden
    assertThat(db.visible).isEmpty()
    data.online.value = true
    runCurrent()
    assertThat(api.deleted).containsExactly(NOTE)
    assertThat(db.notes.value).isEmpty()
  }

  @Test
  fun `an undo while the row is stored does not cancel the deletion`() = runTest {
    val rows = rows(insert = { delay(1.seconds) })
    // Offline, so the row stays in the store
    val notes = repository(online = false, rows = rows)

    val delete = notes.delete(NOTE)
    advanceTimeBy(UNDO_WINDOW + 0.5.seconds)
    // The insert is still running
    assertThat(delete.state.value is OperationState.Terminal).isFalse()
    val undo = notes.undo(NOTE)

    delete.awaitSucceeded()
    undo.awaitSucceeded()
    assertThat(db.visible).isEmpty()
    assertThat(rows.entries().first().map { it.payload }).containsExactly(NoteCommand.Delete(NOTE))
  }

  @Test
  fun `the delete succeeds only once the row is stored`() = runTest {
    val rows = rows(insert = { delay(1.seconds) })
    // Offline, so the row stays in the store
    val notes = repository(online = false, rows = rows)

    val delete = notes.delete(NOTE)
    advanceTimeBy(UNDO_WINDOW + 0.9.seconds)
    assertThat(rows.entries().first()).isEmpty()
    assertThat(delete.state.value is OperationState.Terminal).isFalse()

    advanceTimeBy(0.2.seconds)
    assertThat(rows.entries().first().map { it.payload }).containsExactly(NoteCommand.Delete(NOTE))
    delete.assertSucceeded()
  }

  @Test
  fun `a failed insert restores the note and fails the delete with its cause`() = runTest {
    val diskFull = IllegalStateException("Disk full")
    val notes = repository(rows = rows(insert = { throw diskFull }))

    val delete = notes.delete(NOTE)

    val failure = delete.awaitFailed<IllegalStateException>()
    // Stack trace recovery on the JVM can copy the exception, with the original as its cause
    assertThat(generateSequence<Throwable>(failure) { it.cause }.toList()).contains(diskFull)
    assertThat(db.visible).isEqualTo(setOf(NOTE))
  }

  /** Rows in memory whose insert runs [insert] first. */
  private fun rows(insert: suspend () -> Unit): OutboxStore<NoteCommand> {
    val rows = inMemoryOutboxStore<NoteCommand>()
    return object : OutboxStore<NoteCommand> by rows {
      override suspend fun insert(id: EntryId, key: String, payload: NoteCommand) {
        insert()
        rows.insert(id, key, payload)
      }
    }
  }

  private companion object {
    val NOTE = NoteId("note-1")
  }
}
