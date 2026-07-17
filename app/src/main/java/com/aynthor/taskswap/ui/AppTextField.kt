package com.aynthor.taskswap.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFF000000),
            unfocusedTextColor = Color(0xFF000000),
            disabledTextColor = Color(0xFF666666),
            cursorColor = Color(0xFF1565C0),
            focusedBorderColor = Color(0xFF1565C0),
            unfocusedBorderColor = Color(0xFF79747E),
            focusedLabelColor = Color(0xFF1565C0),
            unfocusedLabelColor = Color(0xFF49454F),
            focusedPlaceholderColor = Color(0xFF888888),
            unfocusedPlaceholderColor = Color(0xFF888888),
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
        )
    )
}
