plugins {
  alias(libs.plugins.agp.application)
  alias(libs.plugins.compose)
}

val appId = "dev.zacsweers.fieldspottr"

val fsVersionCode = providers.gradleProperty("fs_versioncode").map(String::toLong).get()
val fsVersionName = providers.gradleProperty("fs_versionname").get()

val isReleasing = providers.environmentVariable("RELEASING").map(String::toBoolean).orElse(false)

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
}

dependencies {
  implementation(project(":shared"))
  coreLibraryDesugaring(libs.desugarJdkLibs)
  lintChecks(libs.lints.compose)
}
