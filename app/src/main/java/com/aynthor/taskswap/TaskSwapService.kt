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
import android.os.Build
import android.os.Handler
import android.os.Looper
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

        /** Last filtered key for on-device debugging of Home/AYN mapping. */
        @Volatile
        var lastKeyDebug: String = ""
            private set
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
     * After injecting short-AYN via `input keyevent`, pass KEYCODE_HOME through to the system
     * for a short window. Count-based ignore was dropping real Home/AYN presses when fewer
     * synthetic events arrived than expected.
     */
    @Volatile
    private var ignoreAynInjectUntilElapsedRealtime = 0L

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

    private val backLongPressRunnable = Runnable {
        if (!gestures.isBackHeld()) return@Runnable
        val decision = gestures.onBackHoldTimeout()
        val ranCustom = applyDecision(decision)
        if (ranCustom) vibrateShort()
    }

    private val homeLongPressRunnable = Runnable {
        if (!gestures.isHomeHeld()) return@Runnable
        val decision = gestures.onHomeHoldTimeout()
        val ranCustom = applyDecision(decision)
        if (ranCustom) vibrateShort()
    }

    private val aynLongPressRunnable = Runnable {
        if (!gestures.isAynHeld()) return@Runnable
        val decision = gestures.onAynHoldTimeout()
        val ranCustom = applyDecision(decision)
        if (ranCustom) vibrateShort()
    }

    private val singleBackRunnable = Runnable {
        applyDecision(gestures.onBackSingleTapTimeout())
    }

    private val homeSingleTapRunnable = Runnable {
        applyDecision(gestures.onHomeSingleTapTimeout())
    }

    private val aynSingleTapRunnable = Runnable {
        applyDecision(gestures.onAynSingleTapTimeout())
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
        }

        Log.d(TAG, "Connected, displays: $availableDisplayIds")
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
        // Injected short-AYN must reach the system; everything else for HOME must be filtered
        // or Thor firmware turns off / dims the bottom screen on long-press.
        if (event.keyCode == KeyEvent.KEYCODE_HOME &&
            SystemClock.elapsedRealtime() < ignoreAynInjectUntilElapsedRealtime
        ) {
            return false
        }

        val deviceName = event.device?.name
        val key = ThorKeyMapper.map(event.keyCode, event.source, deviceName) ?: return false
        val gesturesEnabled = GestureSettings.isEnabled(this)

        // When custom gestures are off, let system handle Back entirely.
        if (!gesturesEnabled && key == ButtonGestures.Key.BACK) {
            return false
        }

        val act = when (event.action) {
            KeyEvent.ACTION_DOWN -> ButtonGestures.KeyAction.DOWN
            KeyEvent.ACTION_UP -> ButtonGestures.KeyAction.UP
            else -> return false
        }

        // Key repeats still must be consumed for AYN/Home or firmware steals the hold.
        // Also use downTime as a backup long-press trigger if the Handler timer was starved
        // by duplicate zero-repeat DOWNs from the controller firmware.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            lastKeyDebug =
                "${KeyEvent.keyCodeToString(event.keyCode)}→$key REPEAT src=0x${Integer.toHexString(event.source)} dev=$deviceName"
            if (gesturesEnabled) {
                maybeFireLongPressFromDownTime(key, event)
            }
            return true
        }

        lastKeyDebug =
            "${KeyEvent.keyCodeToString(event.keyCode)}→$key src=0x${Integer.toHexString(event.source)} dev=$deviceName"
        Log.d(
            TAG,
            "key=${KeyEvent.keyCodeToString(event.keyCode)} mapped=$key " +
                "source=0x${Integer.toHexString(event.source)} device=$deviceName " +
                "action=${event.action} repeat=${event.repeatCount}"
        )

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (key) {
                ButtonGestures.Key.BACK -> {
                    handler.removeCallbacks(singleBackRunnable)
                    // Arm long-press only once per physical hold. Duplicate DOWN(repeat=0)
                    // from gamepad firmware must not reset the 1s timer (Home was never firing).
                    if (!gestures.isBackHeld()) {
                        snapshotPushActorAtBackDown()
                        handler.removeCallbacks(backLongPressRunnable)
                        if (gesturesEnabled) {
                            handler.postDelayed(backLongPressRunnable, holdThresholdMs)
                        }
                    } else if (gesturesEnabled) {
                        maybeFireLongPressFromDownTime(key, event)
                    }
                }
                ButtonGestures.Key.HOME -> {
                    handler.removeCallbacks(homeSingleTapRunnable)
                    handler.removeCallbacks(aynSingleTapRunnable)
                    handler.removeCallbacks(aynLongPressRunnable)
                    if (!gestures.isHomeHeld()) {
                        handler.removeCallbacks(homeLongPressRunnable)
                        if (gesturesEnabled) {
                            handler.postDelayed(homeLongPressRunnable, holdThresholdMs)
                        }
                    } else if (gesturesEnabled) {
                        maybeFireLongPressFromDownTime(key, event)
                    }
                }
                ButtonGestures.Key.AYN -> {
                    handler.removeCallbacks(aynSingleTapRunnable)
                    handler.removeCallbacks(homeSingleTapRunnable)
                    handler.removeCallbacks(homeLongPressRunnable)
                    if (!gestures.isAynHeld()) {
                        handler.removeCallbacks(aynLongPressRunnable)
                        if (gesturesEnabled) {
                            handler.postDelayed(aynLongPressRunnable, holdThresholdMs)
                        }
                    } else if (gesturesEnabled) {
                        maybeFireLongPressFromDownTime(key, event)
                    }
                }
            }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            when (key) {
                ButtonGestures.Key.BACK -> {
                    handler.removeCallbacks(backLongPressRunnable)
                    if (!gestures.isBackLongFired()) {
                        backDownActiveDisplayId = null
                        backDownActivePackage = null
                    }
                }
                ButtonGestures.Key.HOME -> handler.removeCallbacks(homeLongPressRunnable)
                ButtonGestures.Key.AYN -> handler.removeCallbacks(aynLongPressRunnable)
            }
        }

        val decision = gestures.onKeyEvent(key, act, event.eventTime)
        applyDecision(decision)

        if (decision.armBackSingleTimeout) {
            handler.removeCallbacks(singleBackRunnable)
            handler.postDelayed(singleBackRunnable, doubleTapWindowMs)
        }
        if (key == ButtonGestures.Key.HOME &&
            event.action == KeyEvent.ACTION_UP &&
            decision.consume &&
            decision.actions.isEmpty() &&
            !decision.cancelHomeSystemInject &&
            !decision.scheduleHomeSystemInject
        ) {
            handler.removeCallbacks(homeSingleTapRunnable)
            handler.postDelayed(homeSingleTapRunnable, doubleTapWindowMs)
        }
        if (key == ButtonGestures.Key.AYN &&
            event.action == KeyEvent.ACTION_UP &&
            decision.consume &&
            decision.actions.isEmpty() &&
            !decision.cancelAynSystemInject &&
            !decision.scheduleAynSystemInject
        ) {
            handler.removeCallbacks(aynSingleTapRunnable)
            handler.postDelayed(aynSingleTapRunnable, doubleTapWindowMs)
        }

        return decision.consume
    }

    /** @return true if at least one custom (non-system-short) action was started. */
    private fun applyDecision(decision: ButtonGestures.Decision): Boolean {
        if (decision.cancelAynSystemInject) {
            handler.removeCallbacks(aynSingleTapRunnable)
        }
        if (decision.cancelHomeSystemInject) {
            handler.removeCallbacks(homeSingleTapRunnable)
        }
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
                    openAllAppsList()
                }
                ButtonGestures.Action.MinimizeAllDisplays -> {
                    ranCustom = true
                    if (!minimizeInProgress) scope.launch { minimizeAllApps() }
                }
                ButtonGestures.Action.SwapDisplaysOrSendSingle -> {
                    ranCustom = true
                    handler.removeCallbacks(singleBackRunnable)
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
                    handler.removeCallbacks(singleBackRunnable)
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
        if (decision.scheduleAynSystemInject) {
            injectSystemAynShortPress()
        }
        if (decision.scheduleHomeSystemInject) {
            // Short physical Home: deliver normal system Home without our long-press path.
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        return ranCustom
    }

    private fun injectSystemAynShortPress() {
        scope.launch {
            ignoreAynInjectUntilElapsedRealtime = SystemClock.elapsedRealtime() + 400L
            val result = shellExecutor.inputKeyevent(KeyEvent.KEYCODE_HOME)
            if (result.isFailure || result.getOrNull()?.success != true) {
                Log.w(TAG, "Failed to inject AYN short press: ${result.exceptionOrNull()?.message ?: result.getOrNull()?.output}")
                ignoreAynInjectUntilElapsedRealtime = 0L
            }
        }
    }

    /**
     * Backup long-press path: controller may spam DOWN with repeatCount=0 while held,
     * which used to keep re-arming postDelayed(1000) and never fire. When already held,
     * fire once [holdThresholdMs] has elapsed since KeyEvent.downTime.
     */
    private fun maybeFireLongPressFromDownTime(key: ButtonGestures.Key, event: KeyEvent) {
        if (event.eventTime - event.downTime < holdThresholdMs) return
        val decision = when (key) {
            ButtonGestures.Key.BACK -> {
                if (!gestures.isBackHeld() || gestures.isBackLongFired()) return
                handler.removeCallbacks(backLongPressRunnable)
                gestures.onBackHoldTimeout()
            }
            ButtonGestures.Key.HOME -> {
                if (!gestures.isHomeHeld() || gestures.isHomeLongFired()) return
                handler.removeCallbacks(homeLongPressRunnable)
                gestures.onHomeHoldTimeout()
            }
            ButtonGestures.Key.AYN -> {
                if (!gestures.isAynHeld() || gestures.isAynLongFired()) return
                handler.removeCallbacks(aynLongPressRunnable)
                gestures.onAynHoldTimeout()
            }
        }
        val ranCustom = applyDecision(decision)
        if (ranCustom) vibrateShort()
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

            val pair = DisplayPairing.pickSwapDisplays(availableDisplayIds, displayApps.keys)
            val targets = (pair.ifEmpty { availableDisplayIds }).mapNotNull { displayId ->
                displayApps[displayId]?.let { displayId to it }
            }

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

            // Always send each main display to Home so empty screens also reset.
            for (displayId in pair.ifEmpty { availableDisplayIds.take(2) }) {
                shellExecutor.launchHomeOnDisplay(displayId)
            }

            lastSwapMessage = if (hiddenCount > 0 || pair.isNotEmpty()) {
                "Свернули приложения / Home на экранах $pair"
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
