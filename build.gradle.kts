// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.diffplug.spotless.LineEnding
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

buildscript { dependencies { classpath(platform(libs.kotlin.plugins.bom)) } }

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.agp.application)
  alias(libs.plugins.kotlin.plugin.parcelize)
  alias(libs.plugins.spotless)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.sqldelight)
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
    targetExclude(
      "**/circuit/backstack/**/*.kt",
      "**/HorizontalPagerIndicator.kt",
      "**/FilterList.kt",
      "**/Remove.kt",
      "**/Pets.kt",
      "**/SystemUiController.kt",
    )
  }
}

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

  listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "fieldspottr" }
  }

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
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.circuit.foundation)
        implementation(libs.circuit.overlay)
        implementation(libs.circuitx.overlays)
        implementation(libs.ktor.client)
        implementation(libs.ktor.client.engine.cio)
        implementation(libs.okio)
        implementation(libs.kotlinx.immutable)
        implementation(libs.kotlinx.datetime)
        implementation(libs.coroutines)
        implementation(libs.sqldelight.async)
        implementation(libs.compose.material.material3)
        implementation(libs.compose.material.icons)
      }
    }
    androidMain {
      dependencies {
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.coroutines.android)
        implementation(libs.sqldelight.driver.android)
        implementation(libs.androidx.appCompat)
        implementation(libs.androidx.compose.integration.activity)
      }
    }
    jvmMain {
      dependencies {
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.sqldelight.driver.jdbc)
        implementation(compose.desktop.currentOs)
        implementation(libs.appDirs)
      }
    }
    nativeMain { dependencies { implementation(libs.sqldelight.driver.native) } }
  }
}

// Teach Gradle that full guava replaces listenablefuture.
// This bypasses the dependency resolution that transitively bumps listenablefuture to a 9999.0
// version that is empty.
dependencies.modules {
  module("com.google.guava:listenablefuture") { replacedBy("com.google.guava:guava") }
}

android {
  namespace = "dev.zacsweers.fieldspottr"
  compileSdk = 34

  defaultConfig {
    minSdk = 29
    targetSdk = 34
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = libs.versions.jvmTarget.map(JavaVersion::toVersion).get()
    targetCompatibility = libs.versions.jvmTarget.map(JavaVersion::toVersion).get()
  }

  lint { checkTestSources = true }
  buildTypes {
    maybeCreate("debug").apply { matchingFallbacks += listOf("release") }
    maybeCreate("release").apply {
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
      proguardFiles("proguardrules.pro")
    }
  }
  compileOptions { isCoreLibraryDesugaringEnabled = true }
  dependencies {
    add("coreLibraryDesugaring", libs.desugarJdkLibs)
    add("lintChecks", libs.lints.compose)
  }
}

compose {
  desktop {
    application {
      mainClass = "dev.zacsweers.fieldspottr.MainKt"
      nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "dev.zacsweers.fieldspottr"
        packageVersion = "1.0.0"
      }
    }
  }
}

composeCompiler {
  enableStrongSkippingMode = true
  includeSourceInformation = true
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
