/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("MatchingDeclarationName")

package dev.zacsweers.fieldspottr.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A styled drop-down menu to select between [allValues], with the given [currentValue]. */
// Adapted from
// https://github.com/alexvanyo/composelife/blob/e8c965128af31c30d03d0c53325452be07bcf595/ui-app/src/jbMain/kotlin/com/alexvanyo/composelife/ui/app/component/TextFieldDropdown.kt#L69
@Composable
fun <T> TextFieldDropdown(
  label: String,
  currentValue: T,
  allValues: List<T>,
  setValue: (T) -> Unit,
  displayName: (T) -> String,
  modifier: Modifier = Modifier,
) {
  var isShowingDropdownMenu by remember { mutableStateOf(false) }

  Box(modifier = modifier.padding(top = 8.dp).clickable { isShowingDropdownMenu = true }) {
    val interactionSource = remember { MutableInteractionSource() }

    OutlinedTextFieldDefaults.DecorationBox(
      value = displayName(currentValue),
      innerTextField = {
        Text(
          displayName(currentValue),
          modifier = Modifier.fillMaxWidth(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      enabled = true,
      singleLine = true,
      visualTransformation = VisualTransformation.None,
      interactionSource = interactionSource,
      label = { Text(text = label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isShowingDropdownMenu) },
    ) {
      Box(propagateMinConstraints = true) {
        OutlinedTextFieldDefaults.ContainerBox(
          enabled = true,
          isError = false,
          interactionSource = interactionSource,
          colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )

        DropdownMenu(
          expanded = isShowingDropdownMenu,
          onDismissRequest = { isShowingDropdownMenu = false },
        ) {
          allValues.forEach { value ->
            DropdownMenuItem(
              text = {
                val isSelected = value == currentValue
                Text(displayName(value), fontWeight = if (isSelected) FontWeight.Black else null)
              },
              onClick = {
                setValue(value)
                isShowingDropdownMenu = false
              },
            )
          }
        }
      }
    }
  }
}
