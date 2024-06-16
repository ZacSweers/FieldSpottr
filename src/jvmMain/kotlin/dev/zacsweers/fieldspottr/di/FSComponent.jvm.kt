// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import dev.zacsweers.fieldspottr.DesktopFSAppDirs
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.JvmSqlDriverFactory
import dev.zacsweers.fieldspottr.SqlDriverFactory

class JvmSharedPlatformFSComponent : SharedPlatformFSComponent {
  private val appDirs = DesktopFSAppDirs()
  override fun provideFSAppDirs() = appDirs
  override fun provideSqlDriverFactory(): SqlDriverFactory {
    return JvmSqlDriverFactory(appDirs)
  }
}
