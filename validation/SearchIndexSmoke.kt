package com.drindexer.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

private class RowsCursor(private val rows: List<List<Any?>>) : Cursor {
    private var pos = -1
    override fun moveToFirst(): Boolean { pos = if (rows.isEmpty()) -1 else 0; return pos >= 0 }
    override fun moveToNext(): Boolean { pos++; return pos in rows.indices }
    private fun v(i: Int): Any? = rows[pos][i]
    override fun getInt(columnIndex: Int): Int = (v(columnIndex) as Number).toInt()
    override fun getLong(columnIndex: Int): Long = (v(columnIndex) as Number).toLong()
    override fun getDouble(columnIndex: Int): Double = (v(columnIndex) as Number).toDouble()
    override fun getString(columnIndex: Int): String? = v(columnIndex)?.toString()
    override fun isNull(columnIndex: Int): Boolean = v(columnIndex) == null
    override fun getType(columnIndex: Int): Int = when (v(columnIndex)) {
        null -> Cursor.FIELD_TYPE_NULL
        is Byte, is Short, is Int, is Long -> Cursor.FIELD_TYPE_INTEGER
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is String -> Cursor.FIELD_TYPE_STRING
        else -> Cursor.FIELD_TYPE_BLOB
    }
    override fun close() = Unit
}

private data class Row(
    val id: Long, val scan: Int, val root: Int?, val name: String, val folder: Int?,
    val type: String?, val sizeMb: Double, val bytes: Long, val modified: Long,
    val isFolder: Int
)

private class FakeDb(private val rows: List<Row>) : SQLiteDatabase() {
    private val cols = setOf("root_id", "folder_id", "size_bytes")
    override fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor {
        val q = sql.replace(Regex("\\s+"), " ").trim()
        return when {
            q.startsWith("SELECT COUNT(*) FROM files", ignoreCase = true) -> RowsCursor(listOf(listOf(rows.size)))
            q.startsWith("PRAGMA table_info", ignoreCase = true) -> RowsCursor(
                listOf("id", "scan_id", "root_id", "filename", "folder_id", "file_type", "size", "size_bytes", "date_modified", "is_folder")
                    .filter { it in cols || it in setOf("id", "scan_id", "filename", "file_type", "size", "date_modified", "is_folder") }
                    .mapIndexed { i, name -> listOf(i, name) }
            )
            q.equals("SELECT filename FROM files", ignoreCase = true) -> RowsCursor(rows.map { listOf(it.name) })
            q.startsWith("SELECT id,scan_id", ignoreCase = true) -> RowsCursor(rows.map {
                listOf(it.id, it.scan, it.root, it.name, it.folder, it.type, it.sizeMb, it.bytes, it.modified, it.isFolder)
            })
            else -> throw IllegalArgumentException("Unexpected SQL: $q")
        }
    }
}

private fun check(name: String, condition: Boolean) {
    if (!condition) error("FAIL: $name")
    println("PASS: $name")
}

fun main() {
    val rows = listOf(
        Row(1, 1, 101, "word1.word2-word3_ext", 10, "ext", 1.0, 1_048_576, 100, 0),
        Row(2, 2, 202, "إِسـلام_١٢٣.pdf", 20, "pdf", 2.0, 2_097_152, 200, 0),
        Row(3, 1, 101, "Movies", 30, null, 0.0, 0, 120, 1),
        Row(4, 1, 101, "alpha.txt", 40, "txt", 0.1, 100_000, 150, 0),
        Row(5, 1, 101, "zeta.txt", 10, "txt", 0.2, 200_000, 300, 0),
        Row(6, 2, 202, "beta.txt", 20, "txt", 0.3, 300_000, 50, 0)
    )
    val folders = mapOf(
        10 to "E:\\Archive\\Mixed",
        20 to "F:\\Arabic",
        30 to "E:\\Archive\\Movies",
        40 to "E:\\Archive\\special-gamma"
    )
    val scans = mapOf(1 to "Zeta Drive", 2 to "Alpha Drive")
    val index = SearchIndex()
    check("index loads", index.load(FakeDb(rows), folders, scans))
    check("entry count", index.entryCount == rows.size)

    fun search(q: String, f: SearchFilter = SearchFilter()): SearchResult = index.search(q, f, scans)

    check("mixed separators", search("word1 word2 word3 ext").items.single().id == 1L)
    check("Arabic tolerant", search("اسلام 123").items.single().id == 2L)
    check("path-only match included", search("special gamma").items.single().id == 4L)
    check("true union count", search("archive").totalCount == 4)

    val folderOnly = SearchFilter(includeFiles=false, includeFolders=true)
    check("folder-only filter", search("movies", folderOnly).items.map { it.id } == listOf(3L))

    val oneScan = SearchFilter(scanIds=setOf(1))
    check("selected scan scope", search("txt", oneScan).items.all { it.scanId == 1 } && search("txt", oneScan).totalCount == 2)

    val byDate = SearchFilter(sortField=SortField.DATE, sortOrder=SortOrder.DESC)
    check("Date Modified uses epoch", search("txt", byDate).items.map { it.id } == listOf(5L,4L,6L))

    val byScan = SearchFilter(sortField=SortField.SCAN, sortOrder=SortOrder.ASC)
    check("Scan sort uses real name", search("txt", byScan).items.first().id == 6L)

    val relevance = SearchFilter(sortField=SortField.RELEVANCE)
    check("relevance exact filename first", search("alpha", relevance).items.first().id == 4L)

    check("fast result carries archived path", search("alpha").items.first().filePath == folders[40])
    check("exact quote preserves separators", search("\"word1.word2\"").items.single().id == 1L)
    check("exact quote rejects alternate separator", search("\"word1 word2\"").totalCount == 0)

    val scoped = SearchFilter(includeFiles=false, includeFolders=true, scanIds=setOf(1, 2), sortField=SortField.SCAN)
    val restored = SearchFilter.fromBundle(scoped.toBundle())
    check("multi-scan Bundle round-trip", restored.scanIds == setOf(1, 2) && !restored.includeFiles && restored.includeFolders && restored.sortField == SortField.SCAN)
    val legacyBundle = android.os.Bundle().apply { putInt("scanId", 2) }
    check("legacy single-scan Bundle compatibility", SearchFilter.fromBundle(legacyBundle).scanIds == setOf(2))
    check("exact-byte size formatting", FileItem(99,1,filename="x",filePath=null,fileType="bin",size=0.0,sizeBytes=1_073_741_824,isFolder=false,scanName="s").getFormattedSize() == "1.00 GB")

    println("PASS: SearchIndex core smoke complete")
}
