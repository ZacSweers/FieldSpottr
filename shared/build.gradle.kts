// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
  alias(libs.plugins.agp.kotlin.multiplatform)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.parcelize)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.aboutLicenses)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.bugsnag)
  alias(libs.plugins.kotlin.plugin.serialization)
  alias(libs.plugins.metro)
  id("fs.base")
  id("fs.android")
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  androidLibrary {
    namespace = "dev.zacsweers.fieldspottr.shared"

    compilerOptions {
      freeCompilerArgs.addAll(
        "-P",
        "plugin:org.jetbrains.kotlin.parcelize:additionalAnnotation=dev.zacsweers.fieldspottr.parcel.CommonParcelize",
      )
    }
  }
  jvm { mainRun { mainClass.set("dev.zacsweers.fieldspottr.MainKt") } }

  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      dependencies {
        // API for klib reasons
        api(libs.calf.ui)
        implementation(libs.compose.components.resources)
        implementation(project.dependencies.platform(libs.kotlin.bom))
        implementation(libs.circuit.foundation)
        implementation(libs.circuit.overlay)
        implementation(libs.circuitx.overlays)
        implementation(libs.circuitx.gestureNav)
        implementation(libs.okio)
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
        implementation(libs.kermit)
      }
    }
    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.assertk)
        implementation(libs.coroutines.test)
        implementation(libs.okio.fakefilesystem)
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
    jvmTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.assertk)
        implementation(libs.coroutines.test)
        implementation(libs.okio.fakefilesystem)
        implementation(libs.sqldelight.driver.jdbc)
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
