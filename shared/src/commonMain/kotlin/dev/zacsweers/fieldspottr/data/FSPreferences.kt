// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

@Inject
@SingleIn(AppScope::class)
class FSPreferencesStore(appDirs: FSAppDirs, private val json: Json) {
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

  /** Saved custom Find a Field windows, in the order they were created. */
  val customTimeWindows: Flow<List<TimeWindow>> =
    dataStore.data.map { prefs -> decodeWindows(prefs[KEY_CUSTOM_TIME_WINDOWS]) }

  /** Adds [window], or replaces the saved window with the same id in place. */
  suspend fun saveCustomTimeWindow(window: TimeWindow) {
    editCustomTimeWindows { windows ->
      val index = windows.indexOfFirst { it.id == window.id }
      if (index == -1) {
        windows + window
      } else {
        windows.toMutableList().apply { set(index, window) }
      }
    }
  }

  suspend fun deleteCustomTimeWindow(id: String) {
    editCustomTimeWindows { windows -> windows.filterNot { it.id == id } }
  }

  private suspend fun editCustomTimeWindows(transform: (List<TimeWindow>) -> List<TimeWindow>) {
    dataStore.edit { prefs ->
      val updated = transform(decodeWindows(prefs[KEY_CUSTOM_TIME_WINDOWS]))
      prefs[KEY_CUSTOM_TIME_WINDOWS] =
        json.encodeToString(
          updated.map { StoredTimeWindow(it.id, it.name, it.startMinutes, it.endMinutes) }
        )
    }
  }

  /** Unreadable or invalid stored windows are dropped so a bad value cannot break Find a Field. */
  private fun decodeWindows(value: String?): List<TimeWindow> {
    if (value == null) return emptyList()
    val stored =
      try {
        json.decodeFromString<List<StoredTimeWindow>>(value)
      } catch (e: IllegalArgumentException) {
        Logger.w(e) { "Dropping unreadable custom time windows" }
        return emptyList()
      }
    return stored.mapNotNull { window ->
      try {
        TimeWindow(window.id, window.name, window.startMinutes, window.endMinutes)
      } catch (e: IllegalArgumentException) {
        Logger.w(e) { "Dropping invalid custom time window ${window.id}" }
        null
      }
    }
  }

  /** Persisted form of a custom [TimeWindow]. */
  @Serializable
  private data class StoredTimeWindow(
    val id: String,
    val name: String,
    val startMinutes: Int,
    val endMinutes: Int,
  )

  private companion object {
    val KEY_DEFAULT_GROUP = stringPreferencesKey("default_group")
    val KEY_CUSTOM_TIME_WINDOWS = stringPreferencesKey("custom_time_windows")
  }
}
