// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.kotlin.plugin.serialization)
  id("fs.base")
}

kotlin {
  jvm()

  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      dependencies {
        api(libs.compose.runtime)
        api(libs.kotlinx.immutable)
        api(libs.kotlinx.serialization.core)
      }
    }
  }
}
