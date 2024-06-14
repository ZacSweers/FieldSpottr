// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import dev.zacsweers.fieldspottr.util.touch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Common interface for access to different directories on the filesystem. */
interface FSAppDirs {
  val fs: FileSystem
  val userConfig: Path
  val userData: Path
  val userCache: Path
}

fun FSAppDirs.delete(path: Path) = fs.delete(path)

fun FSAppDirs.touch(path: Path) = fs.touch(path)

class FakeFSAppDirs(override val fs: FileSystem) : FSAppDirs {
  override val userConfig: Path = "/userConfig".toPath().also(fs::createDirectories)
  override val userData: Path = "/userData".toPath().also(fs::createDirectories)
  override val userCache: Path = "/userCache".toPath().also(fs::createDirectories)
}
