// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.zacsweers.fieldspottr.PermitState.FieldState
import dev.zacsweers.fieldspottr.PermitState.FieldState.Companion.padFreeSlots
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.theme.FSTheme

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val FieldSpottrApp = (application as FieldSpottrApplication).fsGraph.app
    setContent { FieldSpottrApp(onRootPop = ::finish) }
  }
}

@Preview
@Composable
private fun GridPreview() {
  val areas = Areas.default
  FSTheme {
    Surface {
      PermitGrid(
        selectedGroup = areas.entries[0].fieldGroups[0].name,
        areas = areas,
        permits =
          PermitState(
            fields =
              mapOf(
                areas.entries[0].fieldGroups[0].fields[0] to
                  listOf(
                      FieldState.Reserved(
                        start = 7,
                        end = 11,
                        timeRange = "12—4",
                        title = "Title",
                        org = "Org",
                        status = "Approved",
                        isBlocked = false,
                        isOverlap = false,
                      )
                    )
                    .padFreeSlots("Field")
              )
          ),
      )
    }
  }
}
