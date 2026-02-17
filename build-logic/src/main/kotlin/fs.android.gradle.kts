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
      minSdk = 29
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
