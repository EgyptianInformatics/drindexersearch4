package com.drindexer.search

import android.os.Bundle

/**
 * Sort options for search results.
 */
enum class SortField(val displayName: String, val dbColumn: String) {
    RELEVANCE("Relevance", ""),
    NAME("Name", "filename"),
    SIZE("Size", "size"),
    DATE("Date Modified", "date_modified"),
    EXTENSION("Extension", "file_type"),
    SCAN("Scan Name", "scan_name")
}

enum class SortOrder(val displayName: String, val sqlOrder: String) {
    ASC("Ascending", "ASC"),
    DESC("Descending", "DESC")
}

/**
 * Combined filter and sort configuration.
 * Supports Bundle serialization for surviving configuration changes.
 */
data class SearchFilter(
    var includeFiles: Boolean = true,
    var includeFolders: Boolean = true,
    var category: String = "all",
    var minSizeMB: Float = 0f,
    var maxSizeMB: Float = Float.MAX_VALUE,
    var extensionFilter: String = "",
    var scanIds: Set<Int> = emptySet(),
    var sortField: SortField = SortField.RELEVANCE,
    var sortOrder: SortOrder = SortOrder.ASC,
    var limit: Int = 500
) {
    fun hasActiveFilters(): Boolean {
        return category != "all" ||
               minSizeMB > 0f ||
               maxSizeMB < Float.MAX_VALUE ||
               extensionFilter.isNotBlank() ||
               scanIds.isNotEmpty() ||
               !includeFiles ||
               !includeFolders
    }

    fun getFilterSummary(): String {
        val parts = mutableListOf<String>()
        if (category != "all") {
            parts.add(FileCategory.DISPLAY_NAMES[category] ?: category)
        }
        if (minSizeMB > 0f) parts.add(">${formatSize(minSizeMB)}")
        if (maxSizeMB < Float.MAX_VALUE) parts.add("<${formatSize(maxSizeMB)}")
        if (extensionFilter.isNotBlank()) {
            parts.add(".${extensionFilter.split(",").first().trim()}")
        }
        if (includeFolders && !includeFiles) parts.add("Folders only")
        if (includeFiles && !includeFolders) parts.add("Files only")
        return if (parts.isEmpty()) "No filters" else parts.joinToString(" • ")
    }

    fun getSortSummary(): String {
        val arrow = if (sortOrder == SortOrder.DESC) "↓" else "↑"
        return "${sortField.displayName} $arrow"
    }

    private fun formatSize(mb: Float): String {
        return when {
            mb >= 1024 -> "${(mb / 1024).toInt()}GB"
            mb >= 1 -> "${mb.toInt()}MB"
            else -> "${(mb * 1024).toInt()}KB"
        }
    }

    fun applyPreset(preset: FilterPreset) {
        category = preset.category
        minSizeMB = preset.minSizeMB
        maxSizeMB = Float.MAX_VALUE
        extensionFilter = ""
    }

    fun reset() {
        includeFiles = true
        includeFolders = true
        category = "all"
        minSizeMB = 0f
        maxSizeMB = Float.MAX_VALUE
        extensionFilter = ""
        scanIds = emptySet()
        sortField = SortField.RELEVANCE
        sortOrder = SortOrder.ASC
    }

    fun copy(): SearchFilter {
        return SearchFilter(
            includeFiles, includeFolders, category, minSizeMB, maxSizeMB,
            extensionFilter, scanIds.toSet(), sortField, sortOrder, limit
        )
    }

    // ─── Bundle serialization for rotation safety ────────────────

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("includeFiles", includeFiles)
        putBoolean("includeFolders", includeFolders)
        putString("category", category)
        putFloat("minSizeMB", minSizeMB)
        putFloat("maxSizeMB", maxSizeMB)
        putString("extensionFilter", extensionFilter)
        putIntArray("scanIds", scanIds.toIntArray())
        putInt("sortField", sortField.ordinal)
        putInt("sortOrder", sortOrder.ordinal)
        putInt("limit", limit)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SearchFilter {
            val restoredScanIds = bundle.getIntArray("scanIds")?.toSet()
                ?: bundle.getInt("scanId", -1).takeIf { it >= 0 }?.let { setOf(it) }
                ?: emptySet()
            return SearchFilter(
                includeFiles = bundle.getBoolean("includeFiles", true),
                includeFolders = bundle.getBoolean("includeFolders", true),
                category = bundle.getString("category", "all") ?: "all",
                minSizeMB = bundle.getFloat("minSizeMB", 0f),
                maxSizeMB = bundle.getFloat("maxSizeMB", Float.MAX_VALUE),
                extensionFilter = bundle.getString("extensionFilter", "") ?: "",
                scanIds = restoredScanIds,
                sortField = SortField.values().getOrElse(bundle.getInt("sortField", SortField.RELEVANCE.ordinal)) { SortField.RELEVANCE },
                sortOrder = SortOrder.values().getOrElse(bundle.getInt("sortOrder", SortOrder.ASC.ordinal)) { SortOrder.ASC },
                limit = bundle.getInt("limit", 500)
            )
        }
    }
}
