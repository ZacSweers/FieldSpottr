// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class OutboxTest {

  private val send = TestSend<String>()
  private lateinit var data: TestDataScope

  private fun TestScope.outbox(
    online: Boolean = true,
    retry: RetryPolicy = RetryPolicy(maxAttempts = 3) { 1.seconds },
  ): Outbox<String> {
    data = testDataScope(clock = testClock(START), online = online)
    return data.outbox<String>("test", send, send::revert, retry)
  }

  @Test
  fun `an enqueued row applies its change before it inserts and sends`() = runTest {
    val outbox = outbox()
    var applied = false

    val handle = outbox.enqueue("a") { applied = true }

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(applied).isTrue()
    assertThat(send.sent).containsExactly("a")
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows on one key send in order and other keys do not wait`() = runTest {
    send.hold("a")
    val outbox = outbox()

    outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "other")

    assertThat(c.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("c")
    send.release("a")
    assertThat(b.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("c", "a", "b")
  }

  @Test
  fun `a failed apply inserts nothing and fails the handle`() = runTest {
    val outbox = outbox()

    val handle = outbox.enqueue("a") { error("apply failed") }

    assertThat(handle.await()).isInstanceOf<OperationState.Failed>()
    assertThat(outbox.entries.first()).isEmpty()
    assertThat(send.sent).isEmpty()
  }

  @Test
  fun `a transient failure backs off and then sends`() = runTest {
    send.failTransiently()
    val outbox = outbox()

    val handle = outbox.enqueue("a")
    val waiting = outbox.entries.first { it.singleOrNull()?.retryAt != null }.single()
    assertThat(waiting.attempts).isEqualTo(1)
    assertThat(waiting.retryAt).isEqualTo(START + 1.seconds)

    // The deadline on the row and the timer that wakes the drain both follow virtual time
    advanceTimeBy(1.seconds)
    runCurrent()

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a permanent failure fails the row and reverts it once`() = runTest {
    send.failPermanently()
    val outbox = outbox()

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(send.reverted).containsExactly("a")
    assertThat(outbox.entries.first().single().status).isInstanceOf<OutboxStatus.Failed>()
  }

  @Test
  fun `running out of attempts fails the row`() = runTest {
    send.failure = { _, _ -> IllegalStateException("flaky") }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 1) { 1.seconds })

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(send.reverted).containsExactly("a")
  }

  @Test
  fun `retry requeues a failed row without reverting again`() = runTest {
    send.failPermanently()
    val outbox = outbox()
    outbox.enqueue("a").await()
    val id = outbox.entries.first().single().id

    send.succeed()

    assertThat(outbox.retry(id).await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
    assertThat(send.reverted).containsExactly("a")
  }

  @Test
  fun `discard reverts a queued row and cancels its handle`() = runTest {
    val outbox = outbox(online = false)
    val handle = outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    assertThat(outbox.discard(id).await()).isEqualTo(OperationState.Succeeded)

    assertThat(send.reverted).containsExactly("a")
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows wait for the network`() = runTest {
    val outbox = outbox(online = false)

    val handle = outbox.enqueue("a")
    outbox.entries.first { it.isNotEmpty() }
    testScheduler.runCurrent()
    assertThat(send.sent).isEmpty()

    data.online.value = true
    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `operation re-attaches to a row by id`() = runTest {
    send.hold("a")
    val outbox = outbox()
    outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    val reattached = outbox.operation(id)
    send.release("a")

    assertThat(reattached.await()).isEqualTo(OperationState.Succeeded)
    assertThat(outbox.operation(EntryId("unknown")).await()).isEqualTo(OperationState.Cancelled)
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
