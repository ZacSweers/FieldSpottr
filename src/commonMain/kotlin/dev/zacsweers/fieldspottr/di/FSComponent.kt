// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import androidx.compose.runtime.Immutable
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.presenterOf
import dev.zacsweers.fieldspottr.About
import dev.zacsweers.fieldspottr.AboutScreen
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.Home
import dev.zacsweers.fieldspottr.HomePresenter
import dev.zacsweers.fieldspottr.HomeScreen
import dev.zacsweers.fieldspottr.PermitDetails
import dev.zacsweers.fieldspottr.PermitDetailsPresenter
import dev.zacsweers.fieldspottr.PermitDetailsScreen
import dev.zacsweers.fieldspottr.ScaffoldPresenter
import dev.zacsweers.fieldspottr.ScaffoldScreen
import dev.zacsweers.fieldspottr.ScaffoldScreenContent
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.data.PermitRepository

interface SharedPlatformFSComponent {
  fun provideFSAppDirs(): FSAppDirs

  fun provideSqlDriverFactory(): SqlDriverFactory
}

@Immutable
class FSComponent(private val shared: SharedPlatformFSComponent) :
  SharedPlatformFSComponent by shared {

  private val permitRepository: PermitRepository by lazy {
    PermitRepository(provideSqlDriverFactory(), provideFSAppDirs())
  }

  val circuit: Circuit by lazy {
    Circuit.Builder()
      .addPresenter<HomeScreen, HomeScreen.State> { _, navigator, _ ->
        presenterOf { HomePresenter(navigator, permitRepository) }
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
      .addStaticUi<AboutScreen, CircuitUiState> { _, modifier -> About(modifier) }
      .build()
  }
}
