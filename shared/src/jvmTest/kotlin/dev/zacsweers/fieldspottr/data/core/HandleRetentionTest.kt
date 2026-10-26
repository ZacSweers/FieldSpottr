// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
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
  fun `a settled handle does not keep its key`() = runTest {
    val key = ByteArray(1 shl 20)
    val handle = testDataScope().newActions().run(Ordering.Keyed(key)) {}
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(key)).isFalse()
  }
}

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
