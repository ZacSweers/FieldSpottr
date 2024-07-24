package dev.zacsweers.fieldspottr.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.LocalContentColor
import io.github.alexzhirkevich.cupertino.CupertinoSurface
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveSurface
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveWidget
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme

@Composable
fun AdaptiveClickableSurface(
  clickableEnabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  color: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  shadowElevation: Dp = 0.dp,
  content: @Composable () -> Unit,
) {
  if (!clickableEnabled) {
    AdaptiveSurface(modifier, shape, color, contentColor, shadowElevation, content)
  } else {
    AdaptiveWidget(
      material = {
        Surface(
          onClick = onClick,
          modifier = modifier,
          shape = shape,
          color = color.takeOrElse { MaterialTheme.colorScheme.surface },
          contentColor = contentColor.takeOrElse { MaterialTheme.colorScheme.onSurface },
          shadowElevation = shadowElevation,
          content = content,
        )
      },
      cupertino = {
        CupertinoSurface(
          onClick = onClick,
          modifier = modifier,
          shape = shape,
          color = color.takeOrElse { CupertinoTheme.colorScheme.systemBackground },
          contentColor = contentColor.takeOrElse { LocalContentColor.current },
          //        shadowElevation = shadowElevation,
          content = content,
        )
      },
    )
  }
}