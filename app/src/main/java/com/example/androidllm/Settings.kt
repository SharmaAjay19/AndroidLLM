package com.example.androidllm

import android.content.Context

/** Simple SharedPreferences-backed app settings. */
object Settings {
    private const val PREFS = "androidllm_settings"
    private const val KEY_WORKSPACE = "workspace_dir"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user-chosen absolute path for the agent's workspace, or null to use the default. */
    fun getWorkspacePath(context: Context): String? =
        prefs(context).getString(KEY_WORKSPACE, null)?.takeIf { it.isNotBlank() }

    fun setWorkspacePath(context: Context, path: String?) {
        prefs(context).edit().putString(KEY_WORKSPACE, path?.trim()).apply()
    }
}
