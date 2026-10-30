// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.impl.RealDataActions
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * How a request joins or lines up on its key when another caller changes the key between the
 * request's read and its link. A query's requests go through this one step, which keeps a handle it
 * returns ahead of any invalidation queued after it.
 */
class RunOrJoinTest {

  @Test
  fun `a request that loses its key to a newer fetch joins that fetch`() = runTest {
    val actions = RealDataActions(backgroundScope, onFailure = {})
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
