// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
dependencyResolutionManagement {
  repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    google()
    maven("https://oss.sonatype.org/content/repositories/snapshots") {
      name = "snapshots-maven-central"
      mavenContent { snapshotsOnly() }
    }
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots") {
      name = "snapshots-maven-central-s01"
      mavenContent { snapshotsOnly() }
    }
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

pluginManagement {
  includeBuild("build-logic")
  repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    google()
    maven("https://oss.sonatype.org/content/repositories/snapshots") {
      name = "snapshots-maven-central"
      mavenContent { snapshotsOnly() }
    }
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots") {
      name = "snapshots-maven-central-s01"
      mavenContent { snapshotsOnly() }
    }
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // Gradle's plugin portal proxies jcenter, which we don't want. To avoid this, we specify
    // exactly which dependencies to pull from here.
    val pluginPortalMirror = maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
    val pluginPortal = gradlePluginPortal()
    exclusiveContent {
      forRepositories(pluginPortalMirror, pluginPortal)
      filter {
        includeGroup("com.github.gmazzo.buildconfig")
        includeGroup("com.mikepenz.aboutlibraries.plugin")
        includeGroup("com.gradle")
        includeGroup("com.bugsnag.gradle")
        includeModule("com.gradle.develocity", "com.gradle.develocity.gradle.plugin")
        includeModule("org.gradle.kotlin.kotlin-dsl", "org.gradle.kotlin.kotlin-dsl.gradle.plugin")
        includeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
      }
    }
  }
}

plugins { id("com.gradle.develocity") version "4.4.2" }

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

include(":android")

include(":generator")

include(":models")

include(":shared")
