// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Tests how a request joins an existing command, or lines up behind it, when another caller changes
 * the key between the moment the request reads the key's tail and the moment it links itself on.
 * Every request a query makes goes through this one step, which guarantees that the handle the
 * request returns is ordered ahead of any invalidation queued after it.
 */
class RunOrJoinTest {

  @Test
  fun `a request that loses its key to a newer fetch joins that fetch`() = runTest {
    val actions = RealActionRunner(backgroundScope, onFailure = {})
    val ran = mutableListOf<String>()
    // The newest command on the key, which no request joins, like a query's invalidation
    actions.run(CHECK) { ran += "invalidate" }
    var newer: Operation? = null

    val request =
      actions.runOrJoin(
        FORCED,
        Forced,
        joins = { entry ->
          // Runs after this request read the key and before it links, so another caller's forced
          // fetch lands in between, like a second thread
          if (newer == null) {
            newer =
              actions.runOrJoin(FORCED, Forced, joins = { it.tag == Forced }) { ran += "newer" }
          }
          entry.tag == Forced
        },
      ) {
        ran += "request"
      }
    testScheduler.runCurrent()

    // Linking against the key it read would cancel the newer fetch someone already holds
    assertThat(request).isSameInstanceAs(newer)
    assertThat(ran).containsExactly("invalidate", "newer")
    request.assertSucceeded()
  }

  private data object Forced

  private companion object {
    val CHECK = Ordering.Keyed("query")
    val FORCED = Ordering.Keyed("query", OnPrevious.Cancel)
  }
}
