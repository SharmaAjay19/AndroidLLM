package com.example.androidllm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Decides where the GGUF model is stored.
 *
 * Preferred: a public external folder (`/sdcard/AndroidLLM/`) which **survives app
 * uninstall / reinstall**, so upgrading the app never re-downloads the ~2.5 GB model.
 * This requires "All files access" (MANAGE_EXTERNAL_STORAGE) — acceptable for a
 * sideloaded app. If we don't have it, we fall back to internal storage (filesDir),
 * which survives normal in-place updates but not a full uninstall.
 */
object ModelStorage {

    const val MODEL_FILE_NAME = "qwen3-4b-q4_k_m.gguf"
    private const val PUBLIC_DIR = "AndroidLLM"

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun externalFile(): File =
        File(File(Environment.getExternalStorageDirectory(), PUBLIC_DIR), MODEL_FILE_NAME)

    private fun internalFile(context: Context): File =
        File(context.filesDir, MODEL_FILE_NAME)

    /** Where a new download should be written (external if we can, else internal). */
    fun downloadTarget(context: Context): File {
        if (hasAllFilesAccess()) {
            val dir = externalFile().parentFile
            if (dir != null && (dir.exists() || dir.mkdirs())) return externalFile()
        }
        return internalFile(context)
    }

    /** An already-downloaded valid model (external preferred), or null if none exists. */
    fun existingModel(context: Context): File? {
        val ext = externalFile()
        if (Downloader.isValidGguf(ext)) return ext
        val int = internalFile(context)
        if (Downloader.isValidGguf(int)) return int
        return null
    }

    /**
     * If we have external access and a model exists only in internal storage, move it to
     * the external folder so it survives future uninstalls. Returns the external file if a
     * move happened, otherwise null. Safe to call on a background thread (does file IO).
     */
    fun migrateToExternalIfPossible(context: Context): File? {
        if (!hasAllFilesAccess()) return null
        val ext = externalFile()
        if (Downloader.isValidGguf(ext)) return null
        val int = internalFile(context)
        if (!Downloader.isValidGguf(int)) return null

        val dir = ext.parentFile ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        return if (int.renameTo(ext)) {
            ext
        } else {
            // Cross-volume rename can fail; fall back to copy + delete.
            int.inputStream().use { input -> ext.outputStream().use { input.copyTo(it) } }
            if (Downloader.isValidGguf(ext)) {
                int.delete()
                ext
            } else {
                ext.delete()
                null
            }
        }
    }

    /** Opens the system screen to grant "All files access" for this app. */
    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
