// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.metro.Qualifier

/**
 * Qualifies the dispatcher for blocking I/O, like database and file calls. Stores and repositories
 * take it from the graph instead of using `Dispatchers.IO` directly, so the choice lives in one
 * place and tests can pass their own.
 */
@Qualifier annotation class IoDispatcher
