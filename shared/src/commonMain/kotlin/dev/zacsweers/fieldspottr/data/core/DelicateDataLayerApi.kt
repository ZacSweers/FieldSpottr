// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

/**
 * Marks the core's interfaces that only the core should implement, like [DataQuery] and
 * [Operation]. Their builders hold ordering and cancellation rules that a hand-written version can
 * break without failing to compile. Implement one only where those rules are not needed, like a
 * fake in a test.
 */
@RequiresOptIn @Retention(AnnotationRetention.BINARY) annotation class DelicateDataLayerApi
