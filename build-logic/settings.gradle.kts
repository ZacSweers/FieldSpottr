// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
