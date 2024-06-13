// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import okio.FileSystem

class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val appDirs = ContextFSAppDirs(this, FileSystem.SYSTEM)
    val permitRepository = PermitRepository(SqlDriverFactory(this), appDirs)
    setContent { FieldSpottrApp(permitRepository, onRootPop = ::finish) }
  }
}
