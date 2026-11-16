// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.clearFetched
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/** What an optimistic store set keeps reachable, checked by walking the object graph from it. */
class OptimisticQueryStoreRetentionTest {
  @Serializable data class Star(val starred: Boolean)

  data class Post(val body: String, val starred: Boolean = false)

  @Test
  fun `a sent change lets go of the bodies that later overlapping fetches replaced`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val stored = MutableStateFlow<Fetched<Post>?>(Fetched(Post("old"), START))
    // What the store emits, which the test moves by hand, like a store whose emissions run late
    val emitted = MutableStateFlow(stored.value)
    val base =
      QueryStore.of(
        emitted,
        clear = stored::clearFetched,
        write = { fetched, keepsStored ->
          stored.writeFetched(fetched, keepsStored)
        },
      )
    fun DataScope.posts() =
      optimisticStores(
        PostsOutbox,
        base = { base },
        apply = { post, star -> post?.copy(starred = star.starred) },
      )
    val posts = scopes.create(owner, setOf(PostsOutbox.sender { _, _ -> })).posts()
    val post = posts("p")

    // Eight refreshes read the server before the edit reached it, and land after its send in the
    // order they started, each with a large body of its own
    val fetchedAt = List(8) { START + (it + 1).milliseconds }
    advanceTimeBy(1.seconds)
    post.mutate(Star(true)).awaitSucceeded()
    val bodies = List(8) { "$it".repeat(1 shl 20) }
    for (i in bodies.indices) {
      post.writeFetched(Post(bodies[i]), fetchedAt[i]) { storedAt, _ ->
        storedAt != null && storedAt > fetchedAt[i]
      }
    }
    assertThat(stored.value)
      .isEqualTo(Fetched(Post(bodies.last(), starred = true), fetchedAt.last()))

    // A late emission of a body that a later fetch replaced still shows the edit
    emitted.value = Fetched(Post(bodies[3], starred = true), fetchedAt[3])
    assertThat(post.data.first()?.value).isEqualTo(Post(bodies[3], starred = true))
    emitted.value = stored.value
    assertThat(post.data.first()?.value).isEqualTo(Post(bodies.last(), starred = true))

    // Only the body the store holds stays reachable
    for (body in bodies.dropLast(1)) {
      assertThat(posts.reaches(body)).isFalse()
    }
    assertThat(posts.reaches(bodies.last())).isTrue()

    // A reopened set, which has no records, shows the edit from the stored body
    owner.cancel()
    owner.coroutineContext.job.join()
    val reopened =
      scopes.create(backgroundScope, setOf(PostsOutbox.sender { _, _ -> })).posts()("p")
    assertThat(reopened.data.first()?.value).isEqualTo(Post(bodies.last(), starred = true))
    scopes.assertNoFailures()
  }

  /**
   * The outbox and the call's watcher follow the handle while the change waits. What the set knows
   * of the change, which views read, needs only the change itself.
   */
  @Test
  fun `a waiting change's state does not keep its handle`() = runTest {
    val data =
      testDataScopeFactory()
        .create(backgroundScope, setOf(PostsOutbox.sender { _, _ -> awaitCancellation() }))
    val posts =
      data.optimisticStores(
        PostsOutbox,
        base = QueryStore.inMemoryByKey<String, Post>(),
        apply = { post, star -> post?.copy(starred = star.starred) },
      )

    val handle = posts.mutate("p", Star(true))
    runCurrent()

    assertThat(handle.state.value).isEqualTo(OperationState.Running)
    assertThat(posts.knownChanges).isEqualTo(1)
    assertThat(posts.stateValue().reaches(handle)).isFalse()
    data.assertNoFailures()
  }

  @Test
  fun `an idle set does not keep the stored milestone of its last change`() = runTest {
    val data = testDataScopeFactory().create(backgroundScope, setOf(PostsOutbox.sender { _, _ -> }))
    val posts =
      data.optimisticStores(
        PostsOutbox,
        base = QueryStore.inMemoryByKey<String, Post>(),
        apply = { post, star -> post?.copy(starred = star.starred) },
      )

    val handle = posts.mutate("p", Star(true))
    handle.awaitSucceeded()
    runCurrent()

    assertThat(posts.reaches(handle.stored)).isFalse()
    data.assertNoFailures()
  }
}

/**
 * The set's current state, which every view reads. It is private to the set, so a test reads it by
 * reflection to tell what the state itself keeps from what the outbox keeps.
 */
private fun OptimisticQueryStores<*, *, *>.stateValue(): Any {
  val field = javaClass.getDeclaredField("state")
  field.isAccessible = true
  return (field.get(this) as StateFlow<*>).value!!
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

private val PostsOutbox =
  OutboxKey<KeyedMutation<String, OptimisticQueryStoreRetentionTest.Star>>("posts")
