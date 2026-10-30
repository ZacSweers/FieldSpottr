// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.impl.InMemoryOutboxStores
import dev.zacsweers.fieldspottr.data.core.impl.invoke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.serializer

/**
 * A [DataScope.Factory] for tests, built by [testDataScopeFactory], for code that builds its own
 * data scope over a scope its owner hands it. Every data scope it creates shares its clock, its
 * network state, its failure lists and its outbox rows, like the scopes of one graph's factory.
 */
@OptIn(DelicateDataLayerApi::class)
class TestDataScopeFactory
internal constructor(
  private val delegate: DataScope.Factory,
  // Added to every scope it creates, to run their work eagerly. Null leaves each scope's own.
  private val dispatcher: CoroutineDispatcher?,
  /** The clock every query and outbox from this factory reads. */
  val clock: TestClock,
  /** Whether outboxes from this factory drain. Set it to false to hold rows in their store. */
  val online: MutableStateFlow<Boolean>,
  /** What fetches threw. */
  val fetchFailures: TestFailures,
  /**
   * What commands threw, on runners from `newActions()`, inside outboxes, and in a query's commands
   * that failed without a fetch. A runner built with its own handler reports there instead.
   */
  val actionFailures: TestFailures,
  /** The outbox rows, by outbox name. Tests read them through [outboxStore]. */
  @PublishedApi internal val outboxStores: InMemoryOutboxStores,
) : DataScope.Factory {
  /** A data scope over [scope], which owns how long its work lives. */
  override fun create(scope: CoroutineScope): TestDataScope {
    val target =
      if (dispatcher == null) {
        scope
      } else {
        scope + dispatcher
      }
    return TestDataScope(delegate.create(target), this)
  }

  /** The rows of the outbox called [name], the same rows that outbox reads and writes. */
  inline fun <reified E> outboxStore(name: String): OutboxStore<E> =
    outboxStores.open(name, serializer<E>())

  /** Fails the test if any fetch or command failed. */
  fun assertNoFailures() {
    fetchFailures.assertNone()
    actionFailures.assertNone()
  }
}

/**
 * A [DataScope] for tests, built by [testDataScope] or by a [TestDataScopeFactory]. It records
 * failures, reads time from [clock] and keeps outbox rows in memory, where [outboxStore] reads
 * them. Each of these belongs to its [factory], so scopes from one factory share them.
 */
@OptIn(DelicateDataLayerApi::class)
class TestDataScope
internal constructor(private val delegate: DataScope, val factory: TestDataScopeFactory) :
  DataScope by delegate {
  /** The clock every query and outbox here reads. */
  val clock: TestClock
    get() = factory.clock

  /** Whether outboxes here drain. Set it to false to hold rows in their store. */
  val online: MutableStateFlow<Boolean>
    get() = factory.online

  /** What fetches threw. */
  val fetchFailures: TestFailures
    get() = factory.fetchFailures

  /**
   * What commands threw, on runners from [newActions] and inside outboxes. A runner built with its
   * own handler reports there instead.
   */
  val actionFailures: TestFailures
    get() = factory.actionFailures

  /** The rows of the outbox called [name], the same rows that outbox reads and writes. */
  inline fun <reified E> outboxStore(name: String): OutboxStore<E> = factory.outboxStore(name)

  /** Fails the test if any fetch or command failed. */
  fun assertNoFailures() {
    factory.assertNoFailures()
  }
}

/**
 * A [TestDataScopeFactory] on this test's scheduler, starting [online].
 *
 * By default each scope keeps its own dispatcher, so its work runs when the test body suspends or
 * calls `runCurrent()`, and a test awaits a handle, like with `awaitSucceeded()`, before it
 * asserts. That holds for work that finishes on a real dispatcher, like database or file calls on
 * `Dispatchers.IO` or an HTTP client engine, and for tests that step through interleavings.
 *
 * With [eager], work runs as soon as it is started. A fetch, command or outbox send that does not
 * suspend has settled by the time the call returns, so the test can assert right after it. Work
 * started from inside another coroutine, like a collector, runs once that coroutine suspends. Use
 * it only when every fake stays on the test scheduler, since eager dispatch resumes work on the
 * thread that finished it, so work that finishes on a real dispatcher runs beside the test body.
 * Eager dispatch can also hide an ordering bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.testDataScopeFactory(
  eager: Boolean = false,
  clock: TestClock = testClock(),
  online: Boolean = true,
): TestDataScopeFactory {
  val onlineState = MutableStateFlow(online)
  val fetchFailures = TestFailures()
  val actionFailures = TestFailures()
  val stores = InMemoryOutboxStores()
  val delegate =
    DataScope.Factory(
      onFailure = fetchFailures,
      onActionFailure = actionFailures,
      online = onlineState,
      stores = stores,
      clock = clock,
    )
  return TestDataScopeFactory(
    delegate,
    dispatcher =
      if (eager) {
        UnconfinedTestDispatcher(testScheduler)
      } else {
        null
      },
    clock,
    onlineState,
    fetchFailures,
    actionFailures,
    stores,
  )
}

/**
 * A [TestDataScope] over this test's `backgroundScope`, so its work ends with the test. The
 * parameters are those of [testDataScopeFactory].
 */
fun TestScope.testDataScope(
  eager: Boolean = false,
  clock: TestClock = testClock(),
  online: Boolean = true,
): TestDataScope = testDataScopeFactory(eager, clock, online).create(backgroundScope)
