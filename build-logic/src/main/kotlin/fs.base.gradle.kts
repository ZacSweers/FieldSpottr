// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
  configure<KotlinMultiplatformExtension> {
    jvmToolchain(catalog.findVersion("jvmTarget").get().toString().toInt())

    compilerOptions {
      progressiveMode = true
      optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.foundation.ExperimentalFoundationApi",
        "kotlin.time.ExperimentalTime",
      )
      freeCompilerArgs.addAll("-Xexpect-actual-classes")
    }

    targets.withType<KotlinJvmTarget>().configureEach {
      compilerOptions { configureCommonJvmCompilerOptions() }
    }
  }
}
