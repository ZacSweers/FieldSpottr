// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.data.NSFileManagerFSAppDirs

class IosSharedPlatformFSComponent : SharedPlatformFSComponent {
  override fun provideFSAppDirs(): FSAppDirs {
    return NSFileManagerFSAppDirs()
  }
}
