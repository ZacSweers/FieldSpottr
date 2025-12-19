// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.presenterOf
import dev.zacsweers.fieldspottr.FieldSpottrApp
import dev.zacsweers.fieldspottr.About
import dev.zacsweers.fieldspottr.AboutScreen
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.Home
import dev.zacsweers.fieldspottr.HomePresenter
import dev.zacsweers.fieldspottr.HomeScreen
import dev.zacsweers.fieldspottr.LocationMap
import dev.zacsweers.fieldspottr.LocationMapPresenter
import dev.zacsweers.fieldspottr.LocationMapScreen
import dev.zacsweers.fieldspottr.PermitDetails
import dev.zacsweers.fieldspottr.PermitDetailsPresenter
import dev.zacsweers.fieldspottr.PermitDetailsScreen
import dev.zacsweers.fieldspottr.ScaffoldPresenter
import dev.zacsweers.fieldspottr.ScaffoldScreen
import dev.zacsweers.fieldspottr.ScaffoldScreenContent
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.SYSTEM

@SingleIn(AppScope::class)
interface FSGraph {
  val app: FieldSpottrApp

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
  fun provideCircuit(permitRepository: PermitRepository): Circuit {
    return Circuit.Builder()
      .addPresenter<HomeScreen, HomeScreen.State> { _, navigator, _ ->
        presenterOf { HomePresenter(permitRepository, navigator) }
      }
      .addUi<HomeScreen, HomeScreen.State> { state, modifier -> Home(state, modifier) }
      .addPresenter<PermitDetailsScreen, PermitDetailsScreen.State> { screen, _, _ ->
        presenterOf { PermitDetailsPresenter(screen, permitRepository) }
      }
      .addUi<PermitDetailsScreen, PermitDetailsScreen.State> { state, modifier ->
        PermitDetails(state, modifier)
      }
      .addPresenter<ScaffoldScreen, ScaffoldScreen.State> { screen, navigator, _ ->
        presenterOf { ScaffoldPresenter(screen, navigator) }
      }
      .addUi<ScaffoldScreen, ScaffoldScreen.State> { state, modifier ->
        ScaffoldScreenContent(state, modifier)
      }
      .addPresenter<LocationMapScreen, LocationMapScreen.State> { screen, _, _ ->
        presenterOf { LocationMapPresenter(screen) }
      }
      .addUi<LocationMapScreen, LocationMapScreen.State> { state, modifier ->
        LocationMap(state, modifier)
      }
      .addStaticUi<AboutScreen, CircuitUiState> { _, modifier -> About(modifier) }
      .build()
  }
}
