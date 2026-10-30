// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.RealDataActions
import dev.zacsweers.fieldspottr.data.core.impl.invoke
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** What a settled handle keeps reachable, checked by walking the object graph from it. */
class HandleRetentionTest {

  @Test
  fun `a settled handle does not keep its command's inputs`() = runTest {
    val input = ByteArray(1 shl 20)
    val handle = testDataScope().newActions().run { check(input.isNotEmpty()) }
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(input)).isFalse()
  }

  @Test
  fun `a settled recheck does not keep the value that went away`() = runTest {
    val cleared = ByteArray(1 shl 20)
    val store = QueryStore.inMemory(Fetched(cleared, TestClock.DEFAULT_START))
    val fetch = TestFetch { ByteArray(1) }
    val query =
      testDataScope().query(store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    val collector = backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()

    fetch.hold()
    store.update { null }
    testScheduler.runCurrent()
    // Joins the recheck the collector started for the clear
    val handle = query.requestFetch(force = false)
    fetch.release()
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(handle.reaches(cleared)).isFalse()
  }

  @Test
  fun `a settled handle does not keep its runner's failure handler`() = runTest {
    val reports = ByteArray(1 shl 20)
    val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
    val handle = RealDataActions(owner, onFailure = { reports[0] = 1 }).run {}
    testScheduler.runCurrent()
    owner.cancel()
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(reports)).isFalse()
  }

  @Test
  fun `a superseded handle does not keep the failure of the command before it`() = runTest {
    val actions = RealDataActions(backgroundScope, onFailure = {})
    val response = ByteArray(1 shl 20)
    val release = CompletableDeferred<Unit>()
    val failed =
      actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {
        release.await()
        throw ResponseException(response)
      }
    testScheduler.runCurrent()
    // Queued behind the running command, then replaced before it starts
    val superseded = actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {}
    val newest = actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {}
    release.complete(Unit)
    testScheduler.runCurrent()

    failed.assertFailed<ResponseException>()
    superseded.assertCancelled()
    newest.assertSucceeded()
    assertThat(actions.keysHeld).isEqualTo(0)
    assertThat(superseded.reaches(response)).isFalse()
    // A failed handle keeps its own result
    assertThat(failed.reaches(response)).isTrue()
  }

  @Test
  fun `a query does not keep a failed automatic check after a later refresh`() = runTest {
    val state = MutableStateFlow<Fetched<String>?>(Fetched("old", TestClock.DEFAULT_START))
    val store =
      QueryStore.of(state, clear = { state.value = null }) { value, fetchedAt ->
        state.value = Fetched(value, fetchedAt)
      }
    var thrown: ResponseException? = null
    val fetch =
      TestFetch<String> {
        throw ResponseException(ByteArray(1 shl 20)).also { thrown = it }
      }
    val query =
      DataScope.Factory(onFailure = {}, clock = testClock())
        .create(backgroundScope)
        .query(store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    val collector = backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()

    // Emptied under the collector, whose automatic check then fails
    state.value = null
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()
    fetch.respond = { "new" }
    query.requestFetch(force = true)
    testScheduler.runCurrent()

    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
    assertThat(state.value?.value).isEqualTo("new")
    assertThat(query.reaches(checkNotNull(thrown).response)).isFalse()
  }

  @Test
  fun `a settled handle does not keep its key`() = runTest {
    val key = ByteArray(1 shl 20)
    val handle = testDataScope().newActions().run(Ordering.Keyed(key)) {}
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(key)).isFalse()
  }
}

/** A failure that carries the response that caused it, like a server error body. */
private class ResponseException(val response: ByteArray) : Exception("Rejected")

/**
 * Whether [target] is reachable from this object through instance fields, arrays, collections, maps
 * and atomic references. JDK fields that reflection cannot open are read through those interfaces
 * instead.
 */
private fun Any.reaches(target: Any): Boolean {
  val seen = IdentityHashMap<Any, Unit>()
  val queue = ArrayDeque<Any>()
  queue += this
  while (queue.isNotEmpty()) {
    val next = queue.removeFirst()
    if (next === target) {
      return true
    }
    // Classes, threads and loaders lead to everything, and none of them is how a handle holds data
    if (next is Class<*> || next is Thread || next is ClassLoader || seen.put(next, Unit) != null) {
      continue
    }
    check(seen.size < 1_000_000) { "The graph from $this is too large to walk" }
    when (next) {
      is Array<*> -> next.filterNotNullTo(queue)
      is Collection<*> -> next.filterNotNullTo(queue)
      is Map<*, *> -> next.forEach { (key, value) -> listOfNotNull(key, value).toCollection(queue) }
      is AtomicReference<*> -> next.get()?.let(queue::add)
    }
    var type: Class<*>? = next.javaClass
    while (type != null && !type.isArray) {
      for (field in type.declaredFields) {
        val readable =
          !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive && field.trySetAccessible()
        if (readable) {
          field.get(next)?.let(queue::add)
        }
      }
      type = type.superclass
    }
  }
  return false
}
