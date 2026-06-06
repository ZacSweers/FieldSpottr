// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

application { mainClass = "dev.zacsweers.fieldspottr.generator.MainKt" }

tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
  implementation(project(":models"))
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.ktor.client)
  implementation(libs.ktor.client.engine.okhttp)
  testImplementation(kotlin("test"))
  testImplementation(libs.assertk)
}
