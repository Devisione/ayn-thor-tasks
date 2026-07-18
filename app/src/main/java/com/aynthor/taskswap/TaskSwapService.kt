package com.aynthor.taskswap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.aynthor.taskswap.adb.AdbConnectionManager
import com.aynthor.taskswap.adb.ShellExecutor
import com.aynthor.taskswap.input.ButtonGestures
import com.aynthor.taskswap.input.GestureSettings
import com.aynthor.taskswap.input.ThorKeyMapper
import com.aynthor.taskswap.task.DisplayPairing
import com.aynthor.taskswap.task.DisplaySwapper
import com.aynthor.taskswap.task.DisplayTransferRules
import com.aynthor.taskswap.task.ShellPackages
import com.aynthor.taskswap.task.TaskResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class TaskSwapService : AccessibilityService() {

    companion object {
        private const val TAG = "TaskSwapService"

        val displayApps = ConcurrentHashMap<Int, String>()

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var availableDisplayIds = emptyList<Int>()
            private set

        @Volatile
        var lastSwapMessage: String = ""
            private set

        /**
         * Set true only for key-mapping diagnostics (no gesture actions).
         * Confirmed Thor map (2026-07-18 device log):
         * - Back: KEYCODE_BACK, Odin Controller, scan=158
         * - Home: KEYCODE_HOME, Odin Controller, scan=102
         * - AYN:  KEYCODE_HOME, gpio-keys, scan=194 (linux KEY_F24)
         */
        const val KEY_LOG_ONLY = false

        /** Last single-line key summary for the UI. */
        @Volatile
        var lastKeyDebug: String = ""
            private set

        /** Newest-first ring of raw key lines (for copy/screenshot). */
        @Volatile
        var keyLogHistory: List<String> = emptyList()
            private set

        private const val KEY_LOG_LIMIT = 40

        private val keyLogLock = Any()

        fun clearKeyLog() {
            synchronized(keyLogLock) {
                keyLogHistory = emptyList()
                lastKeyDebug = ""
            }
        }

        /** Append a line from getevent / external capture into the same UI log. */
        fun appendExternalKeyLog(line: String) {
            pushKeyLog(line)
        }

        private fun pushKeyLog(line: String) {
            synchronized(keyLogLock) {
                lastKeyDebug = line
                keyLogHistory = (listOf(line) + keyLogHistory).take(KEY_LOG_LIMIT)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val displaySwapper by lazy { DisplaySwapper(this) }
    private val shellExecutor by lazy { ShellExecutor() }
    private val gestures = ButtonGestures(holdThresholdMs = 1000L, doubleTapWindowMs = 300L)

    private val holdThresholdMs = 1000L
    private val doubleTapWindowMs = 300L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var swapInProgress = false

    @Volatile
    private var minimizeInProgress = false

    /**
     * Snapshot at Back DOWN for long-Back push.
     * Prefer last touch/interaction — Thor key-focus often stays on the top YouTube.
     */
    @Volatile
    private var backDownActiveDisplayId: Int? = null

    @Volatile
    private var backDownActivePackage: String? = null

    /** Last real user interaction (touch / window) — beats sticky top-display key focus. */
    @Volatile
    private var lastInteractedDisplayId: Int? = null

    @Volatile
    private var lastInteractedPackage: String? = null

    @Volatile
    private var lastInteractionFromTouch: Boolean = false

    @Volatile
    private var lastInteractionAtElapsed: Long = 0L

    private val ignoredPackages = mutableSetOf<String>().apply {
        addAll(ShellPackages.ALWAYS_IGNORED)
    }
    private val scanIgnoredPackages = mutableSetOf<String>().apply {
        addAll(ShellPackages.ALWAYS_IGNORED)
    }
    private val launcherPackages = mutableSetOf<String>()

    // --- One long-press timer per physical button (DOWN start / UP cancel / fire → done) ---

    private val backLongRunnable = Runnable { fireLong(ButtonGestures.Key.BACK) }
    private val homeLongRunnable = Runnable { fireLong(ButtonGestures.Key.HOME) }
    private val aynLongRunnable = Runnable { fireLong(ButtonGestures.Key.AYN) }

    private fun fireLong(key: ButtonGestures.Key) {
        val decision = when (key) {
            ButtonGestures.Key.BACK -> {
                if (!gestures.isBackHeld()) return
                gestures.onBackHoldTimeout()
            }
            ButtonGestures.Key.HOME -> {
                if (!gestures.isHomeHeld()) return
                gestures.onHomeHoldTimeout()
            }
            ButtonGestures.Key.AYN -> {
                if (!gestures.isAynHeld()) return
                // Physical AYN is consumed — firmware never sees the hold, so no blank.
                gestures.onAynHoldTimeout()
            }
        }
        val ran = applyDecision(decision)
        if (ran) vibrateShort()
        Log.i(TAG, "$key long fired")
    }

    private val backSingleRunnable = Runnable {
        applyDecision(gestures.onBackSingleTapTimeout())
    }

    /**
     * After short-AYN gpio replay, let the next DOWN+UP reach the system (native AYN menu)
     * without re-entering gesture state. Must NOT swallow — that was why menu never opened.
     */
    @Volatile
    private var remainingAynReplayPassThrough = 0

    @Volatile
    private var aynReplayUntilElapsed = 0L

    @Volatile
    private var cachedGpioEventNode: String? = null

    private fun longRunnableFor(key: ButtonGestures.Key): Runnable = when (key) {
        ButtonGestures.Key.BACK -> backLongRunnable
        ButtonGestures.Key.HOME -> homeLongRunnable
        ButtonGestures.Key.AYN -> aynLongRunnable
    }

    private fun armLongTimer(key: ButtonGestures.Key) {
        val r = longRunnableFor(key)
        handler.removeCallbacks(r)
        handler.postDelayed(r, holdThresholdMs)
    }

    private fun cancelLongTimer(key: ButtonGestures.Key) {
        handler.removeCallbacks(longRunnableFor(key))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AdbConnectionManager.init(applicationContext)

        serviceInfo = serviceInfo.apply {
            // Clicks/focus/window: Thor leaves key-focus on top while the user uses the bottom screen.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        refreshDisplays()
        detectLaunchers()
        postPersistentNotification()
        isRunning = true

        scope.launch {
            AdbConnectionManager.reconnect()
            disableOdinAccidentalHomeGuard()
        }

        Log.d(TAG, "Connected, displays: $availableDisplayIds")
    }

    /**
     * Odin Game Assistant "Prevent pressing Home accidentally" swallows the first Home
     * and shows "press home again to return to launcher". Disable so we can own Home.
     */
    private suspend fun disableOdinAccidentalHomeGuard() {
        val key = "prevent_press_home_accidentally"
        try {
            val cur = Settings.System.getInt(contentResolver, key, 1)
            if (cur != 0) {
                val ok = Settings.System.putInt(contentResolver, key, 0)
                Log.i(TAG, "Settings.System $key: $cur → 0 (put=$ok)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write $key via Settings: ${e.message}")
        }
        // ADB path — works without WRITE_SETTINGS when wireless ADB is up.
        val shell = AdbConnectionManager.shell("settings put system $key 0")
        if (shell.isSuccess) {
            Log.i(TAG, "ADB disabled $key")
        } else {
            Log.w(TAG, "ADB disable $key: ${shell.exceptionOrNull()?.message}")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        getSystemService(NotificationManager::class.java)?.cancel(1)
        scope.launch { AdbConnectionManager.disconnect() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: return
        val displayId = getDisplayIdFromEvent(event) ?: getDisplayIdFromWindowId(event.windowId)

        if (pkg in launcherPackages || ShellPackages.isShellOrIgnored(pkg)) {
            if (swapInProgress) return
            if (displayId != null) {
                displayApps.remove(displayId)
            }
            return
        }

        if (pkg in scanIgnoredPackages) return
        if (displayId == null) return

        val fromTouch = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        // WINDOW_STATE: only when the foreground app on that display actually changed
        // (ignore YouTube title/ad churn that would steal push source from bottom Settings).
        val appChangedOnDisplay = displayApps[displayId] != pkg
        if (fromTouch || (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && appChangedOnDisplay)) {
            noteUserInteraction(displayId, pkg, fromTouch = fromTouch)
        }

        val previousDisplay = displayApps.entries
            .firstOrNull { it.value == pkg && it.key != displayId }
            ?.key
        displayApps[displayId] = pkg
        // User picked this app from Recents/task list on another screen:
        // move the existing task here instead of letting the system fail and wipe UI.
        if (previousDisplay != null &&
            !swapInProgress &&
            !minimizeInProgress &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            scope.launch {
                val result = displaySwapper.ensureAppOnDisplay(
                    packageName = pkg,
                    targetDisplayId = displayId,
                    ignoredPackages = ignoredPackages + launcherPackages + scanIgnoredPackages
                )
                if (result.success) {
                    displayApps.remove(previousDisplay)
                    displayApps[displayId] = pkg
                    Log.i(TAG, result.message)
                } else {
                    Log.w(TAG, "ensureAppOnDisplay: ${result.message}")
                }
            }
        }
    }

    private fun noteUserInteraction(displayId: Int, packageName: String, fromTouch: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // After a recent touch on display A, ignore non-touch noise from display B.
        if (!fromTouch &&
            lastInteractionFromTouch &&
            lastInteractedDisplayId != null &&
            lastInteractedDisplayId != displayId &&
            now - lastInteractionAtElapsed < 60_000L
        ) {
            return
        }
        lastInteractedDisplayId = displayId
        lastInteractedPackage = packageName
        lastInteractionFromTouch = fromTouch
        lastInteractionAtElapsed = now
        Log.d(TAG, "interaction display=$displayId pkg=$packageName touch=$fromTouch")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val line = formatKeyLogLine(event)
        pushKeyLog(line)
        Log.i(TAG, "KEY $line")

        if (KEY_LOG_ONLY) {
            // Consume Thor keys so system Home does not leave the app before you see the log.
            // (Previously return false → first Home minimized/left the app; second press looked "broken".)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                vibrateShort()
            }
            return true
        }

        // --- normal gesture path ---
        // gpio replay for short AYN: pass through to system, skip gesture re-entry.
        if (shouldPassThroughAynReplay(event)) {
            remainingAynReplayPassThrough = (remainingAynReplayPassThrough - 1).coerceAtLeast(0)
            if (remainingAynReplayPassThrough == 0) aynReplayUntilElapsed = 0L
            Log.d(TAG, "AYN replay pass-through (${remainingAynReplayPassThrough} left)")
            return false
        }

        val deviceName = event.device?.name
        val key = ThorKeyMapper.map(event.keyCode, event.source, deviceName)
        if (key == null) return false

        val gesturesEnabled = GestureSettings.isEnabled(this)
        if (!gesturesEnabled) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            // AYN DOWN is consumed — eat repeats so firmware never sees a hold.
            return true
        }

        val act = when (event.action) {
            KeyEvent.ACTION_DOWN -> ButtonGestures.KeyAction.DOWN
            KeyEvent.ACTION_UP -> ButtonGestures.KeyAction.UP
            else -> return false
        }

        when (act) {
            ButtonGestures.KeyAction.DOWN -> {
                val alreadyHeld = when (key) {
                    ButtonGestures.Key.BACK -> gestures.isBackHeld()
                    ButtonGestures.Key.HOME -> gestures.isHomeHeld()
                    ButtonGestures.Key.AYN -> gestures.isAynHeld()
                }
                if (key == ButtonGestures.Key.BACK) {
                    handler.removeCallbacks(backSingleRunnable)
                    if (!alreadyHeld) snapshotPushActorAtBackDown()
                }
                if (!alreadyHeld) armLongTimer(key)
            }
            ButtonGestures.KeyAction.UP -> {
                cancelLongTimer(key)
                if (key == ButtonGestures.Key.BACK && !gestures.isBackLongFired()) {
                    backDownActiveDisplayId = null
                    backDownActivePackage = null
                }
            }
        }

        val decision = gestures.onKeyEvent(key, act, event.eventTime)
        applyDecision(decision)

        if (decision.armBackSingleTimeout) {
            handler.removeCallbacks(backSingleRunnable)
            handler.postDelayed(backSingleRunnable, doubleTapWindowMs)
        }

        return decision.consume
    }

    private fun formatKeyLogLine(event: KeyEvent): String {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "ACT${event.action}"
        }
        val device = event.device
        val deviceName = device?.name ?: "null"
        val mapped = ThorKeyMapper.map(event.keyCode, event.source, device?.name)?.name ?: "?"
        val vendor = device?.vendorId ?: -1
        val product = device?.productId ?: -1
        val descriptor = try {
            device?.descriptor?.take(24) ?: "-"
        } catch (_: Exception) {
            "-"
        }
        return buildString {
            append(action)
            append(' ')
            append(KeyEvent.keyCodeToString(event.keyCode))
            append('(')
            append(event.keyCode)
            append(") map=")
            append(mapped)
            append(" src=0x")
            append(Integer.toHexString(event.source))
            append(" devId=")
            append(event.deviceId)
            append(" name=")
            append(deviceName)
            append(" scan=")
            append(event.scanCode)
            append(" rep=")
            append(event.repeatCount)
            append(" flags=0x")
            append(Integer.toHexString(event.flags))
            append(" meta=0x")
            append(Integer.toHexString(event.metaState))
            append(" vid=")
            append(vendor)
            append(" pid=")
            append(product)
            append(" desc=")
            append(descriptor)
            append(" down=")
            append(event.downTime)
            append(" t=")
            append(event.eventTime)
        }
    }

    /** @return true if at least one custom (non-system-short) action was started. */
    private fun applyDecision(decision: ButtonGestures.Decision): Boolean {
        var ranCustom = false
        for (raw in decision.actions) {
            val action = when (raw) {
                is ButtonGestures.Action.Custom -> GestureSettings.resolve(this, raw.slot)
                else -> raw
            } ?: continue
            when (action) {
                ButtonGestures.Action.SystemBack -> performGlobalAction(GLOBAL_ACTION_BACK)
                is ButtonGestures.Action.Custom -> { /* already resolved */ }
                ButtonGestures.Action.OpenAllAppsList -> {
                    ranCustom = true
                    // Wake before launching — long AYN must not leave the bottom screen off.
                    wakeScreen()
                    openAllAppsList()
                }
                ButtonGestures.Action.MinimizeAllDisplays -> {
                    ranCustom = true
                    if (!minimizeInProgress) scope.launch { minimizeAllApps() }
                }
                ButtonGestures.Action.SwapDisplaysOrSendSingle -> {
                    ranCustom = true
                    handler.removeCallbacks(backSingleRunnable)
                    if (!swapInProgress) {
                        scope.launch {
                            swapInProgress = true
                            try {
                                performSwap()
                            } finally {
                                swapInProgress = false
                            }
                        }
                    }
                }
                ButtonGestures.Action.PushActiveToOtherDisplay -> {
                    ranCustom = true
                    handler.removeCallbacks(backSingleRunnable)
                    if (!swapInProgress) {
                        scope.launch {
                            swapInProgress = true
                            try {
                                pushActiveAppToOtherDisplay()
                            } finally {
                                swapInProgress = false
                            }
                        }
                    }
                }
            }
        }
        if (decision.scheduleHomeSystemInject) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (decision.scheduleAynGpioInject) {
            scope.launch { injectGpioAynShort() }
        }
        return ranCustom
    }

    /**
     * Replay one gpio AYN tap so the vendor menu opens after we consumed the physical press.
     * Echoes must pass through (not be swallowed) — otherwise the menu never sees the key.
     */
    private suspend fun injectGpioAynShort() {
        val node = cachedGpioEventNode
            ?: shellExecutor.findInputEventNode("gpio-keys")?.also { cachedGpioEventNode = it }
        if (node == null) {
            Log.w(TAG, "AYN short: gpio node not found (is wireless ADB connected?)")
            return
        }
        remainingAynReplayPassThrough = 2
        aynReplayUntilElapsed = SystemClock.elapsedRealtime() + 1_200L
        val result = shellExecutor.sendeventKeyPulse(node, ThorKeyMapper.SCAN_AYN_F24)
        if (result.isSuccess && result.getOrNull()?.success == true) {
            Log.i(TAG, "AYN short: sendevent gpio pulse on $node")
        } else {
            remainingAynReplayPassThrough = 0
            aynReplayUntilElapsed = 0L
            Log.w(
                TAG,
                "AYN short: sendevent failed: ${result.exceptionOrNull()?.message ?: result.getOrNull()?.output}"
            )
        }
    }

    private fun shouldPassThroughAynReplay(event: KeyEvent): Boolean {
        if (remainingAynReplayPassThrough <= 0) return false
        if (SystemClock.elapsedRealtime() >= aynReplayUntilElapsed) {
            remainingAynReplayPassThrough = 0
            return false
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME && event.keyCode != ThorKeyMapper.KEYCODE_F24) {
            return false
        }
        val name = event.device?.name?.lowercase().orEmpty()
        if (name.contains("odin") || name.contains("controller") || name.contains("gamepad")) {
            return false
        }
        return true
    }

    /** Keep display awake when opening All Apps (safety net). */
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "thor:ayn_long"
            )
            wakeLock.acquire(3_000)
            wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreen wakeLock: ${e.message}")
        }
        scope.launch {
            shellExecutor.inputKeyevent(KeyEvent.KEYCODE_WAKEUP)
        }
    }

    /** System all-apps list ("список приложений"). */
    private fun openAllAppsList() {
        val intent = Intent(Intent.ACTION_ALL_APPS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activeDisplayId = getActiveDisplayId()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activeDisplayId != null) {
                val options = ActivityOptions.makeBasic().apply {
                    @Suppress("NewApi")
                    launchDisplayId = activeDisplayId
                }
                startActivity(intent, options.toBundle())
            } else {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_ALL_APPS failed, falling back to launcher: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private suspend fun minimizeAllApps() {
        if (minimizeInProgress) {
            Log.w(TAG, "Minimize already in progress")
            return
        }
        minimizeInProgress = true
        try {
            refreshDisplays()
            scanAllDisplayApps()
            refreshDisplayMapFromDumpsys()

            // Hide every known app — not only the swap-pair peer (scan can miss one side).
            val targets = displayApps.entries.map { it.key to it.value }
            val homeDisplays = DisplayPairing.displaysForMinimize(
                availableDisplayIds,
                displayApps.keys
            ).ifEmpty { availableDisplayIds.take(2) }

            var hiddenCount = 0
            for ((displayId, pkg) in targets) {
                val result = displaySwapper.hideAppOnDisplay(
                    displayId = displayId,
                    packageName = pkg,
                    ignoredPackages = ignoredPackages
                )
                if (result.success) {
                    hiddenCount++
                    displayApps.remove(displayId)
                } else {
                    Log.w(TAG, "hide $pkg on display $displayId failed: ${result.message}")
                }
            }

            // Always Home every minimize target so both Thor screens reset even when hide fails.
            var homeOk = 0
            for (displayId in homeDisplays) {
                val home = shellExecutor.launchHomeOnDisplay(displayId)
                if (home.isSuccess && home.getOrNull()?.success == true) {
                    homeOk++
                } else {
                    Log.w(
                        TAG,
                        "Home on display $displayId failed: " +
                            "${home.exceptionOrNull()?.message ?: home.getOrNull()?.output}"
                    )
                }
            }
            // Default display fallback when ADB home is flaky.
            if (homeOk == 0 && homeDisplays.isNotEmpty()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            lastSwapMessage = if (hiddenCount > 0 || homeOk > 0) {
                "Свернули приложения / Home на экранах $homeDisplays"
            } else {
                "Не удалось свернуть приложения"
            }
            Log.i(TAG, lastSwapMessage)
        } finally {
            minimizeInProgress = false
        }
    }

    private suspend fun performSwap() {
        refreshDisplays()
        // Pure accessibility map only — dumpsys underlays must not pollute "how many apps".
        val a11yApps = scanDisplayAppsSnapshot()
        displayApps.clear()
        displayApps.putAll(a11yApps)

        val result = displaySwapper.performSwapOrSend(
            displayApps = a11yApps,
            displayIds = availableDisplayIds,
            ignoredPackages = ignoredPackages + launcherPackages + scanIgnoredPackages
        )

        lastSwapMessage = result.message
        Log.i(TAG, result.message)

        if (result.success) {
            scanAllDisplayApps()
        }
    }

    private fun snapshotPushActorAtBackDown() {
        // Only freeze last user interaction — do NOT bake sticky top focus into this snapshot
        // (focus is passed separately and loses to dumpsys lastActiveTime).
        backDownActiveDisplayId = lastInteractedDisplayId
        backDownActivePackage = lastInteractedPackage
        val focused = findFocusedApplication()
        Log.i(
            TAG,
            "Back-DOWN snapshot: lastInteract=$backDownActiveDisplayId/$backDownActivePackage " +
                "focus=${focused?.first}/${focused?.second}"
        )
    }

    private suspend fun pushActiveAppToOtherDisplay() {
        refreshDisplays()
        // Prefer actor captured at Back DOWN (last interaction beats sticky top focus).
        val snapDisplay = backDownActiveDisplayId
        val snapPkg = backDownActivePackage
        backDownActiveDisplayId = null
        backDownActivePackage = null

        val liveFocused = findFocusedApplication()
        val a11yApps = scanDisplayAppsSnapshot().toMutableMap()
        // Fill gaps from last interaction / focus so Settings is present even if scan missed it.
        val seedDisplay = snapDisplay ?: lastInteractedDisplayId ?: liveFocused?.first
        val seedPkg = snapPkg ?: lastInteractedPackage ?: liveFocused?.second
        if (seedDisplay != null && seedPkg != null) {
            if (seedPkg !in scanIgnoredPackages &&
                seedPkg !in launcherPackages &&
                !ShellPackages.isShellOrIgnored(seedPkg)
            ) {
                a11yApps.putIfAbsent(seedDisplay, seedPkg)
            }
        }
        displayApps.clear()
        displayApps.putAll(a11yApps)

        val pair = DisplayPairing.pickSwapDisplays(availableDisplayIds, a11yApps.keys)
        val actor = DisplayTransferRules.resolvePushActor(
            pair = pair,
            apps = a11yApps.filterValues {
                it !in ignoredPackages &&
                    it !in launcherPackages &&
                    it !in scanIgnoredPackages &&
                    !ShellPackages.isShellOrIgnored(it)
            },
            lastInteractedDisplayId = snapDisplay ?: lastInteractedDisplayId,
            lastInteractedPackage = snapPkg ?: lastInteractedPackage,
            focusedDisplayId = liveFocused?.first,
            focusedPackage = liveFocused?.second
        )

        val activeDisplayId = actor?.displayId
            ?: snapDisplay
            ?: getActiveDisplayId(a11yApps)
        if (activeDisplayId == null) {
            lastSwapMessage = "Активный экран не найден"
            Log.w(TAG, lastSwapMessage)
            return
        }

        val otherDisplayId = DisplayPairing.otherDisplay(
            activeDisplayId,
            availableDisplayIds,
            a11yApps.keys
        )
        if (otherDisplayId == null) {
            lastSwapMessage = "Второй экран не найден"
            Log.w(TAG, lastSwapMessage)
            return
        }

        val result = displaySwapper.pushActiveAppToOther(
            activeDisplayId = activeDisplayId,
            otherDisplayId = otherDisplayId,
            displayApps = a11yApps,
            ignoredPackages = ignoredPackages + launcherPackages + scanIgnoredPackages,
            lastInteractedDisplayId = snapDisplay ?: lastInteractedDisplayId,
            lastInteractedPackage = snapPkg ?: lastInteractedPackage,
            focusedDisplayId = liveFocused?.first,
            focusedPackage = liveFocused?.second
        )

        lastSwapMessage = result.message
        Log.i(TAG, result.message)

        if (result.success) {
            scanAllDisplayApps()
        }
    }

    private fun getActiveDisplayId(a11yApps: Map<Int, String> = displayApps.toMap()): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val focused = findFocusedApplicationDisplayId()
                if (focused != null) return focused
            } catch (e: Exception) {
                Log.w(TAG, "getActiveDisplayId failed: ${e.message}")
            }
        }
        // Physical Back often leaves focus on top while the only real app is on bottom.
        val sole = a11yApps.entries.singleOrNull {
            it.value !in ignoredPackages &&
                it.value !in launcherPackages &&
                it.value !in scanIgnoredPackages &&
                !ShellPackages.isShellOrIgnored(it.value)
        }?.key
        if (sole != null) return sole
        return availableDisplayIds.firstOrNull()
    }

    private fun findFocusedApplicationDisplayId(): Int? =
        findFocusedApplication()?.first

    /**
     * Focused window on any display: displayId + package when root is readable.
     * Package may be null if the focused application window has a null root.
     */
    private fun findFocusedApplication(): Pair<Int, String?>? {
        val candidates = mutableListOf<AccessibilityWindowInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("NewApi")
            val sparse: SparseArray<List<AccessibilityWindowInfo>> = getWindowsOnAllDisplays()
            for (i in 0 until sparse.size()) {
                candidates += sparse.valueAt(i).orEmpty()
            }
        } else {
            candidates += windows.orEmpty()
        }
        val ordered = candidates.filter { it.isFocused }.sortedBy { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1
        }
        var displayOnly: Int? = null
        for (window in ordered) {
            @Suppress("NewApi")
            val displayId = window.displayId
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && displayOnly == null) {
                displayOnly = displayId
            }
            val root = window.root ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                return displayId to pkg
            } finally {
                root.recycle()
            }
        }
        return displayOnly?.let { it to null }
            ?: ordered.firstOrNull()?.let { window ->
                @Suppress("NewApi")
                window.displayId to null
            }
    }

    private suspend fun refreshDisplayMapFromDumpsys() {
        val connectResult = AdbConnectionManager.ensureConnected()
        if (connectResult.isFailure) return

        val dumpsys = ShellExecutor().dumpsysActivities().getOrElse { return }
        val stackList = ShellExecutor().stackList().getOrNull()
        val apps = TaskResolver().resolveDisplayApps(
            dumpsys,
            scanIgnoredPackages + launcherPackages,
            stackList
        )
        if (apps.isNotEmpty()) {
            // Only fill gaps — never overwrite accessibility (underlays steal the count).
            for ((id, pkg) in apps) {
                if (!displayApps.containsKey(id)) displayApps[id] = pkg
            }
            Log.d(TAG, "Display map from dumpsys (gaps only): $apps; now $displayApps")
        }
    }

    /** Fresh accessibility scan — does not merge dumpsys. */
    private fun scanDisplayAppsSnapshot(): Map<Int, String> {
        val found = linkedMapOf<Int, String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("NewApi")
                val sparse: SparseArray<List<AccessibilityWindowInfo>> = getWindowsOnAllDisplays()
                for (i in 0 until sparse.size()) {
                    collectWindowsForDisplay(
                        windows = sparse.valueAt(i).orEmpty(),
                        displayId = sparse.keyAt(i),
                        into = found
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val grouped = mutableMapOf<Int, MutableList<AccessibilityWindowInfo>>()
                for (window in windows.orEmpty()) {
                    @Suppress("NewApi")
                    val displayId = window.displayId
                    grouped.getOrPut(displayId) { mutableListOf() }.add(window)
                }
                for ((displayId, windowList) in grouped) {
                    collectWindowsForDisplay(windowList, displayId, found)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanDisplayAppsSnapshot failed: ${e.message}")
        }
        Log.d(TAG, "Accessibility snapshot: $found")
        return found
    }

    fun scanAllDisplayApps() {
        val found = scanDisplayAppsSnapshot()
        if (found.isNotEmpty()) {
            displayApps.clear()
            displayApps.putAll(found)
            Log.d(TAG, "Display map from accessibility: $found")
        } else {
            Log.d(TAG, "Accessibility scan empty — keeping previous map: $displayApps")
        }
    }

    private fun collectWindowsForDisplay(
        windows: List<AccessibilityWindowInfo>,
        displayId: Int,
        into: MutableMap<Int, String>
    ) {
        val sorted = windows.sortedWith(
            compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                .thenByDescending { it.isActive }
                .thenBy { if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1 }
        )
        for (window in sorted) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root
            if (root == null) {
                Log.d(TAG, "scan: display $displayId window root is null")
                continue
            }
            try {
                val pkg = root.packageName?.toString() ?: continue
                if (pkg in scanIgnoredPackages || pkg in launcherPackages || ShellPackages.isShellOrIgnored(pkg)) continue
                into[displayId] = pkg
                Log.d(TAG, "scan: display $displayId -> $pkg")
                break
            } finally {
                root.recycle()
            }
        }
    }

    private fun getDisplayIdFromEvent(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var source: AccessibilityNodeInfo? = null
        var window: AccessibilityWindowInfo? = null
        try {
            source = event.source ?: return null
            window = source.window ?: return null
            @Suppress("NewApi")
            return window.displayId
        } catch (_: Exception) {
            return null
        } finally {
            window?.recycle()
            source?.recycle()
        }
    }

    private fun getDisplayIdFromWindowId(windowId: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        try {
            for (window in windows ?: return null) {
                if (window.id == windowId) {
                    @Suppress("NewApi")
                    return window.displayId
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun refreshDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        availableDisplayIds = dm.displays.map { it.displayId }
    }

    private fun detectLaunchers() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val falsePositives = setOf("com.android.settings", "com.android.permissioncontroller")
        val launchers = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        for (ri in launchers) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in falsePositives) continue
            launcherPackages.add(pkg)
            ignoredPackages.add(pkg)
        }
    }

    private fun postPersistentNotification() {
        val channelId = "thor_display_swapper"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(1, notification)
    }

    private fun vibrateShort() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed: ${e.message}")
        }
    }
}
