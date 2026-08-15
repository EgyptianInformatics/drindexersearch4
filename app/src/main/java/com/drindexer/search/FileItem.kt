package com.drindexer.search

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a file or folder from the Dr_Indexer database.
 *
 * v4.0 (normalized/mobile-v3 schema):
 *   - filePath is resolved from folderId/rootId via the cached archived-path map;
 *     fast-index results carry it immediately so location is visible without an
 *     extra details query.
 *   - dateModifiedEpoch holds the raw epoch-seconds value (nullable). The
 *     mobile export stores dates as INTEGER epoch to save space; we format
 *     on display. Legacy flat databases store a pre-formatted string instead,
 *     carried in dateModifiedText.
 */
data class FileItem(
    val id: Long,
    val scanId: Int,
    val rootId: Int? = null,
    val filename: String,
    val filePath: String?,
    val fileType: String?,
    val size: Double,  // compatibility MB value used by existing filters
    val sizeBytes: Long? = null,
    val folderId: Int? = null,
    val dateModifiedEpoch: Long? = null,
    val dateModifiedText: String? = null,
    val isFolder: Boolean,
    val scanName: String
) {
    /** Human-readable modified date, from either epoch or a legacy text value. */
    val dateModified: String?
        get() = when {
            dateModifiedText != null -> dateModifiedText
            dateModifiedEpoch != null && dateModifiedEpoch > 0 ->
                formatEpoch(dateModifiedEpoch)
            else -> null
        }

    fun getFormattedSize(): String {
        if (isFolder) return "Folder"
        val bytes = sizeBytes ?: (size * 1024.0 * 1024.0).toLong()
        return when {
            bytes >= 1024L * 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun getExtension(): String {
        return fileType?.lowercase() ?: filename.substringAfterLast('.', "").lowercase()
    }

    fun getDisplayExtension(): String {
        return getExtension().uppercase()
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun formatEpoch(epochSeconds: Long): String {
            return try {
                dateFormat.format(Date(epochSeconds * 1000L))
            } catch (e: Exception) {
                epochSeconds.toString()
            }
        }
    }
}
