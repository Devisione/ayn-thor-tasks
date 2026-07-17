package com.aynthor.taskswap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aynthor.taskswap.util.SettingsNavigator

data class SetupStatus(
    val wirelessDebuggingReady: Boolean,
    val adbPaired: Boolean,
    val adbConnected: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryOptimizationDisabled: Boolean
) {
    val allComplete: Boolean =
        wirelessDebuggingReady &&
            adbPaired &&
            adbConnected &&
            accessibilityEnabled &&
            batteryOptimizationDisabled
}

@Composable
fun SetupGuide(
    status: SetupStatus,
    pairingPort: String,
    onPairingPortChange: (String) -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    connectionPort: String,
    onConnectionPortChange: (String) -> Unit,
    onPair: () -> Unit,
    onConnect: () -> Unit,
    isPairing: Boolean,
    isConnecting: Boolean,
    canPair: Boolean,
    canConnect: Boolean,
    onOpenSettingsResult: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Настройка",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Выполните шаги по порядку. Зелёный пункт = шаг выполнен. " +
                "Когда всё готово, инструкция исчезнет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SetupStepCard(
            stepNumber = 1,
            title = "Включите беспроводную отладку",
            description = "1. Откройте «Для разработчиков»\n" +
                "2. Включите «Отладка по USB»\n" +
                "3. Включите «Беспроводная отладка»\n\n" +
                "Пункт станет зелёным, когда отладка по USB включена.",
            completed = status.wirelessDebuggingReady,
            primaryAction = "Открыть для разработчиков",
            onPrimaryAction = {
                onOpenSettingsResult(
                    SettingsNavigator.openDeveloperSettings(it)
                        .exceptionOrNull()?.message
                )
            },
            secondaryAction = "Беспроводная отладка",
            onSecondaryAction = {
                onOpenSettingsResult(
                    SettingsNavigator.openWirelessDebuggingSettings(it)
                        .exceptionOrNull()?.message
                )
            }
        )

        SetupStepCard(
            stepNumber = 2,
            title = "Сопряжение ADB",
            description = "Важно: это НЕ «сопряжение по Wi‑Fi» в обычных настройках сети!\n\n" +
                "1. Включите разделение экрана: слева Настройки, справа это приложение\n" +
                "2. Настройки → Беспроводная отладка → «Сопряжение по коду» (Pair with pairing code)\n" +
                "3. В диалоге видны ПОРТ и 6-значный КОД — введите их ниже\n" +
                "4. Не закрывайте диалог, пока не увидите «Сопряжено»\n\n" +
                "Порт сопряжения — из диалога. Это НЕ порт с главного экрана беспроводной отладки.",
            completed = status.adbPaired,
            extraContent = {
                AppTextField(
                    value = pairingPort,
                    onValueChange = onPairingPortChange,
                    label = "Порт сопряжения (из диалога)",
                    placeholder = "например 37891",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
                AppTextField(
                    value = pairingCode,
                    onValueChange = onPairingCodeChange,
                    label = "Код сопряжения (6 цифр)",
                    placeholder = "например 643102",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
                Text(
                    "Введено: порт=${pairingPort.ifBlank { "—" }}, код=${pairingCode.length}/6",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onPair,
                    enabled = canPair && !isPairing && !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isPairing) "Сопряжение..." else "Сопрячь")
                }
            }
        )

        SetupStepCard(
            stepNumber = 3,
            title = "Подключение ADB",
            description = "На главном экране «Беспроводная отладка» (не в диалоге!) " +
                "найдите строку IP:PORT — введите только цифры ПОСЛЕ двоеточия.\n\n" +
                "Пример: 192.168.1.5:41235 → введите 41235",
            completed = status.adbConnected,
            extraContent = {
                AppTextField(
                    value = connectionPort,
                    onValueChange = onConnectionPortChange,
                    label = "Порт подключения (с главного экрана)",
                    placeholder = "например 41235",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
                Text(
                    "Введено: ${connectionPort.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onConnect,
                    enabled = canConnect && !isConnecting && !isPairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isConnecting) "Подключение..." else "Подключить")
                }
            }
        )

        SetupStepCard(
            stepNumber = 4,
            title = "Служба специальных возможностей",
            description = "Найдите «Thor Display Swapper» в списке и включите переключатель. " +
                "Без этого долгое нажатие «Назад» не будет работать.",
            completed = status.accessibilityEnabled,
            primaryAction = "Открыть спец. возможности",
            onPrimaryAction = {
                onOpenSettingsResult(
                    SettingsNavigator.openAccessibilitySettings(it)
                        .exceptionOrNull()?.message
                )
            }
        )

        SetupStepCard(
            stepNumber = 5,
            title = "Оптимизация батареи",
            description = "Разрешите приложению работать в фоне без ограничений батареи.",
            completed = status.batteryOptimizationDisabled,
            primaryAction = "Отключить оптимизацию",
            onPrimaryAction = {
                onOpenSettingsResult(
                    SettingsNavigator.openBatteryOptimization(it)
                        .exceptionOrNull()?.message
                )
            }
        )
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    completed: Boolean,
    primaryAction: String? = null,
    onPrimaryAction: ((android.content.Context) -> Unit)? = null,
    secondaryAction: String? = null,
    onSecondaryAction: ((android.content.Context) -> Unit)? = null,
    extraContent: @Composable (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = if (completed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) {
                Color(0xFF2E7D32).copy(alpha = 0.10f)
            } else {
                Color.White
            }
        )
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StepIndicator(number = stepNumber, completed = completed, accent = accent)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (completed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            extraContent?.invoke()
            if (primaryAction != null && onPrimaryAction != null) {
                OutlinedButton(
                    onClick = { onPrimaryAction(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(primaryAction)
                }
            }
            if (secondaryAction != null && onSecondaryAction != null) {
                OutlinedButton(
                    onClick = { onSecondaryAction(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(secondaryAction)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(number: Int, completed: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (completed) Color(0xFF2E7D32) else accent.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (completed) "✓" else number.toString(),
            color = if (completed) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
