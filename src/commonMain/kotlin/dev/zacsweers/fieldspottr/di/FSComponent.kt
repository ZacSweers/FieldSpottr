// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import androidx.compose.runtime.Immutable
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.presenter.presenterOf
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.Home
import dev.zacsweers.fieldspottr.HomePresenter
import dev.zacsweers.fieldspottr.HomeScreen
import dev.zacsweers.fieldspottr.PermitDetails
import dev.zacsweers.fieldspottr.PermitDetailsPresenter
import dev.zacsweers.fieldspottr.PermitDetailsScreen
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.data.PermitRepository

interface SharedPlatformFSComponent {
  fun provideFSAppDirs(): FSAppDirs

  fun provideSqlDriverFactory(): SqlDriverFactory
}

@Immutable
class FSComponent(private val shared: SharedPlatformFSComponent) :
  SharedPlatformFSComponent by shared {
  fun providePermitRepository(): PermitRepository =
    PermitRepository(provideSqlDriverFactory(), provideFSAppDirs())

  fun provideCircuit(): Circuit {
    return Circuit.Builder()
      .addPresenter<HomeScreen, HomeScreen.State> { _, _, _ ->
        presenterOf { HomePresenter(providePermitRepository()) }
      }
      .addUi<HomeScreen, HomeScreen.State> { state, modifier -> Home(state, modifier) }
      .addPresenter<PermitDetailsScreen, PermitDetailsScreen.State> { screen, navigator, _ ->
        presenterOf { PermitDetailsPresenter(screen, providePermitRepository(), navigator) }
      }
      .addUi<PermitDetailsScreen, PermitDetailsScreen.State> { state, modifier ->
        PermitDetails(state, modifier)
      }
      .build()
  }
}
