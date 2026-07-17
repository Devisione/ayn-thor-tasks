package com.aynthor.taskswap.adb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persists Wireless ADB pairing identity outside app-private storage so it survives
 * uninstall / reinstall. Prefer `adb install -r` so SharedPreferences are kept;
 * this backup is the safety net when a full uninstall happens.
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

    fun save(
        context: Context,
        paired: Boolean,
        connectionPort: Int?,
        cert: ByteArray?,
        key: ByteArray?
    ) {
        val payload = JSONObject()
            .put("paired", paired)
            .put("connectionPort", connectionPort ?: -1)
            .put("cert", cert?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .put("key", key?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .toString()
            .toByteArray(Charsets.UTF_8)

        val okMedia = saveViaMediaStore(context, payload)
        val okFile = saveViaPublicFile(payload)
        val okApp = saveViaAppExternal(context, payload)
        Log.i(TAG, "ADB identity save media=$okMedia file=$okFile appExt=$okApp")
    }

    fun load(context: Context): Backup? {
        val bytes = loadViaMediaStore(context)
            ?: loadViaPublicFile()
            ?: loadViaAppExternal(context)
            ?: return null
        return runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            Backup(
                paired = json.optBoolean("paired", false),
                connectionPort = json.optInt("connectionPort", -1).takeIf { it > 0 },
                certPem = json.optString("cert").takeIf { it.isNotBlank() && it != "null" },
                keyPem = json.optString("key").takeIf { it.isNotBlank() && it != "null" }
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse ADB identity: ${it.message}")
        }.getOrNull()
    }

    fun decodePem(base64: String): ByteArray =
        Base64.decode(base64, Base64.NO_WRAP)

    private fun saveViaMediaStore(context: Context, payload: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val resolver = context.contentResolver
            // Update existing if present
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
