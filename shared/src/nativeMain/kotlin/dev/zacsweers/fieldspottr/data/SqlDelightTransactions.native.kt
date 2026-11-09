// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

/**
 * Never. The native driver runs every write and every transaction on its one write connection, so
 * no other connection in the process writes between a transaction's read and its write.
 */
internal actual fun Throwable.isWriteConflict(): Boolean = false
