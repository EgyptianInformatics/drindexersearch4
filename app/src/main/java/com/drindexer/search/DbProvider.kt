package com.drindexer.search

import android.content.Context

/**
 * Process-wide DatabaseHelper singleton (v4.0; introduced in v3.3).
 *
 * Previously MainActivity, ScansActivity and FolderBrowserActivity each opened
 * their own DatabaseHelper, so the folder map (and on rotation, the entire
 * in-memory index!) was rebuilt per screen. Sharing one instance makes the
 * Scans/Browser screens open instantly, removes duplicate folder maps from
 * RAM, and lets the search index survive configuration changes.
 *
 * Lifecycle: created lazily against the application context; never closed by
 * activities. The index is still released under memory pressure via
 * MainActivity.onTrimMemory, and the OS reclaims everything on process death.
 */
object DbProvider {
    @Volatile
    private var instance: DatabaseHelper? = null

    fun get(context: Context): DatabaseHelper {
        return instance ?: synchronized(this) {
            instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
        }
    }
}
