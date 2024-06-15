// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.os.Bundle
import android.os.StrictMode
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dev.zacsweers.fieldspottr.di.AndroidSharedPlatformFSComponent
import dev.zacsweers.fieldspottr.di.FSComponent

class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    StrictMode.enableDefaults()

    val component = FSComponent(AndroidSharedPlatformFSComponent(applicationContext))
    setContent { FieldSpottrApp(component, onRootPop = ::finish) }
  }
}
