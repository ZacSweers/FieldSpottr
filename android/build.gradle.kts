// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.agp.application)
  alias(libs.plugins.compose)
  id("fs.android")
}

val appId = "dev.zacsweers.fieldspottr"

val fsVersionCode = providers.gradleProperty("fs_versioncode").map(String::toLong).get()
val fsVersionName = providers.gradleProperty("fs_versionname").get()

val isReleasing = providers.environmentVariable("RELEASING").map(String::toBoolean).orElse(false)

android {
  namespace = appId

  defaultConfig {
    versionCode = fsVersionCode.toInt()
    versionName = fsVersionName
    // Here because Bugsnag requires it in manifests for some reason
    manifestPlaceholders["bugsnagApiKey"] = providers.gradleProperty("fs_bugsnag_key").getOrElse("")
    manifestPlaceholders["mapsApiKey"] = providers.gradleProperty("fs_maps_api_key").getOrElse("")
  }

  signingConfigs {
    if (rootProject.file("release/app-release.jks").exists()) {
      create("release") {
        storeFile = rootProject.file("release/app-release.jks")
        storePassword = providers.gradleProperty("fs_release_keystore_pwd").orNull
        keyAlias = "zacsweers-fieldspottr"
        keyPassword = providers.gradleProperty("fs_release_key_pwd").orNull
      }
    }
  }

  buildTypes {
    maybeCreate("debug").apply {
      versionNameSuffix = "-dev"
      applicationIdSuffix = ".debug"
    }
    maybeCreate("release").apply {
      isDebuggable = false
      isMinifyEnabled = true
      matchingFallbacks += listOf("release")
      proguardFiles("proguardrules.pro")
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
    }
  }

  bundle {}

  compileOptions { isCoreLibraryDesugaringEnabled = true }
}

dependencies {
  implementation(project(":shared"))
  coreLibraryDesugaring(libs.desugarJdkLibs)
  lintChecks(libs.lints.compose)
}
