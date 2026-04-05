// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.ui.Ui
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.FieldSpottrApp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.SYSTEM

interface FSGraph {
  val fsApp: FieldSpottrApp

  @Provides @SingleIn(AppScope::class) fun provideFileSystem(): FileSystem = FileSystem.SYSTEM

  @Provides
  @SingleIn(AppScope::class)
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideLogger(): Logger =
    if (BuildConfig.IS_RELEASE) {
      object : Logger(loggerConfigInit()) {
        // No op in release
      }
    } else {
      Logger.Companion
    }

  @Provides
  @SingleIn(AppScope::class)
  fun provideCircuit(
    uiFactories: Set<Ui.Factory>,
    presenterFactories: Set<Presenter.Factory>,
  ): Circuit {
    return Circuit.Builder()
      .addUiFactories(uiFactories)
      .addPresenterFactories(presenterFactories)
      .build()
  }
}
