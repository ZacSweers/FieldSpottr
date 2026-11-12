// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.Lint
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val compileSdkVersion = catalog.findVersion("androidCompileSdk").get().toString().toInt()

/**
 * The oldest Android API level the app runs on. The library sets it too, because lint checks each
 * module's code, tests included, against that module's own value, and a module without one counts
 * as API 1.
 */
val minSdkVersion = 29
val jvmTargetVersion = catalog.findVersion("jvmTarget").get().toString()

fun Lint.configureCommonLint() {
  lintConfig = rootProject.file("lint.xml")
  checkTestSources = true
  disable += "ComposableNaming"
}

pluginManager.withPlugin("com.android.application") {
  configure<ApplicationExtension> {
    compileSdk = compileSdkVersion
    defaultConfig {
      minSdk = minSdkVersion
      targetSdk = 36
    }
    lint { configureCommonLint() }
    compileOptions {
      sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
      targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }
    buildTypes { debug { matchingFallbacks += listOf("release") } }
  }
}

pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    configure<KotlinMultiplatformExtension> {
      androidLibrary {
        compileSdk = compileSdkVersion
        minSdk = minSdkVersion
        lint { configureCommonLint() }
        compilerOptions { configureCommonJvmCompilerOptions() }
      }
    }
  }
}

private fun KotlinMultiplatformExtension.androidLibrary(
  block: KotlinMultiplatformAndroidLibraryTarget.() -> Unit
) {
  configure<KotlinMultiplatformAndroidLibraryTarget>(block)
}
