// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

/**
 * Never. The Android driver begins each transaction with `beginTransactionNonExclusive`, which runs
 * BEGIN IMMEDIATE and takes the write lock before the first read.
 */
internal actual fun Throwable.isWriteConflict(): Boolean = false
