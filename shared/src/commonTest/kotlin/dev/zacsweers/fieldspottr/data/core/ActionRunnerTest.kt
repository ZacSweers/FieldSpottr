// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * How the runner settles handles and reports failures, and the guard that fails a wait which can
 * never end.
 */
class ActionRunnerTest {

  private lateinit var data: TestDataScope

  private fun TestScope.actions(): ActionRunner {
    if (!::data.isInitialized) {
      data = testDataScope()
    }
    return data.newActionRunner()
  }

  @Test
  fun `a command cancelled while it runs settles cancelled even when its block returns`() =
    runTest {
      val actions = actions()
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()

      val first =
        actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {
          started.complete(Unit)
          // Like a blocking call that finishes whether or not anyone still waits
          withContext(NonCancellable) { release.await() }
        }
      started.await()
      val second = actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {}
      testScheduler.runCurrent()
      release.complete(Unit)
      assertThat(first.await()).isEqualTo(OperationState.Cancelled)
      assertThat(second.await()).isEqualTo(OperationState.Succeeded)
      // Cancellation is not a failure, so the failure handler hears nothing
      data.actionFailures.assertNone()
    }

  @Test
  fun `a command whose scope ends while it runs settles cancelled even when its block returns`() =
    runTest {
      val scope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
      val data = testDataScopeFactory().create(scope)
      val actions = data.newActionRunner()
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()

      val command = actions.run {
        started.complete(Unit)
        withContext(NonCancellable) { release.await() }
      }
      started.await()
      scope.cancel()
      release.complete(Unit)
      assertThat(command.await()).isEqualTo(OperationState.Cancelled)
      data.actionFailures.assertNone()
    }

  @Test
  fun `awaiting a command queued behind the caller on its key fails at once`() = runTest {
    val actions = actions()
    lateinit var inner: Operation

    val outer =
      actions.run(Ordering.Keyed("k")) {
        inner = actions.run(Ordering.Keyed("k")) {}
        inner.await()
      }
    outer.await()
    outer.assertFailed<IllegalStateException>()
    // The inner command still runs once the outer one is done
    assertThat(inner.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a command awaiting its own handle fails at once`() = runTest {
    val actions = actions()
    lateinit var self: Operation

    self = actions.run { self.await() }
    self.await()
    self.assertFailed<IllegalStateException>()
  }

  @OptIn(DelicateDataLayerApi::class)
  @Test
  fun `a concurrent command awaiting its own handle fails at once`() = runTest {
    val actions = actions()
    lateinit var self: Operation

    // Assigned before the block starts, since nothing runs until runCurrent()
    self = actions.run(Ordering.Concurrent) { self.await() }
    testScheduler.runCurrent()
    self.assertFailed<IllegalStateException>()
  }

  @Test
  fun `a runner whose scope ended keeps none of the keys given to it`() = runTest {
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val actions = RealActionRunner(owner, onFailure = {})
    owner.cancel()
    var ran = 0

    val handles = List(20) { i -> actions.run(Ordering.Keyed("key$i")) { ran++ } }
    testScheduler.runCurrent()

    handles.forEach { it.assertCancelled() }
    assertThat(ran).isEqualTo(0)
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `queued supersede commands run only the newest block`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()

    val old = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "old" }
    val new = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "new" }
    old.assertPending()
    new.assertPending()
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("new")
    old.assertCancelled()
    new.assertSucceeded()
  }

  @Test
  fun `queued cancel commands run only the newest block`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()

    val old = actions.run(Ordering.Keyed("save", OnPrevious.Cancel)) { ran += "old" }
    val new = actions.run(Ordering.Keyed("save", OnPrevious.Cancel)) { ran += "new" }
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("new")
    old.assertCancelled()
    new.assertSucceeded()
  }

  @Test
  fun `the newest of three queued replacing commands is the only one that runs`() = runTest {
    for (mode in listOf(OnPrevious.Supersede, OnPrevious.Cancel)) {
      val actions = actions()
      val ran = mutableListOf<String>()

      val a = actions.run(Ordering.Keyed("save", mode)) { ran += "a" }
      val b = actions.run(Ordering.Keyed("save", mode)) { ran += "b" }
      val c = actions.run(Ordering.Keyed("save", mode)) { ran += "c" }
      // Replaced when c was submitted, so neither older block starts once work runs
      testScheduler.runCurrent()

      assertThat(ran).containsExactly("c")
      a.assertCancelled()
      b.assertCancelled()
      c.assertSucceeded()
    }
  }

  @Test
  fun `a supersede command waits for a running one and replaces only those queued`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()
    val release = CompletableDeferred<Unit>()

    val running =
      actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) {
        ran += "running"
        release.await()
      }
    testScheduler.runCurrent()
    val queued = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "queued" }
    val newest = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "newest" }
    testScheduler.runCurrent()
    // The running block is never replaced, and the newest waits for it
    running.assertRunning()
    newest.assertPending()

    release.complete(Unit)
    testScheduler.runCurrent()
    assertThat(ran).containsExactly("running", "newest")
    running.assertSucceeded()
    queued.assertCancelled()
    newest.assertSucceeded()
  }

  @Test
  fun `a combined handle over a queued command on the caller's key fails at once`() = runTest {
    val actions = actions()

    val outer =
      actions.run(Ordering.Keyed("k")) {
        val other = actions.run(Ordering.Keyed("other")) {}
        val queued = actions.run(Ordering.Keyed("k")) {}
        Operation.all(listOf(other, queued)).await()
      }
    outer.await()
    outer.assertFailed<IllegalStateException>()
  }

  @Test
  fun `waits on other keys and other runners still work`() = runTest {
    val actions = actions()
    val otherRunner = actions()

    val outer =
      actions.run(Ordering.Keyed("a")) {
        check(actions.run(Ordering.Keyed("b")) {}.await() == OperationState.Succeeded)
        check(otherRunner.run(Ordering.Keyed("a")) {}.await() == OperationState.Succeeded)
      }
    assertThat(outer.await()).isEqualTo(OperationState.Succeeded)
    data.actionFailures.assertNone()
  }

  @Test
  fun `a data scope runner reports to its own failure handler when given one`() = runTest {
    val data = testDataScope()
    val own = TestFailures()
    val boom = IllegalStateException("boom")

    data.newActionRunner(own).run(Ordering.Serial) { throw boom }.await()
    data.newActionRunner().run(Ordering.Serial) { throw boom }.await()

    assertThat(own.all).containsExactly(boom)
    assertThat(data.actionFailures.all).containsExactly(boom)
  }

  @Test
  fun `a command settles after the coroutines it starts and fails with them`() = runTest {
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val data = testDataScopeFactory().create(owner)
    val actions = data.newActionRunner()
    val finish = CompletableDeferred<Unit>()

    val command = actions.run {
      // Started in the command's own context, like a helper that launches its write
      CoroutineScope(currentCoroutineContext()).launch {
        finish.await()
        throw IllegalStateException("Write failed")
      }
    }
    val next = actions.run {}
    testScheduler.runCurrent()
    command.assertRunning()

    finish.complete(Unit)
    testScheduler.runCurrent()
    command.assertFailed<IllegalStateException>()
    next.assertSucceeded()
    data.actionFailures.assertSingle<IllegalStateException>()
    assertThat(owner.isActive).isTrue()
  }

  @Test
  fun `a command fails with the exception its block threw`() = runTest {
    val cause = IllegalStateException("Rejected")

    // Suspends first, which is when a scope around the block would hand back a recovered copy
    val handle =
      actions().run {
        yield()
        throw cause
      }

    assertThat(handle.awaitFailed<IllegalStateException>()).isSameInstanceAs(cause)
    assertThat(data.actionFailures.assertSingle<IllegalStateException>()).isSameInstanceAs(cause)
  }

  @Test
  fun `a command keeps its own error when a child's cleanup also fails`() = runTest {
    val commandError = IllegalStateException("Rejected")
    val cleanupError = IllegalArgumentException("Cleanup failed")
    val childWaiting = CompletableDeferred<Unit>()

    val handle =
      actions().run {
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            childWaiting.complete(Unit)
            awaitCancellation()
          } finally {
            throw cleanupError
          }
        }
        childWaiting.await()
        throw commandError
      }

    val cause = handle.awaitFailed<IllegalStateException>()
    assertThat(cause).isSameInstanceAs(commandError)
    assertThat(data.actionFailures.assertSingle<IllegalStateException>()).isSameInstanceAs(cause)
    // The child's failure crosses a coroutine boundary, so stack trace recovery may copy it
    val suppressed = cause.suppressedExceptions.single()
    assertThat(suppressed).isInstanceOf<IllegalArgumentException>()
    assertThat(suppressed.message).isEqualTo(cleanupError.message)
  }

  @Test
  fun `a command that cancels its own scope settles cancelled without a report`() = runTest {
    val handle =
      actions().run {
        currentCoroutineContext().cancel()
        yield()
      }

    handle.awaitCancelled()
    data.actionFailures.assertNone()
  }

  @Test
  fun `a cancellation from a call the command makes fails it`() = runTest {
    val timedOut = actions().run { withTimeout(1.seconds) { awaitCancellation() } }
    val thrown = actions().run { throw CancellationException("A dependency stopped") }

    timedOut.awaitFailed<TimeoutCancellationException>()
    thrown.awaitFailed<CancellationException>()
    assertThat(data.actionFailures.all).hasSize(2)
  }

  @Test
  fun `a rollback runs when the scope stops during a write that cannot be cancelled`() = runTest {
    var rolledBack = false
    val writing = CompletableDeferred<Unit>()
    val finishWrite = CompletableDeferred<Unit>()

    val actions = actions()
    val handle =
      actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {
        rollbackOnFailure({ rolledBack = true }) {
          withContext(NonCancellable) {
            writing.complete(Unit)
            finishWrite.await()
          }
        }
      }
    writing.await()
    // A newer command on the key cancels this one while its write runs
    actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {}
    testScheduler.runCurrent()
    finishWrite.complete(Unit)

    handle.awaitCancelled()
    assertThat(rolledBack).isTrue()
  }
}
