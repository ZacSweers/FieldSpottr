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
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.theme.FSTheme

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val component = (application as FieldSpottrApplication).fsComponent
    setContent { FieldSpottrApp(component, onRootPop = ::finish) }
  }
}

@Preview
@Composable
private fun GridPreview() {
  FSTheme {
    Surface {
      PermitGrid(
        selectedGroup = Area.entries[0].fieldGroups[0].name,
        permits =
          PermitState(
            fields =
              mapOf(
                Area.entries[0].fieldGroups[0].fields[0] to
                  listOf(
                      FieldState.Reserved(
                        start = 7,
                        end = 11,
                        timeRange = "12-4",
                        title = "Title",
                        org = "Org",
                        status = "Approved",
                        description = "Description",
                        isBlocked = false,
                        isOverlap = false,
                      )
                    )
                    .padFreeSlots()
              )
          ),
      )
    }
  }
}
