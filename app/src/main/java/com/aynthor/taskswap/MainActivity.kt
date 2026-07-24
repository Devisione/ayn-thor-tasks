package com.aynthor.taskswap

import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aynthor.taskswap.adb.AdbConnectionManager
import com.aynthor.taskswap.input.GestureSettings
import com.aynthor.taskswap.task.DisplaySwapper
import com.aynthor.taskswap.ui.AppTextField
import com.aynthor.taskswap.ui.BannerType
import com.aynthor.taskswap.ui.GestureSettingsPanel
import com.aynthor.taskswap.ui.SetupGuide
import com.aynthor.taskswap.ui.SetupStatus
import com.aynthor.taskswap.ui.StatusBanner
import com.aynthor.taskswap.ui.ThorDisplaySwapperTheme
import com.aynthor.taskswap.util.AccidentalHomeGuard
import com.aynthor.taskswap.util.DeveloperSettingsChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbConnectionManager.init(applicationContext)

        setContent {
            ThorDisplaySwapperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val displaySwapper = remember { DisplaySwapper(context) }

    var serviceRunning by remember { mutableStateOf(false) }
    var displayApps by remember { mutableStateOf(mapOf<Int, String>()) }
    var displayIds by remember { mutableStateOf(listOf<Int>()) }
    var adbState by remember { mutableStateOf(AdbConnectionManager.State.DISCONNECTED) }
    var batteryOptimized by remember { mutableStateOf(true) }
    var wirelessDebuggingReady by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var bannerType by remember { mutableStateOf(BannerType.INFO) }
    var lastSwapMessage by remember { mutableStateOf("") }
    var lastKeyDebug by remember { mutableStateOf("") }
    var adbPaired by remember { mutableStateOf(AdbConnectionManager.isPaired()) }
    var isPairing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }

    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectionPort by remember { mutableStateOf(AdbConnectionManager.savedConnectionPort()?.toString() ?: "") }

    var gesturesEnabled by remember { mutableStateOf(GestureSettings.isEnabled(context)) }
    var accidentalHomeGuard by remember { mutableStateOf(AccidentalHomeGuard.isEnabled(context)) }
    var accidentalHomeGuardBusy by remember { mutableStateOf(false) }
    var gestureActions by remember {
        mutableStateOf(
            GestureSettings.Slot.entries.associateWith { GestureSettings.getAction(context, it) }
        )
    }

    fun showStatus(message: String, type: BannerType) {
        statusMessage = message
        bannerType = type
    }

    val setupStatus = SetupStatus(
        wirelessDebuggingReady = wirelessDebuggingReady,
        adbPaired = adbPaired,
        adbConnected = adbState == AdbConnectionManager.State.CONNECTED,
        accessibilityEnabled = serviceRunning,
        batteryOptimizationDisabled = !batteryOptimized
    )

    val canPair = pairingPort.toIntOrNull() != null && pairingCode.trim().length >= 6
    val canConnect = connectionPort.toIntOrNull() != null

    LaunchedEffect(Unit) {
        AdbConnectionManager.state.collectLatest { adbState = it }
    }

    // After debug reinstall / process restart: restore identity already ran in init();
    // reconnect with the saved port so the user does not re-enter ADB setup.
    LaunchedEffect(Unit) {
        if (!AdbConnectionManager.isPaired()) return@LaunchedEffect
        val port = AdbConnectionManager.savedConnectionPort() ?: return@LaunchedEffect
        if (AdbConnectionManager.state.value == AdbConnectionManager.State.CONNECTED) return@LaunchedEffect
        connectionPort = port.toString()
        isConnecting = true
        val result = AdbConnectionManager.reconnect()
        isConnecting = false
        if (result.isSuccess) {
            adbPaired = true
            showStatus("ADB подключён (восстановлено)", BannerType.SUCCESS)
        }
    }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            serviceRunning = TaskSwapService.isRunning
            displayIds = TaskSwapService.availableDisplayIds.ifEmpty {
                context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .displays.map { it.displayId }
            }
            lastSwapMessage = TaskSwapService.lastSwapMessage
            lastKeyDebug = TaskSwapService.lastKeyDebug
            adbPaired = AdbConnectionManager.isPaired()
            wirelessDebuggingReady = DeveloperSettingsChecker.isDeveloperSetupReady(context)
            if (!accidentalHomeGuardBusy) {
                accidentalHomeGuard = AccidentalHomeGuard.isEnabled(context)
            }
            val pm = context.getSystemService(PowerManager::class.java)
            batteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)

            val adbConnected = AdbConnectionManager.state.value == AdbConnectionManager.State.CONNECTED
            if (serviceRunning && adbConnected && tick % 3 == 0) {
                val refreshed = displaySwapper.fetchDisplayApps(
                    fallback = TaskSwapService.displayApps.toMap(),
                    ignoredPackages = setOf(
                        "com.android.systemui",
                        "com.odin.gameassistant",
                        "com.odin.dualscreen.assistant"
                    )
                )
                if (refreshed.isNotEmpty()) {
                    TaskSwapService.displayApps.clear()
                    TaskSwapService.displayApps.putAll(refreshed)
                }
                displayApps = refreshed.ifEmpty { TaskSwapService.displayApps.toMap() }
            } else {
                displayApps = TaskSwapService.displayApps.toMap()
            }
            tick++
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Thor Display Swapper",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text("v2.0.2", style = MaterialTheme.typography.labelSmall)
        if (lastKeyDebug.isNotBlank()) {
            Text(
                "Последняя кнопка: $lastKeyDebug",
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (isPairing || isConnecting) {
            StatusBanner(
                message = if (isPairing) "Сопряжение ADB..." else "Подключение ADB...",
                type = BannerType.LOADING
            )
        } else if (statusMessage.isNotBlank()) {
            StatusBanner(message = statusMessage, type = bannerType)
        }

        if (!setupStatus.allComplete) {
            if (lastSwapMessage.isNotBlank()) {
                StatusBanner(
                    message = lastSwapMessage,
                    type = if (lastSwapMessage.startsWith("Поменяли") || lastSwapMessage.startsWith("Отправили") || lastSwapMessage.startsWith("Перенесли")) {
                        BannerType.SUCCESS
                    } else {
                        BannerType.ERROR
                    }
                )
            }

            SetupGuide(
                status = setupStatus,
                pairingPort = pairingPort,
                onPairingPortChange = { pairingPort = it.filter { ch -> ch.isDigit() } },
                pairingCode = pairingCode,
                onPairingCodeChange = { pairingCode = it.filter { ch -> ch.isDigit() }.take(6) },
                connectionPort = connectionPort,
                onConnectionPortChange = { connectionPort = it.filter { ch -> ch.isDigit() } },
                isPairing = isPairing,
                isConnecting = isConnecting,
                canPair = canPair,
                canConnect = canConnect,
                onOpenSettingsResult = { error ->
                    if (error != null) {
                        showStatus("Не удалось открыть настройки: $error", BannerType.ERROR)
                    }
                },
                onPair = {
                    scope.launch {
                        val port = pairingPort.toIntOrNull()
                        val code = pairingCode.trim()
                        if (port == null || code.length < 6) {
                            showStatus("Введите порт сопряжения и 6-значный код", BannerType.ERROR)
                            return@launch
                        }
                        isPairing = true
                        val result = AdbConnectionManager.pair(port, code)
                        isPairing = false
                        if (result.isSuccess) {
                            adbPaired = true
                            showStatus("Сопряжено! Теперь введите порт подключения (шаг 3).", BannerType.SUCCESS)
                        } else {
                            val err = result.exceptionOrNull()
                            showStatus(
                                "Ошибка сопряжения: ${err?.message ?: "неизвестная ошибка"}\n\n" +
                                    "Проверьте: split-screen, диалог сопряжения открыт, порт и код верные.",
                                BannerType.ERROR
                            )
                        }
                    }
                },
                onConnect = {
                    scope.launch {
                        val port = connectionPort.toIntOrNull()
                        if (port == null) {
                            showStatus("Введите порт подключения (цифры после : на главном экране)", BannerType.ERROR)
                            return@launch
                        }
                        isConnecting = true
                        val result = AdbConnectionManager.connect(port)
                        isConnecting = false
                        if (result.isSuccess) {
                            showStatus("ADB подключён!", BannerType.SUCCESS)
                        } else {
                            val err = result.exceptionOrNull()
                            showStatus(
                                "Ошибка подключения: ${err?.message ?: "неизвестная ошибка"}\n\n" +
                                    "Порт подключения — с главного экрана «Беспроводная отладка», не из диалога сопряжения.",
                                BannerType.ERROR
                            )
                        }
                    }
                }
            )
        } else {
            ReadyBanner(gesturesEnabled = gesturesEnabled)
            GestureSettingsPanel(
                enabled = gesturesEnabled,
                onEnabledChange = { enabled ->
                    gesturesEnabled = enabled
                    GestureSettings.setEnabled(context, enabled)
                },
                accidentalHomeGuard = accidentalHomeGuard,
                accidentalHomeGuardBusy = accidentalHomeGuardBusy,
                onAccidentalHomeGuardChange = { enabled ->
                    scope.launch {
                        accidentalHomeGuardBusy = true
                        accidentalHomeGuard = enabled
                        val result = AccidentalHomeGuard.setEnabled(context, enabled)
                        accidentalHomeGuard = AccidentalHomeGuard.isEnabled(context)
                        accidentalHomeGuardBusy = false
                        if (result.isSuccess) {
                            showStatus(
                                if (enabled) {
                                    "Защита от случайного Home включена"
                                } else {
                                    "Защита выключена — Home с первого нажатия"
                                },
                                BannerType.SUCCESS
                            )
                        } else {
                            showStatus(
                                "Не удалось изменить настройку: ${result.exceptionOrNull()?.message}. Нужен ADB.",
                                BannerType.ERROR
                            )
                        }
                    }
                },
                actions = gestureActions,
                onActionChange = { slot, action ->
                    gestureActions = gestureActions + (slot to action)
                    GestureSettings.setAction(context, slot, action)
                }
            )
            StatusCard(
                serviceRunning = serviceRunning,
                adbState = adbState,
                batteryOptimized = batteryOptimized
            )

            if (adbState != AdbConnectionManager.State.CONNECTED) {
                ReconnectSection(
                    connectionPort = connectionPort,
                    onConnectionPortChange = { connectionPort = it.filter { ch -> ch.isDigit() } },
                    isConnecting = isConnecting,
                    onConnect = {
                        scope.launch {
                            val port = connectionPort.toIntOrNull()
                            if (port == null) {
                                showStatus("Введите порт подключения", BannerType.ERROR)
                                return@launch
                            }
                            isConnecting = true
                            val result = AdbConnectionManager.connect(port)
                            isConnecting = false
                            if (result.isSuccess) {
                                showStatus("Подключено", BannerType.SUCCESS)
                            } else {
                                showStatus(
                                    "Ошибка: ${result.exceptionOrNull()?.message}",
                                    BannerType.ERROR
                                )
                            }
                        }
                    }
                )
            }

            HorizontalDivider()

            Text("Экраны", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            displayIds.forEach { id ->
                val app = displayApps[id] ?: "—"
                Text("Экран $id → $app")
            }

            Button(
                onClick = {
                    scope.launch {
                        val result = displaySwapper.performSwapOrSend(
                            displayApps = displayApps,
                            displayIds = displayIds,
                            ignoredPackages = setOf(
                                "com.android.systemui",
                                "com.odin.gameassistant",
                                "com.odin.dualscreen.assistant"
                            )
                        )
                        showStatus(
                            result.message,
                            if (result.success) BannerType.SUCCESS else BannerType.ERROR
                        )
                    }
                },
                enabled = serviceRunning && displayApps.isNotEmpty() &&
                    adbState == AdbConnectionManager.State.CONNECTED && !isConnecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Поменять экраны вручную")
            }

            if (lastSwapMessage.isNotBlank()) {
                Text("Последний обмен: $lastSwapMessage", style = MaterialTheme.typography.bodySmall)
            }

        }
    }
}

@Composable
private fun ReadyBanner(gesturesEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Всё настроено",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (gesturesEnabled) {
                    "Жесты включены — настройте действия кнопок ниже."
                } else {
                    "Жесты выключены — кнопки работают как в системе."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ReconnectSection(
    connectionPort: String,
    onConnectionPortChange: (String) -> Unit,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "ADB отключён",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "После перезагрузки включите беспроводную отладку и введите новый порт подключения.",
                style = MaterialTheme.typography.bodySmall
            )
            AppTextField(
                value = connectionPort,
                onValueChange = onConnectionPortChange,
                label = "Порт подключения",
                keyboardType = KeyboardType.Number
            )
            Button(
                onClick = onConnect,
                enabled = !isConnecting && connectionPort.toIntOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnecting) "Подключение..." else "Переподключить")
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceRunning: Boolean,
    adbState: AdbConnectionManager.State,
    batteryOptimized: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StatusRow("Спец. возможности", serviceRunning)
            StatusRow("ADB", adbState == AdbConnectionManager.State.CONNECTED)
            StatusRow("Батарея", !batteryOptimized)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            if (ok) "OK" else "нет",
            fontWeight = FontWeight.Bold,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
