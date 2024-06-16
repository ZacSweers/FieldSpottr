// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import android.content.Context
import dev.zacsweers.fieldspottr.AndroidSqlDriverFactory
import dev.zacsweers.fieldspottr.ContextFSAppDirs

class AndroidSharedPlatformFSComponent(private val appContext: Context) :
  SharedPlatformFSComponent {
  override fun provideFSAppDirs() = ContextFSAppDirs(appContext)

  override fun provideSqlDriverFactory() = AndroidSqlDriverFactory(appContext)
}
