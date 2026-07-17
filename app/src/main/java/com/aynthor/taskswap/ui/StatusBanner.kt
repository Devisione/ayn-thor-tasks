package com.aynthor.taskswap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class BannerType { INFO, SUCCESS, ERROR, LOADING }

@Composable
fun StatusBanner(
    message: String,
    type: BannerType,
    modifier: Modifier = Modifier
) {
    if (message.isBlank() && type != BannerType.LOADING) return

    val (container, textColor) = when (type) {
        BannerType.INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        BannerType.SUCCESS -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        BannerType.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerType.LOADING -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (type == BannerType.LOADING) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                text = if (type == BannerType.LOADING && message.isBlank()) "Выполняется..." else message,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (type == BannerType.ERROR) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
