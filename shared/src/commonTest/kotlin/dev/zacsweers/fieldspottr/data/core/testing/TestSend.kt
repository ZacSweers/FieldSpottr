// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred

/**
 * An outbox send the test controls, with a revert that records too. Pass them as `send = send,
 * revert = send::revert`. Each attempt records its payload, waits while that payload is held, then
 * throws what [failure] returns or records the payload as sent. Every record is safe to write from
 * any thread.
 */
@OptIn(ExperimentalAtomicApi::class)
class TestSend<E> : suspend (E) -> Unit {
  // Compare-and-set only. Nothing observes these.
  private val attempted = AtomicReference(persistentListOf<E>())
  private val delivered = AtomicReference(persistentListOf<E>())
  private val undone = AtomicReference(persistentListOf<E>())
  // What held attempts wait on, by payload. Each hold opens a new gate, so a release reaches every
  // attempt that entered before it, even when the test holds the payload again at once.
  private val gates = AtomicReference(persistentMapOf<E, CompletableDeferred<Unit>>())

  /**
   * What an attempt throws, given its payload and its attempt number for that payload, starting at
   * 1. Null sends it. A transient error makes the outbox back off and retry, and a
   *    [PermanentFailure] fails the row and reverts it.
   */
  @Volatile var failure: (payload: E, attempt: Int) -> Throwable? = { _, _ -> null }

  /** Each attempt's payload, in the order the attempts started. */
  val attempts: List<E>
    get() = attempted.load()

  /** The payloads that sent, in order. */
  val sent: List<E>
    get() = delivered.load()

  /** The payloads reverted, in order. */
  val reverted: List<E>
    get() = undone.load()

  /** How many attempts have started for [payload]. */
  fun attempts(payload: E): Int = attempts.count { it == payload }

  /** Fails the first [times] attempts of each payload with a transient error, then sends. */
  fun failTransiently(times: Int = 1) {
    failure = { _, attempt ->
      if (attempt <= times) {
        IllegalStateException("Transient send failure on attempt $attempt")
      } else {
        null
      }
    }
  }

  /** Rejects every payload for good, which fails its row and reverts it. */
  fun failPermanently() {
    failure = { payload, _ -> PermanentFailure("Rejected $payload") }
  }

  /** Sends every payload from now on. */
  fun succeed() {
    failure = { _, _ -> null }
  }

  /** Makes attempts for [payload] wait before they send, until [release]. */
  fun hold(payload: E) {
    gates.update {
      if (payload in it) {
        it
      } else {
        it.put(payload, CompletableDeferred())
      }
    }
  }

  /** Lets waiting attempts for [payload] send, and stops holding it. */
  fun release(payload: E) {
    gates.fetchAndUpdate { it.remove(payload) }[payload]?.complete(Unit)
  }

  override suspend fun invoke(payload: E) {
    val attempt = attempted.updateAndFetch { it.adding(payload) }.count { it == payload }
    gates.load()[payload]?.await()
    failure(payload, attempt)?.let { throw it }
    delivered.update { it.adding(payload) }
  }

  suspend fun revert(payload: E) {
    undone.update { it.adding(payload) }
  }
}
