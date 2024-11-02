// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation.Url
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mohamedrejeb.calf.ui.progress.AdaptiveCircularProgressIndicator
import com.slack.circuit.runtime.screen.StaticScreen
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

@CommonParcelize data object AboutScreen : StaticScreen

@OptIn(ExperimentalResourceApi::class)
@Composable
fun About(modifier: Modifier = Modifier) {
  val libs by
    produceState<Libs?>(null) {
      value =
        withContext(Dispatchers.IO) {
          val bytes = Res.readBytes("files/aboutlibraries.json")
          Libs.Builder().withJson(bytes.decodeToString()).build()
        }
    }
  if (libs == null) {
    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Center) {
      AdaptiveCircularProgressIndicator()
    }
  } else {
    LibrariesContainer(
      libraries = libs,
      modifier = modifier.fillMaxSize(),
      showAuthor = true,
      showVersion = false,
      header = { item(key = "header") { Header(Modifier.fillMaxWidth()) } },
    )
  }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun Header(modifier: Modifier = Modifier) {
  Column(
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = CenterHorizontally,
    modifier = modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
  ) {
    Icon(
      modifier = Modifier.size(96.dp),
      painter = painterResource(Res.drawable.fs_icon),
      contentDescription = "FieldSpottr icon",
      tint = Color.Unspecified,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text("Field Spottr", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
    Text(
      "v${BuildConfig.VERSION_NAME}",
      style = MaterialTheme.typography.labelSmall,
      color = LocalContentColor.current.copy(alpha = 0.5f),
    )
    Spacer(modifier = Modifier.height(8.dp))
    val text = buildAnnotatedString {
      append("An app for checking field permit status from ")
      withLink(Url("https://nycgovparks.org")) { append("nycgovparks.org") }
      pop()
      append(".")
      repeat(2) { appendLine() }
      append("By ")
      append(" ")
      withLink(Url("https://zacsweers.dev")) { append("Zac Sweers") }
      pop()
      append(" — ")
      withLink(Url("https://github.com/ZacSweers/FieldSpottr")) { append("Source code") }
      pop()
    }
    Text(
      text,
      modifier = Modifier.align(CenterHorizontally),
      style =
        MaterialTheme.typography.bodyMedium.copy(
          textAlign = TextAlign.Center,
          color = LocalContentColor.current,
        ),
    )
  }
}
