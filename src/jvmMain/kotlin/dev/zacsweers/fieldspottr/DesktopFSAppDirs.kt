// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.harawata.appdirs.AppDirsFactory
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DesktopFSAppDirs(override val fs: FileSystem = FileSystem.SYSTEM) : FSAppDirs {

  private val appDirs = AppDirsFactory.getInstance()

  override val userConfig: Path by lazy {
    appDirs.getUserConfigDir(APP_NAME, APP_VERSION, APP_AUTHOR).toPath().also(fs::createDirectories)
  }

  override val userData: Path by lazy {
    appDirs.getUserDataDir(APP_NAME, APP_VERSION, APP_AUTHOR).toPath().also(fs::createDirectories)
  }

  override val userCache: Path by lazy {
    appDirs.getUserCacheDir(APP_NAME, APP_VERSION, APP_AUTHOR).toPath().also(fs::createDirectories)
  }

  private companion object {
    const val APP_NAME = "FieldSpottr"
    const val APP_VERSION = "1.0.0"
    const val APP_AUTHOR = "zacsweers"
  }
}
