// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import androidx.compose.runtime.Composable

/** Platform-specific back press handler. */
@Composable expect fun PlatformBackHandler(onBack: () -> Unit)
