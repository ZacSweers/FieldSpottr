// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * What a test fetch records: each call, the calls that were cancelled, and how many ran at once. It
 * can also hold calls until the test releases them. [TestFetch] and [TestKeyedFetch] build on it.
 * Every record is safe to write from any thread, since a fetch can run on an I/O dispatcher.
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class FetchRecorder<K> internal constructor() {
  // Compare-and-set only. Nothing observes these.
  private val started = AtomicReference(persistentListOf<K>())
  private val cancelledCalls = AtomicReference(persistentListOf<K>())
  private val inFlight = AtomicInt(0)
  private val mostInFlight = AtomicInt(0)
  // What held calls wait on, or null while not holding. Each hold opens a new gate, so a release
  // reaches every call that entered before it, even when the test holds again at once.
  private val gate = AtomicReference<CompletableDeferred<Unit>?>(null)

  /** Each call's key, in the order the calls started. */
  val calls: List<K>
    get() = started.load()

  /** How many calls have started. */
  val callCount: Int
    get() = calls.size

  /**
   * The keys of calls that were cancelled before they returned, like a forced fetch that a newer
   * forced request replaced.
   */
  val cancelled: List<K>
    get() = cancelledCalls.load()

  /** How many calls are running now, held ones included. */
  val running: Int
    get() = inFlight.load()

  /** The most calls that were running at once. */
  val maxRunning: Int
    get() = mostInFlight.load()

  /** Makes calls that start from now on wait before they respond, until [release]. */
  fun hold() {
    gate.update { it ?: CompletableDeferred() }
  }

  /** Lets every waiting call respond, and stops holding. */
  fun release() {
    gate.exchange(null)?.complete(Unit)
  }

  /** Records a call for [key], waits while held, then returns what [respond] gives. */
  protected suspend fun <T> record(key: K, respond: suspend () -> T): T {
    started.update { it.adding(key) }
    val now = inFlight.incrementAndFetch()
    mostInFlight.update { maxOf(it, now) }
    try {
      gate.load()?.await()
      return respond()
    } catch (e: CancellationException) {
      cancelledCalls.update { it.adding(key) }
      throw e
    } finally {
      inFlight.decrementAndFetch()
    }
  }
}

/**
 * A query fetch the test controls. Pass it as the fetch itself, `fetch = fetch`. Each call records
 * itself, waits while [hold] is in effect, then returns what [respond] gives, so a test can change
 * the answer or make it throw between calls.
 */
class TestFetch<T>(
  /** What the next call returns or throws. Read when a call responds. */
  @Volatile var respond: suspend () -> T
) : FetchRecorder<Unit>(), suspend () -> T {
  override suspend fun invoke(): T = record(Unit) { respond() }
}

/**
 * A keyed query fetch the test controls. Pass it as the fetch itself, `fetch = fetch`. Each call
 * records its key, waits while [hold] is in effect, then returns what [respond] gives for that key.
 */
class TestKeyedFetch<K, T>(
  /** What the next call returns or throws for its key. Read when a call responds. */
  @Volatile var respond: suspend (key: K) -> T
) : FetchRecorder<K>(), suspend (K) -> T {
  /** How many calls have started for [key]. */
  fun callCount(key: K): Int = calls.count { it == key }

  override suspend fun invoke(key: K): T = record(key) { respond(key) }
}
