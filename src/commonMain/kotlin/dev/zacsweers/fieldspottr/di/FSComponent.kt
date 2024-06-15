package dev.zacsweers.fieldspottr.di

import androidx.compose.runtime.Immutable
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.presenter.presenterOf
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.Home
import dev.zacsweers.fieldspottr.HomePresenter
import dev.zacsweers.fieldspottr.HomeScreen
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.data.PermitRepository

interface SharedPlatformFSComponent {
  fun provideFSAppDirs(): FSAppDirs
}

@Immutable
class FSComponent(private val shared: SharedPlatformFSComponent): SharedPlatformFSComponent by shared {
  fun providePermitRepository(): PermitRepository =
    PermitRepository(SqlDriverFactory(), provideFSAppDirs())

  fun provideCircuit(): Circuit {
    return Circuit.Builder()
      .addPresenter<HomeScreen, HomeScreen.State> { _, _, _ ->
        presenterOf { HomePresenter(providePermitRepository()) }
      }
      .addUi<HomeScreen, HomeScreen.State> { state, modifier -> Home(state, modifier) }
      .build()
  }
}
