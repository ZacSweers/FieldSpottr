// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.NativeSqlDriverFactory
import dev.zacsweers.fieldspottr.data.NSFileManagerFSAppDirs

class IosSharedPlatformFSComponent : SharedPlatformFSComponent {
  override fun provideFSAppDirs() = NSFileManagerFSAppDirs()

  override fun provideSqlDriverFactory() = NativeSqlDriverFactory()
}
