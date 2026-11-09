// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** App directories under [root], which a test can point at a temp directory on a real [fs]. */
class FakeFSAppDirs(override val fs: FileSystem, root: Path = "/".toPath()) : FSAppDirs {
  override val userConfig: Path = (root / "userConfig").also(fs::createDirectories)
  override val userData: Path = (root / "userData").also(fs::createDirectories)
  override val userCache: Path = (root / "userCache").also(fs::createDirectories)
}
