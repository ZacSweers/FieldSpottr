// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import okio.FileSystem
import okio.Path

/** Common interface for access to different directories on the filesystem. */
interface FSAppDirs {
  val fs: FileSystem
  val userConfig: Path
  val userData: Path
  val userCache: Path
}
