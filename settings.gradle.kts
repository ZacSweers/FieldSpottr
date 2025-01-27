// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
    mavenLocal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

pluginManagement {
  repositories {
    mavenCentral()
    google()
    mavenLocal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // Gradle's plugin portal proxies jcenter, which we don't want. To avoid this, we specify
    // exactly which dependencies to pull from here.
    exclusiveContent {
      forRepository(::gradlePluginPortal)
      filter {
        includeGroup("com.github.gmazzo.buildconfig")
        includeGroup("com.mikepenz.aboutlibraries.plugin")
        includeGroup("com.gradle")
        includeGroup("com.bugsnag.android.gradle")
        includeModule("com.gradle.develocity", "com.gradle.develocity.gradle.plugin")
        includeModule("com.diffplug.spotless", "com.diffplug.spotless.gradle.plugin")
        includeModule("org.gradle.kotlin.kotlin-dsl", "org.gradle.kotlin.kotlin-dsl.gradle.plugin")
        includeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
      }
    }
  }
}

plugins { id("com.gradle.develocity") version "3.19" }

develocity {
  buildScan {
    termsOfUseUrl = "https://gradle.com/terms-of-service"
    termsOfUseAgree = "yes"

    tag(if (System.getenv("CI").isNullOrBlank()) "Local" else "CI")

    obfuscation {
      username { "Redacted" }
      hostname { "Redacted" }
      ipAddresses { addresses -> addresses.map { "0.0.0.0" } }
    }
  }
}

rootProject.name = "field-spottr-root"
