// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

@Inject
@SingleIn(AppScope::class)
class FSPreferencesStore(appDirs: FSAppDirs) {
  private val dataStore: DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
      produceFile = { (appDirs.userConfig / "preferences.preferences_pb").toString().toPath() }
    )

  val defaultGroup: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_DEFAULT_GROUP] }

  suspend fun setDefaultGroup(group: String?) {
    dataStore.edit { prefs ->
      if (group == null) {
        prefs.remove(KEY_DEFAULT_GROUP)
      } else {
        prefs[KEY_DEFAULT_GROUP] = group
      }
    }
  }

  private companion object {
    val KEY_DEFAULT_GROUP = stringPreferencesKey("default_group")
  }
}
