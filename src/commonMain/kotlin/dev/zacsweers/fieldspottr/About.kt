// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.UrlAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalTextApi::class)
@Composable
fun About(modifier: Modifier = Modifier) {
  Column(
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = CenterHorizontally,
    modifier = modifier.padding(32.dp),
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
      pushUrlAnnotation(UrlAnnotation("https://nycgovparks.org"))
      withStyle(
        style =
          SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
          )
      ) {
        append("nycgovparks.org")
      }
      pop()
      append(".")
      repeat(2) { appendLine() }
      append("By ")
      append(" ")
      pushUrlAnnotation(UrlAnnotation("https://zacsweers.dev"))
      withStyle(
        style =
          SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
          )
      ) {
        append("Zac Sweers")
      }
      pop()
      appendLine()
      appendLine()
      pushUrlAnnotation(UrlAnnotation("https://github.com/ZacSweers/FieldSpottr"))
      withStyle(
        style =
          SpanStyle(
            color = LocalContentColor.current.copy(alpha = 0.5f),
            textDecoration = TextDecoration.Underline,
          )
      ) {
        append("Source code + OSS Licenses")
      }
      pop()
    }
    // TODO move to the cleaner Compose APIs once they're in CM.
    val uriHandler = LocalUriHandler.current
    ClickableText(
      text,
      modifier = Modifier.align(CenterHorizontally),
      style =
        MaterialTheme.typography.bodyMedium.copy(
          textAlign = TextAlign.Center,
          color = LocalContentColor.current,
        ),
    ) { offset ->
      text.getUrlAnnotations(start = offset, end = offset).firstOrNull()?.let { annotation ->
        uriHandler.openUri(annotation.item.url)
      }
    }
  }
}