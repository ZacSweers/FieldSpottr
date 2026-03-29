// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import com.slack.circuit.sharedelements.SharedTransitionKey

private data class ReflowWordKey(
  val baseKey: String,
  val suffix: String?,
  val wordIndex: Int,
) : SharedTransitionKey

/**
 * A text composable that splits its content into individual words, each rendered as a separate
 * [Text] within a [FlowRow]. When [sharedElementKey] is non-null, each word gets its own shared
 * element bounds so during a shared element transition each word independently animates from its
 * source position/size to its destination position/size. This creates a "reflow" effect inspired by
 * [ReflowText](https://github.com/nickbutcher/plaid/blob/main/core/src/main/java/io/plaidapp/core/ui/transitions/ReflowText.java).
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReflowText(
  text: String,
  sharedElementKey: String?,
  modifier: Modifier = Modifier,
  sharedElementKeySuffix: String? = null,
  style: TextStyle = TextStyle.Default,
  fontWeight: FontWeight? = null,
  color: Color = Color.Unspecified,
  overflow: TextOverflow = TextOverflow.Clip,
) = SharedElementTransitionScope {
  // Merge standalone dashes with the following word so "Campus - Youth" becomes
  // ["Campus", "- Youth"] rather than ["Campus", "-", "Youth"]
  val words = buildList {
    val raw = text.split(" ")
    var i = 0
    while (i < raw.size) {
      if (raw[i] == "-" && i + 1 < raw.size) {
        add("- ${raw[i + 1]}")
        i += 2
      } else {
        add(raw[i])
        i++
      }
    }
  }
  FlowRow(modifier = modifier) {
    words.forEachIndexed { index, word ->
      val displayText = if (index < words.lastIndex) "$word " else word
      val wordModifier =
        if (sharedElementKey != null) {
          val wordKey = ReflowWordKey(sharedElementKey, sharedElementKeySuffix, index)
          Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(wordKey),
            animatedVisibilityScope = requireAnimatedScope(Navigation),
          )
        } else {
          Modifier
        }
      Text(
        text = displayText,
        modifier = wordModifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
        overflow = overflow,
        softWrap = false,
        maxLines = 1,
      )
    }
  }
}
