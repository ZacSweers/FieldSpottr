// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.FSAppDirs
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
private val NSFileManager.cacheDir: String
  get() =
    URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
      )
      ?.path!!

@OptIn(ExperimentalForeignApi::class)
private val NSFileManager.filesDir: String
  get() =
    URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
      )
      ?.path!!

class NSFileManagerFSAppDirs : FSAppDirs {
  override val fs: FileSystem = FileSystem.SYSTEM

  private val fileManager = NSFileManager.defaultManager

  override val userConfig: Path by lazy {
    (fileManager.filesDir.toPath() / "config").also(fs::createDirectories)
  }

  override val userData: Path by lazy {
    (fileManager.filesDir.toPath() / "data").also(fs::createDirectories)
  }

  override val userCache: Path by lazy { fileManager.cacheDir.toPath() }
}
