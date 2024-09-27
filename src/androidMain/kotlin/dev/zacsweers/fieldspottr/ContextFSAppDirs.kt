// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.content.Context
import me.tatarka.inject.annotations.Inject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
