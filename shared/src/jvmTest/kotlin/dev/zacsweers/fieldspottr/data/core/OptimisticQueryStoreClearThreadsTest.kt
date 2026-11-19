// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A clear on one thread while a change on another thread is admitted and not yet enqueued. Real
 * threads, since only a mutate held inside its own call opens that window. The key's serializer
 * holds the mutate: the set encodes the key for the row's order right before it enqueues the row.
 */
class OptimisticQueryStoreClearThreadsTest {
  @Test
  fun `clear waits for a change admitted before it and discards its row`() = runBlocking {
    val keys = PausingKeys()
    val stores = InMemoryOutboxStores()
    val failures = TestFailures()
    val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val sets =
        DataScope.Factory(onFailure = failures, online = MutableStateFlow(false), stores = stores)
          .create(owner, setOf(Tags.sender { _, _ -> }))
          .optimisticStores(
            Tags,
            base = { QueryStore.inMemory<List<String>>() },
            keySerializer = keys,
            apply = { current, add -> current.orEmpty() + add.tag },
          )
      val store = sets("k")
      keys.pause.set(true)
      val mutating = async(Dispatchers.IO) { store.mutate(Add("b")) }
      check(keys.entered.await(8, TimeUnit.SECONDS))
      val clearing = async(Dispatchers.Default) { store.clear() }
      // A clear that misses the change returns at once. One that waits for it is still running.
      withTimeoutOrNull(500) { clearing.join() }
      keys.resume.countDown()
      withTimeout(8000) {
        val handle = mutating.await()
        clearing.await()
        handle.stored.await()
        val rows = stores.open("clearThreadTags", Tags.serializer).entries().first()
        assertThat(rows.filter { it.status == OutboxStatus.Queued }).isEmpty()
        assertThat(store.data.first()).isNull()
        assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
      }
      failures.assertNone()
    } finally {
      keys.resume.countDown()
      owner.cancel()
    }
  }

  /** String keys whose next encode, once [pause] is set, waits until [resume]. */
  private class PausingKeys : KSerializer<String> {
    val pause = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)

    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: String) {
      if (pause.compareAndSet(true, false)) {
        entered.countDown()
        check(resume.await(8, TimeUnit.SECONDS))
      }
      encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String = decoder.decodeString()
  }

  private companion object {
    val Tags = OutboxKey<KeyedMutation<String, Add>>("clearThreadTags")
  }
}
