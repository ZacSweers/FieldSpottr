// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.retained.CircuitRetainedSettings
import com.slack.circuit.retained.ExperimentalCircuitRetainedApi
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.serialization.CircuitSerializerRegistration
import com.slack.circuit.serialization.SerializableCircuitSaver
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.FieldSpottrApp
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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

  @Provides @SingleIn(AppScope::class) fun provideHttpClient(): HttpClient = HttpClient()

  @Provides @IoDispatcher fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

  @Provides
  suspend fun provideDatabase(
    sqlDriverFactory: SqlDriverFactory,
    @IoDispatcher io: CoroutineDispatcher,
  ): FSDatabase =
    withContext(io) {
      sqlDriverFactory.create(FSDatabase.Schema, "fs.db").createFSDatabase()
    }

  @Provides
  @SingleIn(AppScope::class)
  @ForScope(AppScope::class)
  fun provideAppCoroutineScope(logger: Logger): CoroutineScope {
    val log = logger.withTag("AppScope")
    return CoroutineScope(
      SupervisorJob() +
        Dispatchers.Default +
        CoroutineExceptionHandler { _, t -> log.e(t) { "Uncaught error" } }
    )
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideDataQueryFactory(
    @ForScope(AppScope::class) appScope: CoroutineScope,
    logger: Logger,
  ): DataQuery.Factory {
    val log = logger.withTag("DataQuery")
    return DataQuery.Factory(appScope, onFailure = { log.e(it) { "Fetch failed" } })
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
  @OptIn(ExperimentalCircuitRetainedApi::class)
  fun provideCircuit(
    uiFactories: Set<Ui.Factory>,
    presenterFactories: Set<Presenter.Factory>,
    serializerRegistrations: Set<CircuitSerializerRegistration>,
  ): Circuit {
    CircuitRetainedSettings.useFirstParty = true
    return Circuit.Builder()
      .addUiFactories(uiFactories)
      .addPresenterFactories(presenterFactories)
      .setCircuitSaver(SerializableCircuitSaver(serializerRegistrations))
      .build()
  }
}
