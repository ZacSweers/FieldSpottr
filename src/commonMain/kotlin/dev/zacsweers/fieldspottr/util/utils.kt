// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

operator fun <E> List<E>.component6(): E {
  return this[5]
}

operator fun <E> List<E>.component7(): E {
  return this[6]
}

/** Analogous to `Objects.hash` on the JVM. */
fun hashOf(vararg inputs: Any): Int {
  var hash = 0
  for (v in inputs) {
    hash += v.hashCode()
    hash *= 31
  }
  return hash
}

/**
 * Iterates this [Iterable] with a given [parallelism], passing the value for each emission to the
 * given [action].
 */
suspend inline fun <A> Iterable<A>.parallelForEach(
  parallelism: Int,
  start: CoroutineStart = CoroutineStart.DEFAULT,
  crossinline action: suspend (A) -> Unit,
): Unit = coroutineScope {
  val semaphore = Semaphore(parallelism)
  forEach {
    semaphore.acquire()
    launch(start = start) {
      try {
        action(it)
      } finally {
        semaphore.release()
      }
    }
  }
}
