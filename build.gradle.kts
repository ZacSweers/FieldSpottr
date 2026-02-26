// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
buildscript { dependencies { classpath(platform(libs.kotlin.plugins.bom)) } }

plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.agp.application) apply false
  alias(libs.plugins.agp.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.plugin.parcelize) apply false
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.kotlin.plugin.compose) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.aboutLicenses) apply false
  alias(libs.plugins.buildConfig) apply false
  alias(libs.plugins.bugsnag) apply false
  alias(libs.plugins.kotlin.plugin.serialization) apply false
  alias(libs.plugins.metro) apply false
  id("fs.spotless") apply false
}

allprojects { apply(plugin = "fs.spotless") }
