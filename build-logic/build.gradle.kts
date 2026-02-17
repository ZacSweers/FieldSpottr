// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { `kotlin-dsl` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt())) } }

dependencies {
  compileOnly(libs.agp.gradlePlugin)
  compileOnly(libs.kotlin.gradlePlugin)
  implementation(libs.spotless.gradlePlugin)
}
