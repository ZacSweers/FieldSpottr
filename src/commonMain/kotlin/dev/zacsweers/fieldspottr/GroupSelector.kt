package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.zacsweers.fieldspottr.data.Area

@Composable
fun GroupSelector(
  selectedGroup: String,
  modifier: Modifier = Modifier,
  onGroupSelected: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    modifier = modifier,
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    OutlinedTextField(
      value = selectedGroup,
      modifier = Modifier.menuAnchor().wrapContentWidth(),
      textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      for (group in Area.groups.keys) {
        DropdownMenuItem(
          text = {
            Text(
              text = group,
              // TODO why do none of these work?
              style = LocalTextStyle.current.copy(textAlign = TextAlign.End),
              textAlign = TextAlign.End,
            )
          },
          onClick = {
            expanded = false
            onGroupSelected(group)
          },
        )
      }
    }
  }
}