package com.aynthor.taskswap.adb

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Base64

data class ShellResult(
    val exitCode: Int,
    val output: String
) {
    val success: Boolean get() = exitCode == 0
}

object AdbConnectionManager {

    private const val TAG = "AdbConnection"
    private const val PREFS = "adb_connection"
    private const val KEY_CONNECTION_PORT = "connection_port"
    private const val KEY_PAIRED = "paired"
    private const val KEY_CERT = "cert_b64"
    private const val KEY_KEY = "key_b64"
    /** Dead wireless ADB after sleep/lid often hangs forever without a timeout. */
    private const val SHELL_TIMEOUT_MS = 4_000L
    /**
     * Skip the live ping if a shell succeeded recently. After lid/sleep the wake
     * receiver reconnects; otherwise a stale CONNECTED flag is caught by the ping.
     */
    private const val LIVE_SHELL_TRUST_MS = 20_000L

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        PAIRING_REQUIRED
    }

    private val mutex = Mutex()
    private var kadb: Kadb? = null
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile
    private var lastLiveShellAtMs: Long = 0L

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restoreIdentityIfNeeded()
    }

    fun isPaired(): Boolean = prefs?.getBoolean(KEY_PAIRED, false) == true

    fun savedConnectionPort(): Int? {
        val port = prefs?.getInt(KEY_CONNECTION_PORT, -1) ?: -1
        return port.takeIf { it > 0 }
    }

    /**
     * Load Kadb cert/key + prefs. KadbCert is memory-only, so every process start must restore.
     * Never generate a new key while [KEY_PAIRED] is true — that would invalidate device trust.
     */
    private fun restoreIdentityIfNeeded() {
        val p = prefs ?: return
        val ctx = appContext ?: return

        val backup = AdbIdentityStore.load(ctx)
        val certB64 = p.getString(KEY_CERT, null)?.takeIf { it.isNotBlank() }
            ?: backup?.certPem
        val keyB64 = p.getString(KEY_KEY, null)?.takeIf { it.isNotBlank() }
            ?: backup?.keyPem

        if (!certB64.isNullOrBlank() && !keyB64.isNullOrBlank()) {
            runCatching {
                KadbCert.set(
                    AdbIdentityStore.decodePem(certB64),
                    AdbIdentityStore.decodePem(keyB64)
                )
                Log.i(TAG, "Restored KadbCert (prefs=${p.contains(KEY_CERT)} backup=${backup != null})")
            }.onFailure {
                Log.w(TAG, "KadbCert restore failed: ${it.message}")
            }
        }

        if (backup != null) {
            val editor = p.edit()
            var changed = false
            if (!p.getBoolean(KEY_PAIRED, false) && backup.paired) {
                editor.putBoolean(KEY_PAIRED, true)
                changed = true
            }
            if (savedConnectionPort() == null && backup.connectionPort != null) {
                editor.putInt(KEY_CONNECTION_PORT, backup.connectionPort)
                changed = true
            }
            if (changed) {
                editor.commit()
                Log.i(TAG, "Restored ADB prefs from durable backup (port=${backup.connectionPort})")
            }
        }

        val hasInMemoryCert = runCatching {
            KadbCert.getOrError()
            true
        }.getOrDefault(false)

        when {
            hasInMemoryCert -> {
                // Mirror to all stores (prefs may have been wiped while filesDir backup survived).
                persistBackup()
            }
            isPaired() -> {
                // Paired before, but identity missing — do NOT mint a new key (would break trust).
                Log.e(TAG, "Paired flag set but KadbCert missing; re-pairing required")
                _state.value = State.PAIRING_REQUIRED
            }
            else -> {
                // First run: create identity for upcoming pair(), then persist.
                runCatching {
                    val (cert, key) = KadbCert.get()
                    persistBackup(cert, key)
                }.onFailure {
                    Log.w(TAG, "KadbCert.get failed: ${it.message}")
                }
            }
        }
    }

    private fun persistBackup(cert: ByteArray? = null, key: ByteArray? = null) {
        val ctx = appContext ?: return
        val (c, k) = when {
            cert != null && key != null -> cert to key
            else -> runCatching { KadbCert.getOrError() }.getOrNull() ?: return
        }
        prefs?.edit()
            ?.putString(KEY_CERT, Base64.getEncoder().encodeToString(c))
            ?.putString(KEY_KEY, Base64.getEncoder().encodeToString(k))
            ?.putBoolean(KEY_PAIRED, isPaired())
            ?.apply {
                savedConnectionPort()?.let { putInt(KEY_CONNECTION_PORT, it) }
            }
            ?.commit()
        AdbIdentityStore.save(
            context = ctx,
            paired = isPaired(),
            connectionPort = savedConnectionPort(),
            cert = c,
            key = k
        )
    }

    suspend fun pair(pairingPort: Int, code: String): Result<Unit> {
        _state.value = State.CONNECTING
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    // Ensure durable identity before pairing so device trusts the same key after reinstall.
                    runCatching { KadbCert.get() }
                    Log.d(TAG, "Pairing on port $pairingPort")
                    Kadb.pair("127.0.0.1", pairingPort, code.trim())
                    prefs?.edit()?.putBoolean(KEY_PAIRED, true)?.commit()
                    persistBackup()
                    Log.i(TAG, "Pairing successful")
                    Unit
                }.onFailure {
                    Log.e(TAG, "Pairing failed: ${it.message}", it)
                    _state.value = State.PAIRING_REQUIRED
                }
            }
        }
    }

    suspend fun connect(connectionPort: Int): Result<Unit> {
        _state.value = State.CONNECTING
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    runCatching { KadbCert.get() }
                    disconnectLocked()
                    Log.d(TAG, "Connecting on port $connectionPort")
                    val client = Kadb.create("127.0.0.1", connectionPort)
                    val test = withTimeout(SHELL_TIMEOUT_MS) { client.shell("echo ok") }
                    check(test.exitCode == 0) { "Shell test failed: ${test.output}" }
                    kadb = client
                    prefs?.edit()
                        ?.putInt(KEY_CONNECTION_PORT, connectionPort)
                        ?.putBoolean(KEY_PAIRED, true)
                        ?.commit()
                    persistBackup()
                    _state.value = State.CONNECTED
                    lastLiveShellAtMs = System.currentTimeMillis()
                    Log.i(TAG, "Connected on port $connectionPort")
                    Unit
                }.onFailure {
                    Log.e(TAG, "Connect failed: ${it.message}", it)
                    disconnectLocked()
                    _state.value = if (isPaired()) State.DISCONNECTED else State.PAIRING_REQUIRED
                }
            }
        }
    }

    suspend fun reconnect(): Result<Unit> {
        val port = savedConnectionPort()
            ?: return Result.failure(IllegalStateException("Порт подключения не сохранён"))
        return connect(port)
    }

    /**
     * Drop the recent-shell trust window (e.g. after lid close / screen off).
     * Next [ensureConnected] must ping or reconnect instead of assuming the socket is live.
     */
    fun invalidateLiveShellTrust() {
        lastLiveShellAtMs = 0L
    }

    /**
     * Ensures a live shell. After lid close / sleep the Kadb socket often dies while
     * [State.CONNECTED] stays set — a ping detects that and forces reconnect.
     * Short-AYN gpio replay depends on this; long-AYN (Intent) does not.
     */
    suspend fun ensureConnected(): Result<Unit> {
        if (_state.value == State.CONNECTED && kadb != null) {
            val trustUntil = lastLiveShellAtMs + LIVE_SHELL_TRUST_MS
            if (System.currentTimeMillis() < trustUntil) {
                return Result.success(Unit)
            }
            val ping = shellOnce("echo ok")
            if (ping.isSuccess && ping.getOrNull()?.success == true) {
                return Result.success(Unit)
            }
            Log.w(TAG, "Stale ADB connection (ping failed) — reconnecting")
        }
        return reconnect()
    }

    /**
     * Run a shell command. On transport failure, drop the dead client and retry once
     * after [reconnect] (covers post-sleep stale sockets).
     */
    suspend fun shell(command: String): Result<ShellResult> {
        val first = shellOnce(command)
        if (first.isSuccess) return first
        Log.w(TAG, "Shell transport failed, reconnect+retry: ${first.exceptionOrNull()?.message}")
        val re = reconnect()
        if (re.isFailure) return first
        return shellOnce(command)
    }

    private suspend fun shellOnce(command: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val client = kadb ?: error("ADB не подключён")
                withTimeout(SHELL_TIMEOUT_MS) {
                    val response = client.shell(command)
                    ShellResult(response.exitCode, response.output.trim())
                }
            }.onSuccess {
                lastLiveShellAtMs = System.currentTimeMillis()
            }.onFailure { e ->
                Log.w(TAG, "Shell failed, dropping client: ${e.message}")
                disconnectLocked()
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectLocked()
    }

    private fun disconnectLocked() {
        try {
            kadb?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Kadb: ${e.message}")
        }
        kadb = null
        lastLiveShellAtMs = 0L
        if (_state.value != State.PAIRING_REQUIRED) {
            _state.value = State.DISCONNECTED
        }
    }
}
