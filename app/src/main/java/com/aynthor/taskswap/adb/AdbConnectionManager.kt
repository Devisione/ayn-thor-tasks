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
     * Load Kadb cert/key + prefs from public backup when local prefs were wiped (reinstall).
     */
    private fun restoreIdentityIfNeeded() {
        val p = prefs ?: return
        val ctx = appContext ?: return

        // Always try to restore Kadb cert/key into memory (needed after process death / reinstall).
        val backup = AdbIdentityStore.load(ctx)
        if (backup != null) {
            val certB64 = backup.certPem
            val keyB64 = backup.keyPem
            if (!certB64.isNullOrBlank() && !keyB64.isNullOrBlank()) {
                runCatching {
                    KadbCert.set(
                        AdbIdentityStore.decodePem(certB64),
                        AdbIdentityStore.decodePem(keyB64)
                    )
                    Log.i(TAG, "Restored KadbCert from durable backup")
                }.onFailure {
                    Log.w(TAG, "KadbCert restore failed: ${it.message}")
                }
            }

            if (!p.getBoolean(KEY_PAIRED, false) && backup.paired) {
                p.edit()
                    .putBoolean(KEY_PAIRED, true)
                    .apply {
                        backup.connectionPort?.let { putInt(KEY_CONNECTION_PORT, it) }
                    }
                    .apply()
                Log.i(TAG, "Restored ADB prefs from durable backup (port=${backup.connectionPort})")
            }
        }

        // Ensure in-memory cert exists and is mirrored to backup after first generate.
        runCatching {
            val (cert, key) = KadbCert.get()
            persistBackup(cert, key)
        }.onFailure {
            Log.w(TAG, "KadbCert.get failed: ${it.message}")
        }
    }

    private fun persistBackup(cert: ByteArray? = null, key: ByteArray? = null) {
        val ctx = appContext ?: return
        val (c, k) = when {
            cert != null && key != null -> cert to key
            else -> runCatching { KadbCert.getOrError() }.getOrNull() ?: return
        }
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
                    prefs?.edit()?.putBoolean(KEY_PAIRED, true)?.apply()
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
                    val test = client.shell("echo ok")
                    check(test.exitCode == 0) { "Shell test failed: ${test.output}" }
                    kadb = client
                    prefs?.edit()
                        ?.putInt(KEY_CONNECTION_PORT, connectionPort)
                        ?.putBoolean(KEY_PAIRED, true)
                        ?.apply()
                    persistBackup()
                    _state.value = State.CONNECTED
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

    suspend fun ensureConnected(): Result<Unit> {
        if (_state.value == State.CONNECTED && kadb != null) {
            return Result.success(Unit)
        }
        return reconnect()
    }

    suspend fun shell(command: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val client = kadb ?: error("ADB не подключён")
                val response = client.shell(command)
                ShellResult(response.exitCode, response.output.trim())
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
        if (_state.value != State.PAIRING_REQUIRED) {
            _state.value = State.DISCONNECTED
        }
    }
}
