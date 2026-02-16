// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class ContextFSAppDirs(private val context: Context, override val fs: FileSystem) : FSAppDirs {

  override val userConfig: Path by lazy {
    (context.filesDir.toOkioPath() / "config").also(fs::createDirectories)
  }

  override val userData: Path by lazy {
    (context.filesDir.toOkioPath() / "data").also(fs::createDirectories)
  }

  override val userCache: Path by lazy { context.cacheDir.toOkioPath() }
}
