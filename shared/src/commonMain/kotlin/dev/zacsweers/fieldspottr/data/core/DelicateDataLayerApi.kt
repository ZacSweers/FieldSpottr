// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

/**
 * Marks what stays safe only when used as the core intends. On an interface, like [DataQuery] or
 * [Operation], it means only the core should implement it: the core's builders hold ordering and
 * cancellation rules that a hand-written version can break without failing to compile, so implement
 * one only where those rules are not needed, like a fake in a test. On [Ordering.Concurrent] it
 * means the command gives up the runner's ordering.
 */
@RequiresOptIn @Retention(AnnotationRetention.BINARY) annotation class DelicateDataLayerApi
