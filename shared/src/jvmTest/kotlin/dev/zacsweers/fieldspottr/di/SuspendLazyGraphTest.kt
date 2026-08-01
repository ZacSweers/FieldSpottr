// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class SuspendLazyGraphTest {

  @Test
  fun `consumer construction defers and memoizes its suspend dependency`() = runTest {
    val factory = FakeSuspendResourceFactory { attempt -> FakeSuspendResource(attempt) }
    val graph = createGraph(factory)

    assertThat(factory.attempts).isEqualTo(0)
    val resource = graph.consumer.resource
    assertThat(factory.attempts).isEqualTo(0)
    assertThat(resource.isInitialized()).isFalse()

    val first = resource.await()
    val second = resource.await()

    assertThat(first).isSameInstanceAs(second)
    assertThat(factory.attempts).isEqualTo(1)
    assertThat(resource.isInitialized()).isTrue()
  }

  @Test
  fun `concurrent callers share one initialization`() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val factory = FakeSuspendResourceFactory { attempt ->
      started.complete(Unit)
      release.await()
      FakeSuspendResource(attempt)
    }
    val resource = createGraph(factory).consumer.resource

    val firstCall = async(start = CoroutineStart.UNDISPATCHED) { resource.await() }
    started.await()
    val secondCall = async(start = CoroutineStart.UNDISPATCHED) { resource.await() }

    try {
      assertThat(factory.attempts).isEqualTo(1)
    } finally {
      release.complete(Unit)
    }
    assertThat(firstCall.await()).isSameInstanceAs(secondCall.await())
    assertThat(factory.attempts).isEqualTo(1)
  }

  @Test
  fun `failed initialization can be retried`() = runTest {
    val factory = FakeSuspendResourceFactory { attempt ->
      if (attempt == 1) {
        error("First attempt failed")
      }
      FakeSuspendResource(attempt)
    }
    val resource = createGraph(factory).consumer.resource

    assertFailsWith<IllegalStateException> { resource.await() }
    assertThat(resource.isInitialized()).isFalse()
    assertThat(resource.await().attempt).isEqualTo(2)
    assertThat(factory.attempts).isEqualTo(2)
    assertThat(resource.isInitialized()).isTrue()
  }

  @Test
  fun `cancelled initialization can be retried`() = runTest {
    val started = CompletableDeferred<Unit>()
    val factory = FakeSuspendResourceFactory { attempt ->
      if (attempt == 1) {
        started.complete(Unit)
        awaitCancellation()
      }
      FakeSuspendResource(attempt)
    }
    val resource = createGraph(factory).consumer.resource

    val firstCall = launch(start = CoroutineStart.UNDISPATCHED) { resource.await() }
    started.await()
    firstCall.cancelAndJoin()

    assertThat(resource.isInitialized()).isFalse()
    assertThat(resource.await().attempt).isEqualTo(2)
    assertThat(factory.attempts).isEqualTo(2)
    assertThat(resource.isInitialized()).isTrue()
  }

  private fun createGraph(factory: FakeSuspendResourceFactory): SuspendLazyTestGraph {
    return createGraphFactory<SuspendLazyTestGraph.Factory>().create(factory)
  }
}

internal data class FakeSuspendResource(val attempt: Int)

internal class FakeSuspendResourceFactory(
  private val createResource: suspend (attempt: Int) -> FakeSuspendResource
) {
  var attempts = 0
    private set

  suspend fun create(): FakeSuspendResource {
    attempts += 1
    return createResource(attempts)
  }
}

internal abstract class SuspendLazyTestScope private constructor()

@SingleIn(SuspendLazyTestScope::class)
@Inject
internal class SuspendLazyTestConsumer(val resource: SuspendLazy<FakeSuspendResource>)

@DependencyGraph(SuspendLazyTestScope::class)
internal interface SuspendLazyTestGraph {
  val consumer: SuspendLazyTestConsumer

  @Provides
  suspend fun provideResource(factory: FakeSuspendResourceFactory): FakeSuspendResource =
    factory.create()

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides factory: FakeSuspendResourceFactory): SuspendLazyTestGraph
  }
}
