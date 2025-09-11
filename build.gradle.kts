// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.spotless.LineEnding
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

buildscript { dependencies { classpath(platform(libs.kotlin.plugins.bom)) } }

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.agp.application)
  alias(libs.plugins.kotlin.plugin.parcelize)
  alias(libs.plugins.spotless)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.compose.hotReload)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.aboutLicenses)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.bugsnag)
  alias(libs.plugins.kotlin.plugin.serialization)
  alias(libs.plugins.metro)
}

val ktfmtVersion = libs.versions.ktfmt.get()

spotless {
  lineEndings = LineEnding.PLATFORM_NATIVE

  format("misc") {
    target("*.md", ".gitignore")
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlin {
    target("src/**/*.kt")
    ktfmt(ktfmtVersion).googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
    targetExclude("**/spotless.kt")
  }
  kotlinGradle {
    target("*.kts")
    ktfmt(ktfmtVersion).googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(
      rootProject.file("spotless/spotless.kt"),
      "(import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
    )
  }
  // Apply license formatting separately for kotlin files so we can prevent it from overwriting
  // copied files
  format("license") {
    licenseHeaderFile(rootProject.file("spotless/spotless.kt"), "(package|@file:)")
    target("src/**/*.kt")
    targetExclude("**/TextFieldDropdown.kt")
  }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  androidTarget {
    compilerOptions {
      freeCompilerArgs.addAll(
        "-P",
        "plugin:org.jetbrains.kotlin.parcelize:additionalAnnotation=dev.zacsweers.fieldspottr.parcel.CommonParcelize",
      )
      freeCompilerArgs.addAll(
        "-Xjsr305=strict",
        // Potentially useful for static analysis tools or annotation processors.
        "-Xemit-jvm-type-annotations",
        // Enable new jvm-default behavior
        // https://blog.jetbrains.com/kotlin/2020/07/kotlin-1-4-m3-generating-default-methods-in-interfaces/
        "-Xjvm-default=all",
        // https://kotlinlang.org/docs/whatsnew1520.html#support-for-jspecify-nullness-annotations
        "-Xjspecify-annotations=strict",
        // Match JVM assertion behavior:
        // https://publicobject.com/2019/11/18/kotlins-assert-is-not-like-javas-assert/
        "-Xassertions=jvm",
        "-Xtype-enhancement-improvements-strict-mode",
      )
    }
  }
  jvm {
    mainRun { mainClass.set("dev.zacsweers.fieldspottr.MainKt") }
    compilerOptions {
      freeCompilerArgs.addAll(
        "-Xjsr305=strict",
        // Potentially useful for static analysis tools or annotation processors.
        "-Xemit-jvm-type-annotations",
        // Enable new jvm-default behavior
        // https://blog.jetbrains.com/kotlin/2020/07/kotlin-1-4-m3-generating-default-methods-in-interfaces/
        "-Xjvm-default=all",
        // https://kotlinlang.org/docs/whatsnew1520.html#support-for-jspecify-nullness-annotations
        "-Xjspecify-annotations=strict",
        // Match JVM assertion behavior:
        // https://publicobject.com/2019/11/18/kotlins-assert-is-not-like-javas-assert/
        "-Xassertions=jvm",
        "-Xtype-enhancement-improvements-strict-mode",
      )
    }
  }
  jvmToolchain(libs.versions.jvmTarget.get().toInt())

  iosX64()
  iosArm64()
  iosSimulatorArm64()

  compilerOptions {
    progressiveMode = true
    optIn.addAll(
      "androidx.compose.material3.ExperimentalMaterial3Api",
      "androidx.compose.foundation.ExperimentalFoundationApi",
    )
    freeCompilerArgs.addAll("-Xexpect-actual-classes")
  }

  sourceSets {
    commonMain {
      dependencies {
        // API for klib reasons
        api(libs.calf.ui)
        implementation(compose.components.resources)
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.circuit.foundation)
        implementation(libs.circuit.overlay)
        implementation(libs.circuitx.overlays)
        implementation(libs.circuitx.gestureNav)
        implementation(libs.okio)
        implementation(libs.metro.runtime)
        implementation(libs.kotlinx.immutable)
        implementation(libs.kotlinx.datetime)
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.coroutines)
        implementation(libs.sqldelight.async)
        implementation(libs.sqldelight.coroutines)
        implementation(libs.compose.material.material3)
        implementation(libs.compose.material.icons)
        implementation(libs.ktor.client)
        implementation(libs.aboutLicenses)
      }
    }
    androidMain {
      dependencies {
        implementation(libs.androidx.appCompat)
        implementation(libs.androidx.compose.integration.activity)
        implementation(libs.androidx.splash)
        implementation(libs.bugsnag.android)
        implementation(libs.compose.ui.tooling)
        implementation(libs.compose.ui.tooling.preview)
        implementation(libs.coroutines.android)
        implementation(libs.ktor.client.engine.okhttp)
        implementation(libs.sqldelight.driver.android)
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.maps.compose)
      }
    }
    jvmMain {
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.appDirs)
        implementation(libs.ktor.client.engine.okhttp)
        implementation(libs.slf4jNop)
        implementation(libs.sqldelight.driver.jdbc)
        implementation(project.dependencies.platform(libs.kotlin.bom))
      }
    }
    nativeMain { dependencies { implementation(libs.sqldelight.driver.native) } }
    iosMain { dependencies { implementation(libs.ktor.client.engine.darwin) } }
  }

  targets
    .filterIsInstance<KotlinNativeTarget>()
    .filter { it.konanTarget.family == Family.IOS }
    .forEach {
      it.binaries.framework {
        baseName = "FieldSpottrKt"
        export(libs.calf.ui)
      }
    }
}

// Teach Gradle that full guava replaces listenablefuture.
// This bypasses the dependency resolution that transitively bumps listenablefuture to a 9999.0
// version that is empty.
dependencies.modules {
  module("com.google.guava:listenablefuture") { replacedBy("com.google.guava:guava") }
}

val appId = "dev.zacsweers.fieldspottr"

val fsVersionCode = providers.gradleProperty("fs_versioncode").map(String::toLong).get()
val fsVersionName = providers.gradleProperty("fs_versionname").get()

val isReleasing = providers.environmentVariable("RELEASING").map(String::toBoolean).orElse(false)

buildConfig {
  packageName("dev.zacsweers.fieldspottr")
  useKotlinOutput {
    // internal isn't visible to iOS sources
    internalVisibility = false
  }
  buildConfigField("String", "FS_VERSION_NAME", "\"$fsVersionName - $fsVersionCode\"")
  buildConfigField("Long", "VERSION_CODE", fsVersionCode)
  buildConfigField("Boolean", "IS_RELEASE", isReleasing)
  buildConfigField(
    "String?",
    "BUGSNAG_NOTIFIER_KEY",
    providers.gradleProperty("fs_bugsnag_key").orNull,
  )
  buildConfigField("String?", "MAPS_API_KEY", providers.gradleProperty("fs_maps_api_key").orNull)
  generateAtSync = true
}

android {
  namespace = appId
  compileSdk = 36

  defaultConfig {
    versionCode = fsVersionCode.toInt()
    versionName = fsVersionName
    minSdk = 29
    targetSdk = 36
    // Here because Bugsnag requires it in manifests for some reason
    manifestPlaceholders["bugsnagApiKey"] = providers.gradleProperty("fs_bugsnag_key").getOrElse("")
    manifestPlaceholders["mapsApiKey"] = providers.gradleProperty("fs_maps_api_key").getOrElse("")
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = libs.versions.jvmTarget.map(JavaVersion::toVersion).get()
    targetCompatibility = libs.versions.jvmTarget.map(JavaVersion::toVersion).get()
  }

  lint {
    lintConfig = file("lint.xml")
    checkTestSources = true
    disable += "ComposableNaming"
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
      matchingFallbacks += listOf("release")
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
  dependencies {
    add("coreLibraryDesugaring", libs.desugarJdkLibs)
    add("lintChecks", libs.lints.compose)
  }
}

bugsnag { enabled = !providers.gradleProperty("fs_bugsnag_key").orNull.isNullOrBlank() }

compose {
  resources {
    packageOfResClass = "dev.zacsweers.fieldspottr"
    generateResClass = always
  }
  desktop {
    application {
      mainClass = "dev.zacsweers.fieldspottr.MainKt"
      nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = appId
        packageVersion = fsVersionName
      }
    }
  }
}

composeCompiler {
  includeSourceInformation = true
  stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-compiler-config.conf"))
}

sqldelight {
  databases {
    create("FSDatabase") {
      packageName.set("dev.zacsweers.fieldspottr")
      generateAsync.set(true)
    }
  }
}

tasks
  .withType<JavaExec>()
  .named { it == "run" }
  .configureEach {
    dependsOn("jvmJar")
    classpath("jvmJar")
  }
