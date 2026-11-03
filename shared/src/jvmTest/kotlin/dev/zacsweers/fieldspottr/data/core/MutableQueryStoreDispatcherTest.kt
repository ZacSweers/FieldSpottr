// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/** Where a mutable store's overlay runs, which takes a named real thread to tell. */
class MutableQueryStoreDispatcherTest {
  @Serializable data class Add(val tag: String)

  @Test
  fun `apply runs on the data scope's dispatcher and not the collector's`() = runTest {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "data") }
    val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    try {
      // Offline, so the row stays and every value the collector sees is an overlay
      val data = testDataScopeFactory(online = false).create(scope)
      val threads = CopyOnWriteArraySet<String>()
      val store =
        data.mutableStore<List<String>, Add>(
          name = "tags",
          base = QueryStore.inMemory(Fetched(listOf("a"), START)),
          apply = { current, add ->
            // Coroutine debug mode appends the coroutine's name to the thread's
            threads += Thread.currentThread().name.substringBefore(" @")
            val tags = current.orEmpty()
            if (add.tag in tags) {
              tags
            } else {
              tags + add.tag
            }
          },
          send = { _, _ -> },
        )
      store.mutate(Add("b"))

      // Collected from the test's thread, where the overlay would otherwise run
      assertThat(store.data.first { it?.value == listOf("a", "b") }?.value)
        .isEqualTo(listOf("a", "b"))
      assertThat(threads).isEqualTo(setOf("data"))
    } finally {
      scope.cancel()
      executor.shutdown()
    }
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)
