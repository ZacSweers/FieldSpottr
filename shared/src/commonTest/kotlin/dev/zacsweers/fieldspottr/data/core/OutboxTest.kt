// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class OutboxTest {

  private val clock = FakeClock(START)
  private val online = MutableStateFlow(true)
  private val sent = mutableListOf<String>()
  private val reverted = mutableListOf<String>()
  private val failures = mutableListOf<Throwable>()
  // Sends of these payloads wait until their gate completes
  private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
  private var sendFailure: (String) -> Throwable? = { null }

  private fun TestScope.outbox(
    retry: RetryPolicy = RetryPolicy(maxAttempts = 3) { 1.seconds }
  ): Outbox<String> =
    Outbox(
      scope = backgroundScope,
      actions = DataActions(backgroundScope) { failures += it },
      send = { payload ->
        gates[payload]?.await()
        sendFailure(payload)?.let { throw it }
        sent += payload
      },
      revert = { reverted += it },
      retry = retry,
      online = online,
      clock = clock,
    )

  @Test
  fun `an enqueued row applies, inserts and sends`() = runTest {
    val outbox = outbox()
    var applied = false

    val handle = outbox.enqueue("a") { applied = true }

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(applied).isTrue()
    assertThat(sent).containsExactly("a")
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows on one key send in order and other keys do not wait`() = runTest {
    gates["a"] = CompletableDeferred()
    val outbox = outbox()

    outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "other")

    assertThat(c.await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("c")
    gates.getValue("a").complete(Unit)
    assertThat(b.await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("c", "a", "b")
  }

  @Test
  fun `a failed apply inserts nothing and fails the handle`() = runTest {
    val outbox = outbox()

    val handle = outbox.enqueue("a") { error("apply failed") }

    assertThat(handle.await()).isInstanceOf<OperationState.Failed>()
    assertThat(outbox.entries.first()).isEmpty()
    assertThat(sent).isEmpty()
  }

  @Test
  fun `a transient failure backs off, then sends`() = runTest {
    var failuresLeft = 1
    sendFailure = {
      if (failuresLeft-- > 0) {
        IllegalStateException("flaky")
      } else {
        null
      }
    }
    val outbox = outbox()

    val handle = outbox.enqueue("a")
    val waiting = outbox.entries.first { it.singleOrNull()?.retryAt != null }.single()
    assertThat(waiting.attempts).isEqualTo(1)
    assertThat(waiting.retryAt).isEqualTo(START + 1.seconds)

    // The deadline is wall time on the row, the timer is coroutine time, and both must pass
    clock.now = START + 1.seconds
    testScheduler.advanceTimeBy(1.seconds)
    testScheduler.runCurrent()

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("a")
  }

  @Test
  fun `a permanent failure fails the row and reverts it once`() = runTest {
    sendFailure = { PermanentFailure("rejected") }
    val outbox = outbox()

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(reverted).containsExactly("a")
    assertThat(outbox.entries.first().single().status).isInstanceOf<OutboxStatus.Failed>()
  }

  @Test
  fun `running out of attempts fails the row`() = runTest {
    sendFailure = { IllegalStateException("flaky") }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 1) { 1.seconds })

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(reverted).containsExactly("a")
  }

  @Test
  fun `retry requeues a failed row without reverting again`() = runTest {
    sendFailure = { PermanentFailure("rejected") }
    val outbox = outbox()
    outbox.enqueue("a").await()
    val id = outbox.entries.first().single().id

    sendFailure = { null }

    assertThat(outbox.retry(id).await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("a")
    assertThat(reverted).containsExactly("a")
  }

  @Test
  fun `discard reverts a queued row and cancels its handle`() = runTest {
    online.value = false
    val outbox = outbox()
    val handle = outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    assertThat(outbox.discard(id).await()).isEqualTo(OperationState.Succeeded)

    assertThat(reverted).containsExactly("a")
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows wait for the network`() = runTest {
    online.value = false
    val outbox = outbox()

    val handle = outbox.enqueue("a")
    outbox.entries.first { it.isNotEmpty() }
    testScheduler.runCurrent()
    assertThat(sent).isEmpty()

    online.value = true
    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("a")
  }

  @Test
  fun `operation re-attaches to a row by id`() = runTest {
    gates["a"] = CompletableDeferred()
    val outbox = outbox()
    outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    val reattached = outbox.operation(id)
    gates.getValue("a").complete(Unit)

    assertThat(reattached.await()).isEqualTo(OperationState.Succeeded)
    assertThat(outbox.operation(EntryId("unknown")).await()).isEqualTo(OperationState.Cancelled)
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
