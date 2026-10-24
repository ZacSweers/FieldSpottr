// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

sealed interface Ordering {
  /** One at a time, in call order. The default for writes. */
  data object Serial : Ordering

  data class Keyed(val key: Any, val onPrevious: OnPrevious = OnPrevious.Wait) : Ordering

  @DelicateDataLayerApi data object Concurrent : Ordering
}

/** What a new command does to predecessors on its key that declared the same mode. */
enum class OnPrevious {
  /** Queue behind them. Serial per key, concurrent across keys. */
  Wait,
  /** Replace the ones that have not started. Wait for one that has. */
  Supersede,
  /** Cancel them, running or not. For refresh and search. */
  Cancel,
}
