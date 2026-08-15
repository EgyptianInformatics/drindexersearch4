package com.drindexer.search

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Manages dark/light theme preference and applies it app-wide.
 */
object ThemeHelper {

    private const val PREFS_NAME = "dr_indexer_theme"
    private const val KEY_THEME_MODE = "theme_mode"

    /** Theme modes matching AppCompatDelegate constants */
    const val MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
    const val MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES
    const val MODE_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the saved theme mode. Defaults to system.
     */
    fun getSavedMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_THEME_MODE, MODE_SYSTEM)
    }

    /**
     * Check if the current saved mode is dark.
     */
    fun isDarkMode(context: Context): Boolean {
        return getSavedMode(context) == MODE_DARK
    }

    /**
     * Apply saved theme mode. Call in Application.onCreate or Activity.onCreate
     * before setContentView.
     */
    fun applySavedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getSavedMode(context))
    }

    /**
     * Toggle between light and dark mode. Saves and applies immediately.
     * Returns the new mode.
     */
    fun toggleTheme(context: Context): Int {
        val current = getSavedMode(context)
        val newMode = if (current == MODE_DARK) MODE_LIGHT else MODE_DARK

        getPrefs(context).edit().putInt(KEY_THEME_MODE, newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)

        return newMode
    }

    /**
     * Set a specific theme mode.
     */
    fun setThemeMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
