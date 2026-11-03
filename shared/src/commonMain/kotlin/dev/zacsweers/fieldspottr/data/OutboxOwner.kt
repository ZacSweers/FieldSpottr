// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

/**
 * A repository that owns an outbox. Each one is bound into the graph's set of owners, which a
 * background drain reads before it waits for the outboxes. Reading the set constructs the owners
 * not built yet, so their outboxes read their rows and send. The set is the one list of them, so a
 * new owner contributes itself and nothing else changes.
 */
interface OutboxOwner
