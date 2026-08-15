package com.drindexer.search

import android.content.Context

/**
 * Recent search history (v3.0), persisted in SharedPreferences.
 *
 * Stores up to [MAX] distinct recent queries, most-recent first. Used to offer
 * quick re-search suggestions. Kept deliberately tiny and dependency-free.
 */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences("dr_indexer_history", Context.MODE_PRIVATE)

    companion object {
        private const val KEY = "recent_queries"
        private const val MAX = 15
        private const val SEP = "\n"
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.length < 3) return
        val current = get().toMutableList()
        // De-dupe case-insensitively, newest first.
        current.removeAll { it.equals(q, ignoreCase = true) }
        current.add(0, q)
        while (current.size > MAX) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY, current.joinToString(SEP)).apply()
    }

    fun get(): List<String> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).filter { it.isNotBlank() }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }
}
