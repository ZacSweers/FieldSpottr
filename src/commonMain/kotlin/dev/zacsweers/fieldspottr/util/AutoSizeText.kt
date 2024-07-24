package dev.zacsweers.fieldspottr.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight

@Composable
fun AutoMeasureText(
  modifier: Modifier = Modifier,
  minSize: TextUnit,
  maxSize: TextUnit,
  // Note: only TextAlign.Center is actually supported because shrug
  textAlign: TextAlign,
  content: @Composable (fontSize: TextUnit) -> Unit,
) {
  SubcomposeLayout(modifier) { constraints ->
    fun measureAt(size: TextUnit): Placeable {
      val measurable = subcompose(size) { Box { content(size) } }.single()
      return measurable.measure(Constraints()) // Measure with infinite space
    }

    var currentSize = maxSize
    var placeable = measureAt(currentSize)
    while (placeable.width > constraints.maxWidth) {
      val newSize = (currentSize * 0.9f)
      if (newSize < minSize) {
        // It won't fit, measure at the minimum size and give up
        placeable = measureAt(minSize)
        break
      }
      currentSize = newSize
      placeable = measureAt(newSize)
    }

    layout(constraints.maxWidth, constraints.constrainHeight(placeable.height)) {
      val x =
        when (textAlign) {
          TextAlign.Center -> {
            (constraints.maxWidth - placeable.width) / 2
          }
          else -> {
            0
          }
        }
      placeable.place(x, 0)
    }
  }
}
