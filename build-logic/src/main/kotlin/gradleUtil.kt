// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

fun KotlinJvmCompilerOptions.configureCommonJvmCompilerOptions() {
  jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
  freeCompilerArgs.addAll(
    "-Xjsr305=strict",
    // Potentially useful for static analysis tools or annotation processors.
    "-Xemit-jvm-type-annotations",
    // https://kotlinlang.org/docs/whatsnew1520.html#support-for-jspecify-nullness-annotations
    "-Xjspecify-annotations=strict",
    // Match JVM assertion behavior:
    // https://publicobject.com/2019/11/18/kotlins-assert-is-not-like-javas-assert/
    "-Xassertions=jvm",
    "-Xtype-enhancement-improvements-strict-mode",
  )
}
