package com.aynthor.taskswap.adb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Persists Wireless ADB pairing identity so it survives process death and debug reinstalls.
 *
 * Priority (load): app filesDir → MediaStore Documents → public dir → app external files.
 * SharedPreferences cert/key are handled separately by [AdbConnectionManager] (best for `adb install -r`).
 */
object AdbIdentityStore {

    private const val TAG = "AdbIdentityStore"
    private const val DIR_NAME = "ThorDisplaySwapper"
    private const val FILE_NAME = "adb_backup.json"
    private const val MIME = "application/json"

    data class Backup(
        val paired: Boolean,
        val connectionPort: Int?,
        val certPem: String?,
        val keyPem: String?
    )

    fun encode(
        paired: Boolean,
        connectionPort: Int?,
        cert: ByteArray?,
        key: ByteArray?
    ): ByteArray {
        val certJson = cert?.let { "\"${Base64.getEncoder().encodeToString(it)}\"" } ?: "null"
        val keyJson = key?.let { "\"${Base64.getEncoder().encodeToString(it)}\"" } ?: "null"
        return """{"paired":$paired,"connectionPort":${connectionPort ?: -1},"cert":$certJson,"key":$keyJson}"""
            .toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): Backup? = runCatching {
        val text = bytes.toString(Charsets.UTF_8)
        Backup(
            paired = readBoolean(text, "paired") ?: false,
            connectionPort = readInt(text, "connectionPort")?.takeIf { it > 0 },
            certPem = readString(text, "cert"),
            keyPem = readString(text, "key")
        )
    }.onFailure {
        Log.w(TAG, "Failed to parse ADB identity: ${it.message}")
    }.getOrNull()

    private fun readBoolean(json: String, key: String): Boolean? {
        val match = Regex("\"$key\"\\s*:\\s*(true|false)").find(json) ?: return null
        return match.groupValues[1].toBoolean()
    }

    private fun readInt(json: String, key: String): Int? {
        val match = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun readString(json: String, key: String): String? {
        val nullMatch = Regex("\"$key\"\\s*:\\s*null").find(json)
        val stringMatch = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)
        if (nullMatch != null && (stringMatch == null || nullMatch.range.first < stringMatch.range.first)) {
            return null
        }
        val value = stringMatch?.groupValues?.get(1) ?: return null
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    fun save(
        context: Context,
        paired: Boolean,
        connectionPort: Int?,
        cert: ByteArray?,
        key: ByteArray?
    ) {
        val payload = encode(paired, connectionPort, cert, key)
        val okInternal = saveViaInternal(context, payload)
        val okMedia = saveViaMediaStore(context, payload)
        val okFile = saveViaPublicFile(payload)
        val okApp = saveViaAppExternal(context, payload)
        Log.i(TAG, "ADB identity save internal=$okInternal media=$okMedia file=$okFile appExt=$okApp")
    }

    fun load(context: Context): Backup? {
        val bytes = loadViaInternal(context)
            ?: loadViaMediaStore(context)
            ?: loadViaPublicFile()
            ?: loadViaAppExternal(context)
            ?: return null
        return parse(bytes)
    }

    fun decodePem(base64: String): ByteArray =
        Base64.getDecoder().decode(base64)

    private fun internalFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun saveViaInternal(context: Context, payload: ByteArray): Boolean {
        return runCatching {
            FileOutputStream(internalFile(context)).use { it.write(payload) }
            true
        }.onFailure {
            Log.w(TAG, "Internal save failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun loadViaInternal(context: Context): ByteArray? {
        return runCatching {
            val file = internalFile(context)
            if (!file.isFile) return null
            file.readBytes()
        }.getOrNull()
    }

    private fun saveViaMediaStore(context: Context, payload: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val resolver = context.contentResolver
            findMediaUri(context)?.let { uri ->
                resolver.openOutputStream(uri, "wt")?.use { it.write(payload) }
                return@runCatching true
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/$DIR_NAME"
                )
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(payload) }
                ?: return@runCatching false
            true
        }.onFailure {
            Log.w(TAG, "MediaStore save failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun loadViaMediaStore(context: Context): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val uri = findMediaUri(context) ?: return null
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onFailure {
            Log.w(TAG, "MediaStore load failed: ${it.message}")
        }.getOrNull()
    }

    private fun findMediaUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(FILE_NAME, "%$DIR_NAME%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun saveViaPublicFile(payload: ByteArray): Boolean {
        return runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            FileOutputStream(File(dir, FILE_NAME)).use { it.write(payload) }
            true
        }.getOrDefault(false)
    }

    private fun loadViaPublicFile(): ByteArray? {
        return runCatching {
            val file = File(Environment.getExternalStorageDirectory(), "$DIR_NAME/$FILE_NAME")
            if (!file.isFile) return null
            file.readBytes()
        }.getOrNull()
    }

    private fun saveViaAppExternal(context: Context, payload: ByteArray): Boolean {
        return runCatching {
            val dir = context.getExternalFilesDir(null) ?: return false
            FileOutputStream(File(dir, FILE_NAME)).use { it.write(payload) }
            true
        }.getOrDefault(false)
    }

    private fun loadViaAppExternal(context: Context): ByteArray? {
        return runCatching {
            val file = File(context.getExternalFilesDir(null), FILE_NAME)
            if (!file.isFile) return null
            file.readBytes()
        }.getOrNull()
    }
}
