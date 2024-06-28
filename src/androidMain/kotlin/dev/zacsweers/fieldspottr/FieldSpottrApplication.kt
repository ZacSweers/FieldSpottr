// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.app.Application
import android.os.StrictMode
import com.bugsnag.android.Bugsnag
import dev.zacsweers.fieldspottr.di.AndroidSharedPlatformFSComponent
import dev.zacsweers.fieldspottr.di.FSComponent

class FieldSpottrApplication : Application() {

  internal lateinit var fsComponent: FSComponent

  override fun onCreate() {
    super.onCreate()
    BuildConfig.BUGSNAG_NOTIFIER_KEY?.takeIf { BuildConfig.IS_RELEASE }
      ?.let {
        if (!Bugsnag.isStarted()) {
          Bugsnag.start(this, it)
        }
      }
    fsComponent = FSComponent(AndroidSharedPlatformFSComponent(this))
    if (!BuildConfig.IS_RELEASE) {
      StrictMode.enableDefaults()
    }
  }
}
