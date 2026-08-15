package com.drindexer.search

/**
 * File category definitions matching the desktop Dr_Indexer app.
 */
object FileCategory {
    
    // Category name to extensions mapping
    val CATEGORIES: Map<String, List<String>> = mapOf(
        "video" to listOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "mpeg", "mpg", "m4v", "3gp"),
        "audio" to listOf("mp3", "wav", "ogg", "flac", "aac", "wma", "m4a", "opus"),
        "document" to listOf("doc", "docx", "pdf", "txt", "odt", "xls", "xlsx", "ppt", "pptx", "rtf"),
        "image" to listOf("jpg", "jpeg", "png", "gif", "bmp", "tiff", "svg", "webp", "ico", "raw"),
        "archive" to listOf("zip", "rar", "7z", "tar", "gz", "iso", "bz2", "xz"),
        "code" to listOf("py", "js", "html", "css", "java", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "kt"),
        "executable" to listOf("exe", "msi", "bat", "sh", "dll", "app", "cmd", "ps1", "apk")
    )
    
    // Display names for categories
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        "all" to "All Files",
        "video" to "Videos",
        "audio" to "Audio",
        "document" to "Documents",
        "image" to "Images",
        "archive" to "Archives",
        "code" to "Code",
        "executable" to "Executables"
    )
    
    // Icons for categories (emoji)
    val ICONS: Map<String, String> = mapOf(
        "all" to "📁",
        "video" to "🎬",
        "audio" to "🎵",
        "document" to "📄",
        "image" to "🖼️",
        "archive" to "📦",
        "code" to "💻",
        "executable" to "⚙️"
    )
    
    fun getExtensionsForCategory(category: String): List<String> {
        return CATEGORIES[category] ?: emptyList()
    }
    
    fun getCategoryForExtension(extension: String): String? {
        val ext = extension.lowercase().trimStart('.')
        return CATEGORIES.entries.find { ext in it.value }?.key
    }
    
    fun getAllCategoryNames(): List<String> {
        return listOf("all") + CATEGORIES.keys.toList()
    }
}

/**
 * Predefined filter presets matching desktop app.
 */
data class FilterPreset(
    val name: String,
    val category: String,
    val minSizeMB: Float,
    val description: String
)

object FilterPresets {
    val PRESETS = listOf(
        FilterPreset("All Files", "all", 0f, "Show all files"),
        FilterPreset("Large Videos (>500MB)", "video", 500f, "Video files over 500MB"),
        FilterPreset("Large Files (>1GB)", "all", 1024f, "Any file over 1GB"),
        FilterPreset("Documents", "document", 0f, "All document files"),
        FilterPreset("Audio Files", "audio", 0f, "All audio files"),
        FilterPreset("Images", "image", 0f, "All image files"),
        FilterPreset("Archives", "archive", 0f, "All archive files"),
        FilterPreset("Large Audio (>10MB)", "audio", 10f, "Audio files over 10MB"),
        FilterPreset("HD Videos (>100MB)", "video", 100f, "Video files over 100MB"),
        FilterPreset("Code Files", "code", 0f, "Programming source files"),
        FilterPreset("Executables", "executable", 0f, "Executable files")
    )
    
    fun getPresetByName(name: String): FilterPreset? {
        return PRESETS.find { it.name == name }
    }
    
    fun getPresetNames(): List<String> {
        return PRESETS.map { it.name }
    }
}
