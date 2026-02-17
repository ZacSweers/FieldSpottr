// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import com.diffplug.spotless.LineEnding

apply(plugin = "com.diffplug.spotless")

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktfmtVersion = catalog.findVersion("ktfmt").get().requiredVersion

val isRootProject = this == rootProject

if (isRootProject) {
  configure<SpotlessExtensionPredeclare> {
    kotlin { ktfmt(ktfmtVersion).googleStyle() }
    kotlinGradle { ktfmt(ktfmtVersion).googleStyle() }
  }
}

configure<SpotlessExtension> {
  if (isRootProject) {
    predeclareDeps()
  }
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
