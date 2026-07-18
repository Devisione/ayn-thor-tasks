package com.aynthor.taskswap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aynthor.taskswap.input.GestureSettings

@Composable
fun GestureSettingsPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    accidentalHomeGuard: Boolean,
    onAccidentalHomeGuardChange: (Boolean) -> Unit,
    accidentalHomeGuardBusy: Boolean = false,
    actions: Map<GestureSettings.Slot, GestureSettings.CustomAction>,
    onActionChange: (GestureSettings.Slot, GestureSettings.CustomAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Работа приложения",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Жесты двойного и долгого нажатия",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "Защита от случайного Home",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Как в Thor Settings: при включении Home нужно нажать дважды.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = accidentalHomeGuard,
                    onCheckedChange = onAccidentalHomeGuardChange,
                    enabled = !accidentalHomeGuardBusy
                )
            }

            if (enabled) {
                ButtonActionSection(
                    title = "Назад",
                    rows = listOf(
                        "Двойное нажатие" to GestureSettings.Slot.BACK_DOUBLE,
                        "Долгое нажатие" to GestureSettings.Slot.BACK_LONG
                    ),
                    actions = actions,
                    onActionChange = onActionChange
                )
                ButtonActionSection(
                    title = "Home",
                    rows = listOf(
                        "Долгое нажатие" to GestureSettings.Slot.HOME_LONG
                    ),
                    actions = actions,
                    onActionChange = onActionChange
                )
                ButtonActionSection(
                    title = "AYN",
                    rows = listOf(
                        "Двойное нажатие" to GestureSettings.Slot.AYN_DOUBLE,
                        "Долгое нажатие" to GestureSettings.Slot.AYN_LONG
                    ),
                    actions = actions,
                    onActionChange = onActionChange
                )
                Text(
                    "Короткое нажатие остаётся системным (Назад / Home / AYN).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ButtonActionSection(
    title: String,
    rows: List<Pair<String, GestureSettings.Slot>>,
    actions: Map<GestureSettings.Slot, GestureSettings.CustomAction>,
    onActionChange: (GestureSettings.Slot, GestureSettings.CustomAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        rows.forEach { (label, slot) ->
            GestureActionRow(
                label = label,
                selected = actions[slot] ?: GestureSettings.defaultAction(slot),
                onSelected = { onActionChange(slot, it) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureActionRow(
    label: String,
    selected: GestureSettings.CustomAction,
    onSelected: (GestureSettings.CustomAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(0.6f)
        ) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                GestureSettings.CustomAction.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
