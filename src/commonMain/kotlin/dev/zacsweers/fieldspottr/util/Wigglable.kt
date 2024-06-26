package dev.zacsweers.fieldspottr.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun <T> Wigglable(
  target: T,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val currentTarget by rememberUpdatedState(target)
  var triggerWiggle by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    snapshotFlow {
      currentTarget
    }.distinctUntilChanged()
      .collect {
        triggerWiggle = true
      }
  }

  val angle by
    animateFloatAsState(
      targetValue = if (triggerWiggle) 10f else 0f,
      animationSpec = tween(durationMillis = 200),
      label = "Wiggle",
    )

  val scale by
    animateFloatAsState(
      targetValue = if (triggerWiggle) 1.2f else 1f,
      animationSpec = tween(durationMillis = 200),
      label = "Wiggle",
    )

  LaunchedEffect(angle) {
    if (angle == 10f) {
      triggerWiggle = false
    }
  }

  Box(modifier = modifier.graphicsLayer(rotationZ = angle).scale(scale), content = content)
}
