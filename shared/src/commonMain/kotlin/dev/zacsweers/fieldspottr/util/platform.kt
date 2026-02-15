// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

enum class Platform {
  Android,
  Jvm,
  Native,
}

expect val CurrentPlatform: Platform
