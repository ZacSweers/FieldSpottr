// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.content.Context
import okio.FileSystem
import okio.FileSystem.Companion
import okio.Path
import okio.Path.Companion.toOkioPath

class ContextFSAppDirs(private val context: Context, override val fs: FileSystem = FileSystem.SYSTEM) : FSAppDirs {

  override val userConfig: Path by lazy {
    (context.filesDir.toOkioPath() / "config").also(fs::createDirectories)
  }

  override val userData: Path by lazy {
    (context.filesDir.toOkioPath() / "data").also(fs::createDirectories)
  }

  override val userCache: Path by lazy { context.cacheDir.toOkioPath() }
}
