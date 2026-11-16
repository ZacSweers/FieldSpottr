// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Join keys on [ActionRunner.run]: calls that ask for the same work one after another share one
 * command, and a join never crosses a command in between.
 */
class ActionJoinTest {

  private lateinit var data: TestDataScope
  private val ran = mutableListOf<String>()

  private fun TestScope.actions(): ActionRunner {
    data = testDataScope()
    return data.newActionRunner()
  }

  @Test
  fun `calls with one join key made one after another share one command`() = runTest {
    val actions = actions()

    val first = actions.run(NOTE, joinKey = "fetch") { ran += "fetch" }
    val second = actions.run(NOTE, joinKey = "fetch") { ran += "fetch again" }
    testScheduler.runCurrent()

    assertThat(second).isSameInstanceAs(first)
    assertThat(ran).containsExactly("fetch")
    first.assertSucceeded()
    data.assertNoFailures()
  }

  @Test
  fun `a read never joins an earlier read across a write`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()

    val readA =
      actions.run(NOTE, joinKey = "fetch") {
        ran += "read A"
        release.await()
      }
    testScheduler.runCurrent()
    val writeB = actions.run(NOTE) { ran += "write B" }
    val readC = actions.run(NOTE, joinKey = "fetch") { ran += "read C" }

    assertThat(readC).isNotSameInstanceAs(readA)
    readA.assertRunning()
    readC.assertPending()
    release.complete(Unit)
    testScheduler.runCurrent()

    // C read after B wrote, so it never returns what A read before the write
    assertThat(ran).containsExactly("read A", "write B", "read C")
    writeB.assertSucceeded()
    readC.assertSucceeded()
  }

  @Test
  fun `a later read joins the read queued behind the write`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    actions.run(NOTE, joinKey = "fetch") { release.await() }
    actions.run(NOTE) { ran += "write" }

    val readC = actions.run(NOTE, joinKey = "fetch") { ran += "read C" }
    val readD = actions.run(NOTE, joinKey = "fetch") { ran += "read D" }
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(readD).isSameInstanceAs(readC)
    assertThat(ran).containsExactly("write", "read C")
  }

  @Test
  fun `a call joins a command that already runs`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val running =
      actions.run(NOTE, joinKey = "fetch") {
        ran += "fetch"
        release.await()
      }
    testScheduler.runCurrent()
    running.assertRunning()

    val joined = actions.run(NOTE, joinKey = "fetch") { ran += "fetch again" }
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(joined).isSameInstanceAs(running)
    assertThat(ran).containsExactly("fetch")
  }

  @Test
  fun `a settled command is not joined`() = runTest {
    val actions = actions()
    val first = actions.run(NOTE, joinKey = "fetch") { ran += "first" }
    testScheduler.runCurrent()
    first.assertSucceeded()

    val second = actions.run(NOTE, joinKey = "fetch") { ran += "second" }
    testScheduler.runCurrent()

    assertThat(second).isNotSameInstanceAs(first)
    assertThat(ran).containsExactly("first", "second")
  }

  @Test
  fun `a call made as the newest command settles starts a command of its own`() = runTest {
    val actions = actions()
    val first = actions.run(NOTE, joinKey = "fetch") { ran += "first" }
    var second: Operation? = null
    // Unconfined, so this resumes inside the state change, before the command's job completes
    // and leaves its key
    backgroundScope.launch(Dispatchers.Unconfined) {
      first.state.first { it is OperationState.Terminal }
      second = actions.run(NOTE, joinKey = "fetch") { ran += "second" }
    }
    testScheduler.runCurrent()

    assertThat(second).isNotSameInstanceAs(first)
    checkNotNull(second).assertSucceeded()
    assertThat(ran).containsExactly("first", "second")
  }

  @Test
  fun `only an equal join key on the same ordering key joins`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val fetch = actions.run(NOTE, joinKey = "fetch") { release.await() }

    val otherWork = actions.run(NOTE, joinKey = "export") {}
    val otherNote = actions.run(Ordering.Keyed("note-2"), joinKey = "fetch") {}
    val unkeyed = actions.run(Ordering.Keyed("note-3")) {}
    val afterUnkeyed = actions.run(Ordering.Keyed("note-3"), joinKey = "fetch") {}

    assertThat(otherWork).isNotSameInstanceAs(fetch)
    assertThat(otherNote).isNotSameInstanceAs(fetch)
    // A command without a join key is never joined
    assertThat(afterUnkeyed).isNotSameInstanceAs(unkeyed)
    release.complete(Unit)
  }

  @Test
  fun `equal join keys join by equals and not by identity`() = runTest {
    val actions = actions()
    data class Page(val number: Int)

    val first = actions.run(joinKey = Page(1)) { ran += "page" }
    val second = actions.run(joinKey = Page(1)) { ran += "page again" }
    val other = actions.run(joinKey = Page(2)) { ran += "next page" }
    testScheduler.runCurrent()

    assertThat(second).isSameInstanceAs(first)
    assertThat(other).isNotSameInstanceAs(first)
    // The default ordering is the serial line, which joins like any key
    assertThat(ran).containsExactly("page", "next page")
  }

  @Test
  fun `a call that joins does nothing with its own mode`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val cancelling = Ordering.Keyed("search", OnPrevious.Cancel)
    val running =
      actions.run(cancelling, joinKey = "query") {
        ran += "search"
        release.await()
      }
    testScheduler.runCurrent()

    // Without the join key, this call would cancel the running command
    val joined = actions.run(cancelling, joinKey = "query") { ran += "search again" }
    testScheduler.runCurrent()
    running.assertRunning()
    assertThat(joined).isSameInstanceAs(running)

    val superseding = Ordering.Keyed("save", OnPrevious.Supersede)
    actions.run(superseding) { release.await() }
    val queued = actions.run(superseding, joinKey = "draft") { ran += "save" }
    // Without the join key, this call would replace the queued command
    val joinedSave = actions.run(superseding, joinKey = "draft") { ran += "save again" }
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(joinedSave).isSameInstanceAs(queued)
    assertThat(ran).containsExactly("search", "save")
    queued.assertSucceeded()
  }

  @Test
  fun `a caller that stops waiting on a shared handle leaves the command and the others alone`() =
    runTest {
      val actions = actions()
      val release = CompletableDeferred<Unit>()
      val first =
        actions.run(NOTE, joinKey = "fetch") {
          release.await()
          ran += "fetch"
        }
      val second = actions.run(NOTE, joinKey = "fetch") { ran += "fetch again" }
      val leaving = launch { first.await() }
      val staying = async { second.await() }
      testScheduler.runCurrent()

      leaving.cancelAndJoin()
      testScheduler.runCurrent()
      // The command still runs, and the caller that stayed still waits
      first.assertRunning()
      assertThat(staying.isCompleted).isFalse()

      release.complete(Unit)
      assertThat(staying.await()).isEqualTo(OperationState.Succeeded)
      assertThat(ran).containsExactly("fetch")
    }

  @Test
  fun `a shared handle cannot cancel its command`() = runTest {
    val actions = actions()

    val first = actions.run(NOTE, joinKey = "fetch") {}
    val second = actions.run(NOTE, joinKey = "fetch") {}

    assertThat(first is CancellableOperation).isFalse()
    assertThat(second is CancellableOperation).isFalse()
  }

  @Test
  fun `a cancellable command is never joined`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val cancellable =
      actions.runCancellable(NOTE) {
        ran += "cancellable"
        release.await()
      }
    testScheduler.runCurrent()

    val later = actions.run(NOTE, joinKey = "fetch") { ran += "fetch" }
    assertThat(later).isNotSameInstanceAs(cancellable)
    // Its holder's cancel stops only its own command
    cancellable.cancel()
    testScheduler.runCurrent()

    cancellable.assertCancelled()
    later.assertSucceeded()
    assertThat(ran).containsExactly("cancellable", "fetch")
  }

  @Test
  fun `a newer cancel command stops a shared command for every caller`() = runTest {
    val actions = actions()
    val cancelling = Ordering.Keyed("search", OnPrevious.Cancel)
    val first = actions.run(cancelling, joinKey = "kotlin") { CompletableDeferred<Unit>().await() }
    val second = actions.run(cancelling, joinKey = "kotlin") {}
    testScheduler.runCurrent()

    val newer = actions.run(cancelling, joinKey = "kotlin multiplatform") { ran += "newer" }
    testScheduler.runCurrent()

    assertThat(second).isSameInstanceAs(first)
    first.assertCancelled()
    newer.assertSucceeded()
    assertThat(ran).containsExactly("newer")
  }

  @Test
  fun `a shared command that fails fails every caller and is reported once`() = runTest {
    val actions = actions()
    val failure = IllegalStateException("offline")

    val first = actions.run(NOTE, joinKey = "fetch") { throw failure }
    val second = actions.run(NOTE, joinKey = "fetch") {}
    testScheduler.runCurrent()

    assertThat(first.assertFailed<IllegalStateException>()).isSameInstanceAs(failure)
    assertThat(second.assertFailed<IllegalStateException>()).isSameInstanceAs(failure)
    assertThat(data.actionFailures.take()).containsExactly(failure)
  }

  @Test
  fun `a call joins the running command once a cancelled one behind it settled`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val running = actions.run(NOTE, joinKey = "fetch") { release.await() }
    testScheduler.runCurrent()
    val cancelled = actions.runCancellable(NOTE) { ran += "write" }

    cancelled.cancel()
    testScheduler.runCurrent()
    // The write never ran, so nothing lies between the two reads
    val joined = actions.run(NOTE, joinKey = "fetch") { ran += "fetch again" }
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(joined).isSameInstanceAs(running)
    assertThat(ran.isEmpty()).isTrue()
  }

  @Test
  fun `a join key needs an ordering with a key`() = runTest {
    val actions = actions()

    @OptIn(DelicateDataLayerApi::class)
    assertFailsWith<IllegalArgumentException> {
      actions.run(Ordering.Concurrent, joinKey = "fetch") { ran += "fetch" }
    }
    testScheduler.runCurrent()

    assertThat(ran.isEmpty()).isTrue()
  }

  private companion object {
    val NOTE = Ordering.Keyed("note-1")
  }
}
