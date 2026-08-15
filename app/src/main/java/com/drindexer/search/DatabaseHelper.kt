package com.drindexer.search

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Database helper for DrIndexerSearch v4.0.
 *
 * MOBILE SCHEMA SUPPORT:
 *   v3: scans + scan_roots + hierarchical folders + exact size_bytes
 *   v2: normalized folders(id,path) mobile export
 *   v1: legacy flat file_path databases
 *   compatible raw desktop databases use a schema-aware legacy path.
 *
 * v4 adds staged/validated atomic import, heap-aware Fast/Disk selection,
 * root-aware direct browsing, a normalized local FTS5 disk index, and common
 * tolerant-search semantics shared with the in-memory SearchIndex.
 */
class DatabaseHelper(private val context: Context) {

    private var database: SQLiteDatabase? = null
    private var databaseName: String = ""

    /** Compact in-memory filename index */
    val searchIndex = SearchIndex()

    // --- Detected schema shape ---
    private var schemaVersion: Int = 1          // 3 = hierarchy+roots, 2 = normalized, 1 = legacy flat
    private var hasFilePathColumn: Boolean = false
    private var hasFolderIdColumn: Boolean = false
    private var hasScanNameColumn: Boolean = false
    private var hasRootIdColumn: Boolean = false
    private var hasSizeBytesColumn: Boolean = false
    private var hasCorruptedScanColumn: Boolean = false
    private var hasFolderHierarchy: Boolean = false
    private var dateIsEpoch: Boolean = false

    /** scan_id -> name cache */
    var scanNameCache: Map<Int, String> = emptyMap()
        private set

    /** folder_id -> path map (normalized schema only). Small: a few thousand entries. */
    private var folderPathMap: Map<Int, String> = emptyMap()

    private var folderDisplayPathMap: Map<Int, String> = emptyMap()

    /** root_id -> archived absolute root path (schema v3). */
    private var rootPathMap: Map<Int, String> = emptyMap()

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "dr_indexer_prefs", Context.MODE_PRIVATE
    )

    companion object {
        private const val PREF_DB_NAME = "database_name"
        private const val PREF_DB_IMPORTED = "database_imported"
        private const val PREF_DB_FILE_COUNT = "database_file_count"
        private const val PREF_INDEX_MODE = "index_mode"   // "auto" | "fast" | "disk"
        private const val DB_FILENAME = "dr_indexer_data.db"
        private const val IMPORT_FILENAME = "dr_indexer_data.importing.db"
        private const val BACKUP_FILENAME = "dr_indexer_data.previous.db"
        const val SUPPORTED_MOBILE_SCHEMA = 3

        // gzip magic bytes: 0x1f 0x8b
        private const val GZIP_MAGIC_1 = 0x1f
        private const val GZIP_MAGIC_2 = 0x8b
    }

    // --- State ---

    fun hasSavedDatabase(): Boolean {
        val dbFile = File(context.filesDir, DB_FILENAME)
        val previous = File(context.filesDir, BACKUP_FILENAME)
        // A .previous file is a crash-recovery candidate. Do not make the UI
        // report "no database" merely because the process died mid-swap.
        return (dbFile.exists() || previous.exists()) && prefs.getBoolean(PREF_DB_IMPORTED, false)
    }

    /**
     * Crash-safe import recovery. A successful import deletes .previous only
     * at the commit point. Therefore its presence on the next process start
     * means the previous import never committed and the known-good DB wins.
     */
    private fun recoverInterruptedImportIfNeeded(): File {
        val target = File(context.filesDir, DB_FILENAME)
        val previous = File(context.filesDir, BACKUP_FILENAME)
        if (previous.exists()) {
            if (target.exists()) {
                target.delete()
                cleanupSidecars(target)
            }
            if (!previous.renameTo(target)) {
                // Keep the recovery candidate intact; callers will fail closed
                // rather than silently deleting it.
                return previous
            }
            cleanupSidecars(previous)
        }
        return target
    }

    fun isLoaded(): Boolean = database != null
    fun getDatabaseName(): String = databaseName
    fun getSchemaVersion(): Int = schemaVersion

    /** Public, read-only health check used by the v4 Database screen. */
    fun verifyCurrentDatabase(): Boolean {
        val db = database ?: return false
        return verifyDatabase(db) && quickCheck(db)
    }

    fun getLocalDatabaseSizeBytes(): Long {
        val base = File(context.filesDir, DB_FILENAME)
        return base.length() + File(base.absolutePath + "-wal").length() + File(base.absolutePath + "-shm").length()
    }

    fun getMetaValue(key: String): String? {
        val db = database ?: return null
        if (!tableExists(db, "meta")) return null
        return try {
            db.rawQuery("SELECT value FROM meta WHERE key=? LIMIT 1", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    /** Truthful label for the currently active search backend. */
    fun getActiveSearchEngineLabel(): String {
        if (searchIndex.isLoaded) {
            return "Fast RAM index (~${searchIndex.estimatedMemoryMB()} MB)"
        }
        val db = database ?: return "No search engine"
        return when {
            ftsUsesTrigram(db) -> "Disk / FTS5 trigram"
            hasFts(db) -> "Disk compatibility mode (tolerant fallback may be slower)"
            else -> "Disk search (index not built)"
        }
    }

    fun isSearchEngineReady(): Boolean {
        if (searchIndex.isLoaded) return true
        val db = database ?: return false
        return hasNormalizedFtsSchema(db)
    }

    // --- PRAGMA tuning (requires READWRITE) ---

    private fun applyPragmas(db: SQLiteDatabase) {
        try {
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA mmap_size = 268435456")   // 256MB
            db.execSQL("PRAGMA cache_size = -20000")      // 20MB
            db.execSQL("PRAGMA temp_store = MEMORY")
            db.execSQL("PRAGMA synchronous = NORMAL")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == column) return true
                }
                false
            }
        } catch (e: Exception) { false }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        // Accept both tables and views. The v18.8 desktop database exposes
        // `files` as a VIEW (over files_data/folders/mime_types), not a table,
        // so a type='table'-only check wrongly rejected it on import.
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }
        } catch (e: Exception) { false }
    }

    private fun isView(db: SQLiteDatabase, name: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='view' AND name=?",
                arrayOf(name)
            ).use { it.moveToFirst() }
        } catch (e: Exception) { false }
    }

    private fun detectFeatures(db: SQLiteDatabase) {
        // schema_version from meta table (normalized export writes '2')
        schemaVersion = try {
            if (tableExists(db, "meta")) {
                db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use {
                    if (it.moveToFirst()) it.getString(0)?.toIntOrNull() ?: 1 else 1
                }
            } else 1
        } catch (e: Exception) { 1 }

        hasFilePathColumn = columnExists(db, "files", "file_path")
        hasFolderIdColumn = columnExists(db, "files", "folder_id")
        hasScanNameColumn = columnExists(db, "files", "scan_name")
        hasRootIdColumn = columnExists(db, "files", "root_id")
        hasSizeBytesColumn = columnExists(db, "files", "size_bytes")
        hasCorruptedScanColumn = columnExists(db, "scans", "corrupted")
        hasFolderHierarchy = schemaVersion >= 3 && tableExists(db, "folders") &&
                columnExists(db, "folders", "parent_id") && columnExists(db, "folders", "scan_id")

        // Normalized schemas store epoch seconds.
        dateIsEpoch = schemaVersion >= 2 || (hasFolderIdColumn && tableExists(db, "folders"))
    }

    // --- Load / Import ---

    fun loadSavedDatabase(
        onIndexProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Boolean {
        return try {
            val dbFile = recoverInterruptedImportIfNeeded()
            if (!dbFile.exists() || dbFile.name != DB_FILENAME) return false

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )

            if (!verifyDatabase(db)) {
                db.close()
                clearSavedDatabase()
                return false
            }

            database = db
            applyPragmas(db)
            detectFeatures(db)
            databaseName = prefs.getString(PREF_DB_NAME, "Database") ?: "Database"
            buildScanCache(db)
            buildRootMap(db)
            buildFolderMap(db)

            // v3.3 (B3): honor the persisted index-mode choice.
            val mode = effectiveIndexMode(getTotalFileCount())
            if (mode == "fast") {
                searchIndex.load(db, folderDisplayPathMap, scanNameCache, onIndexProgress)
            }
            warmUpMmap(db)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            clearSavedDatabase()
            false
        }
    }

    /**
     * Lightweight open for the Browse Scans screen: opens the already-imported
     * on-disk database and reads only schema + scan/folder caches. Skips the
     * expensive in-memory filename index (not needed to list scans).
     * Call from IO thread.
     */
    fun loadScansOnly(buildFolders: Boolean = true): Boolean {
        return try {
            val dbFile = recoverInterruptedImportIfNeeded()
            if (!dbFile.exists() || dbFile.name != DB_FILENAME) return false
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            if (!verifyDatabase(db)) {
                db.close()
                return false
            }
            database = db
            applyPragmas(db)
            detectFeatures(db)
            buildScanCache(db)
            buildRootMap(db)
            if (buildFolders) buildFolderMap(db)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Result of the prepare phase of an import.
     */
    data class ImportPreparation(
        val success: Boolean,
        val fileCount: Int,
        val isView: Boolean,
        val schemaVersion: Int,
        val errorMessage: String? = null
    )

    /** Memory-aware automatic index policy. Uses the app heap limit, not total
     * device RAM, and reserves headroom for UI/SQLite/GC during the build. */
    private fun indexWarningThreshold(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val heapLimit = minOf(
                Runtime.getRuntime().maxMemory(),
                am.memoryClass.toLong() * 1024L * 1024L
            )
            val budgetBytes = (heapLimit * 0.45).toLong()
            (budgetBytes / 190L).toInt().coerceIn(250_000, 6_000_000)
        } catch (e: Exception) {
            1_000_000
        }
    }

    fun isLargeForIndex(fileCount: Int): Boolean = fileCount > indexWarningThreshold()

    fun getIndexMode(): String = prefs.getString(PREF_INDEX_MODE, "auto") ?: "auto"

    fun setIndexMode(mode: String) {
        val safe = if (mode in setOf("auto", "fast", "disk")) mode else "auto"
        prefs.edit().putString(PREF_INDEX_MODE, safe).apply()
    }

    fun effectiveIndexMode(fileCount: Int = getTotalFileCount()): String {
        return when (val mode = getIndexMode()) {
            "fast", "disk" -> mode
            else -> if (isLargeForIndex(fileCount)) "disk" else "fast"
        }
    }

    private fun readSchemaVersion(db: SQLiteDatabase): Int {
        return try {
            if (tableExists(db, "meta")) {
                db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use {
                    if (it.moveToFirst()) it.getString(0)?.toIntOrNull() ?: 1 else 1
                }
            } else 1
        } catch (_: Exception) { 1 }
    }

    private fun quickCheck(db: SQLiteDatabase): Boolean {
        return try {
            db.rawQuery("PRAGMA quick_check", null).use {
                it.moveToFirst() && it.getString(0).equals("ok", ignoreCase = true)
            }
        } catch (_: Exception) { false }
    }

    private fun cleanupSidecars(base: File) {
        File(base.absolutePath + "-wal").delete()
        File(base.absolutePath + "-shm").delete()
    }

    private fun copyImportToTemp(uri: Uri, tempFile: File) {
        tempFile.delete()
        cleanupSidecars(tempFile)

        val descriptorLength = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        } catch (_: Exception) { -1L } ?: -1L
        val availableBefore = StatFs(context.filesDir.absolutePath).availableBytes
        val minimumReserve = 32L * 1024L * 1024L
        if (descriptorLength > 0 && availableBefore < descriptorLength + minimumReserve) {
            throw IOException("Not enough free storage to import this database safely.")
        }

        val raw = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file.")
        val buffered = BufferedInputStream(raw, 1024 * 1024)
        buffered.mark(2)
        val b1 = buffered.read(); val b2 = buffered.read(); buffered.reset()
        val source: InputStream = if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) {
            GZIPInputStream(buffered, 1024 * 1024)
        } else buffered

        try {
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var sinceCheck = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sinceCheck += read
                    if (sinceCheck >= 8L * 1024L * 1024L) {
                        sinceCheck = 0L
                        if (StatFs(context.filesDir.absolutePath).availableBytes < minimumReserve) {
                            throw IOException("Storage became too low while decompressing the database.")
                        }
                    }
                }
                output.fd.sync()
            }
        } finally {
            source.close()
        }
    }

    /**
     * Safe import transaction:
     *  1) copy/decompress into a sibling temporary file,
     *  2) verify supported schema + PRAGMA quick_check while the old DB is intact,
     *  3) atomically swap only after validation,
     *  4) restore the previous DB if opening the replacement fails.
     */
    fun prepareImport(uri: Uri): ImportPreparation {
        val target = File(context.filesDir, DB_FILENAME)
        val temp = File(context.filesDir, IMPORT_FILENAME)
        val previous = File(context.filesDir, BACKUP_FILENAME)
        var candidateSchema = 1
        var count = 0
        var filesIsView = false
        try {
            copyImportToTemp(uri, temp)

            val candidate = SQLiteDatabase.openDatabase(
                temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            try {
                candidateSchema = readSchemaVersion(candidate)
                if (candidateSchema > SUPPORTED_MOBILE_SCHEMA) {
                    return ImportPreparation(
                        false, 0, false, candidateSchema,
                        "This database uses mobile schema $candidateSchema; this app supports up to $SUPPORTED_MOBILE_SCHEMA."
                    )
                }
                if (!verifyDatabase(candidate) || !quickCheck(candidate)) {
                    return ImportPreparation(false, 0, false, candidateSchema, "The selected database failed validation.")
                }
                count = candidate.rawQuery("SELECT COUNT(*) FROM files", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                filesIsView = isView(candidate, "files")
            } finally {
                candidate.close()
            }

            // Candidate is proven readable before the currently working DB is touched.
            close()
            cleanupSidecars(target)
            previous.delete(); cleanupSidecars(previous)
            val hadPrevious = target.exists()
            if (hadPrevious && !target.renameTo(previous)) {
                throw IOException("Could not preserve the current database before replacement.")
            }
            if (!temp.renameTo(target)) {
                if (hadPrevious) previous.renameTo(target)
                throw IOException("Could not publish the imported database.")
            }

            try {
                val db = SQLiteDatabase.openDatabase(
                    target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                )
                if (!verifyDatabase(db)) {
                    db.close()
                    throw IOException("Imported database could not be reopened after validation.")
                }
                database = db
                applyPragmas(db)
                detectFeatures(db)
                buildScanCache(db)
                buildRootMap(db)
                buildFolderMap(db)
                databaseName = uri.lastPathSegment?.substringAfterLast("/")
                    ?.removeSuffix(".gz")?.removeSuffix(".db") ?: "Database"

                // Commit point: once the rollback copy is removed the new DB
                // is authoritative. Preference metadata is deliberately written
                // after that point; a crash can at worst leave an old display
                // name/count, never an ambiguous database payload.
                previous.delete(); cleanupSidecars(previous)
                prefs.edit()
                    .putBoolean(PREF_DB_IMPORTED, true)
                    .putString(PREF_DB_NAME, databaseName)
                    .putInt(PREF_DB_FILE_COUNT, count)
                    .apply()
            } catch (e: Exception) {
                close()
                target.delete(); cleanupSidecars(target)
                if (previous.exists()) previous.renameTo(target)
                // Best-effort reopen of the known-good previous DB.
                if (target.exists()) loadSavedDatabase()
                throw e
            }

            return ImportPreparation(true, count, filesIsView, schemaVersion)
        } catch (e: Exception) {
            e.printStackTrace()
            // If publication failed after the current DB was closed but before
            // the replacement became usable, make the restored/untouched
            // known-good target immediately usable again in this process.
            if (database == null && target.exists() && prefs.getBoolean(PREF_DB_IMPORTED, false)) {
                try { loadSavedDatabase() } catch (_: Exception) {}
            }
            return ImportPreparation(false, 0, false, candidateSchema, e.message ?: "Import failed")
        } finally {
            temp.delete(); cleanupSidecars(temp)
        }
    }

    fun rebuildSearchIndex(
        onIndexProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Boolean {
        val db = database ?: return false
        return try {
            if (effectiveIndexMode() == "fast") {
                val ok = searchIndex.load(db, folderDisplayPathMap, scanNameCache, onIndexProgress)
                if (!ok) searchIndex.release()
            } else {
                searchIndex.release()
            }
            if (!searchIndex.isLoaded) ensureFtsIndex()
            warmUpMmap(db)
            true
        } catch (e: OutOfMemoryError) {
            searchIndex.release()
            ensureFtsIndex()
            warmUpMmap(db)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }



    // --- Folder browsing (v3.0) -------------------------------------------

    /**
     * One entry in the drill-down browser: either a sub-folder or a file.
     */
    data class BrowseEntry(
        val name: String,
        val fullPath: String,
        val isFolder: Boolean,
        val fileItem: FileItem?,
        val folderId: Int? = null,
        val rootId: Int? = null
    )

    data class ScanRootSummary(
        val id: Int,
        val scanId: Int,
        val order: Int,
        val path: String,
        val volumeLabel: String,
        val volumeSerial: String,
        val filesystem: String
    )

    fun getScanRoots(scanId: Int): List<ScanRootSummary> {
        val db = database ?: return emptyList()
        if (!tableExists(db, "scan_roots")) return emptyList()
        val out = ArrayList<ScanRootSummary>()
        try {
            db.rawQuery(
                """SELECT id, scan_id, root_order, path,
                          COALESCE(volume_label,''), COALESCE(volume_serial,''), COALESCE(filesystem,'')
                   FROM scan_roots WHERE scan_id=? ORDER BY root_order,id""",
                arrayOf(scanId.toString())
            ).use {
                while (it.moveToNext()) out.add(
                    ScanRootSummary(it.getInt(0), it.getInt(1), it.getInt(2),
                        it.getString(3) ?: "", it.getString(4) ?: "",
                        it.getString(5) ?: "", it.getString(6) ?: "")
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }

    fun supportsDirectHierarchy(): Boolean = hasFolderHierarchy

    /** v3 direct O(children) browsing using folders(scan_id,root_id,parent_id). */
    fun getFolderChildrenDirect(
        scanId: Int, rootId: Int, parentFolderId: Int?
    ): List<BrowseEntry> {
        val db = database ?: return emptyList()
        if (!hasFolderHierarchy) return emptyList()
        val out = ArrayList<BrowseEntry>()
        try {
            val folderWhere = if (parentFolderId == null) "parent_id IS NULL" else "parent_id=?"
            val folderArgs = if (parentFolderId == null)
                arrayOf(scanId.toString(), rootId.toString())
            else arrayOf(scanId.toString(), rootId.toString(), parentFolderId.toString())
            db.rawQuery(
                """SELECT id,name,path FROM folders
                   WHERE scan_id=? AND root_id=? AND $folderWhere
                   ORDER BY name COLLATE NOCASE""", folderArgs
            ).use {
                while (it.moveToNext()) {
                    val fid = it.getInt(0)
                    val name = it.getString(1) ?: ""
                    val display = resolvePath(fid, rootId) ?: it.getString(2) ?: name
                    out.add(BrowseEntry(name, display, true, null, fid, rootId))
                }
            }

            val fileWhere = if (parentFolderId == null) "folder_id IS NULL" else "folder_id=?"
            val fileArgs = if (parentFolderId == null)
                arrayOf(scanId.toString(), rootId.toString())
            else arrayOf(scanId.toString(), rootId.toString(), parentFolderId.toString())
            val sizeExpr = if (hasSizeBytesColumn) "COALESCE(size_bytes,0)/1048576.0" else "COALESCE(size,0)"
            val bytesExpr = if (hasSizeBytesColumn) "COALESCE(size_bytes,0)" else "CAST(ROUND(COALESCE(size,0)*1048576.0) AS INTEGER)"
            db.rawQuery(
                """SELECT id,scan_id,root_id,filename,folder_id,file_type,$sizeExpr,$bytesExpr,date_modified
                   FROM files WHERE scan_id=? AND root_id=? AND is_folder=0 AND $fileWhere
                   ORDER BY filename COLLATE NOCASE""", fileArgs
            ).use {
                while (it.moveToNext()) {
                    val folderId = if (it.isNull(4)) null else it.getInt(4)
                    val item = FileItem(
                        id=it.getLong(0), scanId=it.getInt(1), rootId=it.getInt(2),
                        filename=it.getString(3) ?: "", filePath=resolvePath(folderId, rootId),
                        fileType=it.getString(5), size=it.getDouble(6), sizeBytes=it.getLong(7),
                        folderId=folderId, dateModifiedEpoch=if (it.isNull(8)) null else it.getLong(8),
                        isFolder=false, scanName=scanNameCache[it.getInt(1)] ?: "Unknown"
                    )
                    out.add(BrowseEntry(item.filename, item.filePath ?: rootPathMap[rootId].orEmpty(), false, item, folderId, rootId))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }

    /** Recursive tolerant search limited to one v3 root/folder subtree. */
    fun searchWithinFolderDirect(
        scanId: Int, rootId: Int, parentFolderId: Int?, query: String, limit: Int = 500
    ): List<BrowseEntry> {
        val db = database ?: return emptyList()
        if (!hasFolderHierarchy) return emptyList()
        val spec = SearchNormalizer.parse(query)
        if (!spec.isUsable) return emptyList()
        val out = ArrayList<BrowseEntry>()
        try {
            val folderSql: String
            val folderArgs: Array<String>
            if (parentFolderId == null) {
                folderSql = "SELECT id,name,path FROM folders WHERE scan_id=? AND root_id=? ORDER BY name COLLATE NOCASE"
                folderArgs = arrayOf(scanId.toString(), rootId.toString())
            } else {
                folderSql = """WITH RECURSIVE sub(id) AS (
                    SELECT ? UNION ALL
                    SELECT f.id FROM folders f JOIN sub s ON f.parent_id=s.id
                    WHERE f.scan_id=? AND f.root_id=?
                ) SELECT id,name,path FROM folders WHERE id IN (SELECT id FROM sub WHERE id<>?)
                ORDER BY name COLLATE NOCASE"""
                folderArgs = arrayOf(parentFolderId.toString(), scanId.toString(), rootId.toString(), parentFolderId.toString())
            }
            db.rawQuery(folderSql, folderArgs).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id=c.getInt(0); val name=c.getString(1) ?: ""; val path=c.getString(2) ?: ""
                    val display=resolvePath(id,rootId) ?: path
                    if (SearchNormalizer.matches(name,spec) || SearchNormalizer.matches(display,spec))
                        out.add(BrowseEntry(name,display,true,null,id,rootId))
                }
            }

            if (out.size < limit) {
                val sizeExpr = if (hasSizeBytesColumn) "COALESCE(size_bytes,0)/1048576.0" else "COALESCE(size,0)"
                val bytesExpr = if (hasSizeBytesColumn) "COALESCE(size_bytes,0)" else "CAST(ROUND(COALESCE(size,0)*1048576.0) AS INTEGER)"
                val fileSql: String
                val args: Array<String>
                if (parentFolderId == null) {
                    fileSql = """SELECT id,scan_id,root_id,filename,folder_id,file_type,$sizeExpr,$bytesExpr,date_modified
                        FROM files WHERE scan_id=? AND root_id=? AND is_folder=0"""
                    args = arrayOf(scanId.toString(),rootId.toString())
                } else {
                    fileSql = """WITH RECURSIVE sub(id) AS (
                        SELECT ? UNION ALL
                        SELECT f.id FROM folders f JOIN sub s ON f.parent_id=s.id
                        WHERE f.scan_id=? AND f.root_id=?
                    ) SELECT id,scan_id,root_id,filename,folder_id,file_type,$sizeExpr,$bytesExpr,date_modified
                      FROM files WHERE scan_id=? AND root_id=? AND is_folder=0
                        AND folder_id IN (SELECT id FROM sub)"""
                    args = arrayOf(parentFolderId.toString(),scanId.toString(),rootId.toString(),scanId.toString(),rootId.toString())
                }
                db.rawQuery(fileSql,args).use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        val fid=if(c.isNull(4)) null else c.getInt(4)
                        val path=resolvePath(fid,rootId)
                        val name=c.getString(3) ?: ""
                        if (!SearchNormalizer.matches(name,spec) && !SearchNormalizer.matches(path,spec)) continue
                        val item=FileItem(
                            id=c.getLong(0),scanId=c.getInt(1),rootId=rootId,filename=name,filePath=path,
                            fileType=c.getString(5),size=c.getDouble(6),sizeBytes=c.getLong(7),folderId=fid,
                            dateModifiedEpoch=if(c.isNull(8)) null else c.getLong(8),isFolder=false,
                            scanName=scanNameCache[scanId] ?: "Unknown")
                        out.add(BrowseEntry(name,path ?: rootPathMap[rootId].orEmpty(),false,item,fid,rootId))
                    }
                }
            }
            out.sortWith(compareByDescending<BrowseEntry>{it.isFolder}.thenBy{it.name.lowercase()})
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }

    private fun pathSeparator(sample: String): String =
        if (sample.contains("\\")) "\\" else "/"

    /**
     * v3.2: majority-vote separator detection over ALL paths. The old
     * first-path heuristic could misdetect when a bare root like "D:" happened
     * to come first (HashMap iteration order), which flattened the whole tree
     * into one level.
     */
    private fun detectSeparator(paths: List<String>): String {
        var bs = 0L; var fs = 0L
        for (p in paths) {
            for (ch in p) {
                if (ch == '\\') bs++ else if (ch == '/') fs++
            }
        }
        return if (bs >= fs) "\\" else "/"
    }

    private fun pathSegments(p: String, sep: String): List<String> {
        if (sep == "/" && p.startsWith("/")) {
            val rest = p.substring(1).split(sep).filter { it.isNotEmpty() }
            return listOf("/") + rest
        }
        return p.split(sep).filter { it.isNotEmpty() }
    }

    private fun joinSegments(segs: List<String>, sep: String): String {
        if (segs.isEmpty()) return ""
        if (sep == "/" && segs.first() == "/") {
            return "/" + segs.drop(1).joinToString(sep)
        }
        return segs.joinToString(sep)
    }

    /**
     * Compute the scan's root: the longest common path prefix (segment-wise,
     * case-insensitive) of all its folder paths. Browsing starts here, so
     * opening a scan of D:\Users\me\Documents shows Documents' contents
     * immediately instead of forcing taps through D: -> Users -> me.
     * Returns null when there is no common root (e.g. multi-drive scan).
     */
    fun getScanRootPath(scanId: Int): String? {
        val db = database ?: return null
        return try {
            val paths = folderPathsForScan(db, scanId)
            if (paths.isEmpty()) return null
            val sep = detectSeparator(paths)
            var common = pathSegments(paths.first(), sep)
            for (idx in 1 until paths.size) {
                val segs = pathSegments(paths[idx], sep)
                var i = 0
                while (i < common.size && i < segs.size &&
                    common[i].equals(segs[i], ignoreCase = true)) i++
                common = common.subList(0, i)
                if (common.isEmpty()) return null
            }
            val root = joinSegments(common, sep)
            root.ifEmpty { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Return the immediate children (sub-folders and files) directly contained
     * in [parentPath] within [scanId].
     *
     * Works for BOTH schemas:
     *   - Normalized: folder paths live in the in-memory folderPathMap; files
     *     reference folder_id. We find folders whose parent is parentPath and
     *     files whose folder path == parentPath.
     *   - Legacy flat / desktop view: file_path holds the folder path per row.
     *     We derive sub-folders from distinct file_path values that sit under
     *     parentPath, and files whose file_path == parentPath.
     *
     * When [parentPath] is null we return the scan's root folders (the shortest
     * distinct path prefixes).
     */
    fun getFolderChildren(scanId: Int, parentPath: String?): List<BrowseEntry> {
        val db = database ?: return emptyList()
        return try {
            if (hasFolderIdColumn) {
                browseNormalized(db, scanId, parentPath)
            } else {
                browseFlat(db, scanId, parentPath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Distinct folder paths that belong to a scan. */
    private fun folderPathsForScan(db: SQLiteDatabase, scanId: Int): List<String> {
        val paths = ArrayList<String>()
        if (hasFolderIdColumn) {
            // folders table has no scan_id in the mobile export; derive the set
            // of folder ids used by this scan's files, then map to paths.
            val ids = HashSet<Int>()
            db.rawQuery(
                "SELECT DISTINCT folder_id FROM files WHERE scan_id = ? AND folder_id IS NOT NULL",
                arrayOf(scanId.toString())
            ).use {
                while (it.moveToNext()) ids.add(it.getInt(0))
            }
            for (id in ids) folderPathMap[id]?.let { paths.add(it) }
            paths.sort()  // deterministic order (HashMap iteration is arbitrary)
        } else {
            db.rawQuery(
                "SELECT DISTINCT file_path FROM files WHERE scan_id = ? AND file_path IS NOT NULL AND file_path <> ''",
                arrayOf(scanId.toString())
            ).use {
                while (it.moveToNext()) it.getString(0)?.let { p -> paths.add(p) }
            }
            paths.sort()
        }
        return paths
    }

    /**
     * From a flat list of folder paths, compute the immediate sub-folders of
     * [parentPath] (or the roots if parentPath is null).
     */
    private fun immediateSubfolders(allPaths: List<String>, parentPath: String?): List<String> {
        if (allPaths.isEmpty()) return emptyList()
        val sep = detectSeparator(allPaths)
        val result = LinkedHashSet<String>()

        if (parentPath == null) {
            // No common root (multi-drive scan): list top-level segments.
            for (p in allPaths) {
                val segs = pathSegments(p, sep)
                if (segs.isNotEmpty()) result.add(segs.first())
            }
            return result.sorted()
        }

        val prefix = if (parentPath.endsWith(sep)) parentPath else parentPath + sep
        val prefixLower = prefix.lowercase()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (p in allPaths) {
            if (p.equals(parentPath, ignoreCase = true)) continue
            if (p.lowercase().startsWith(prefixLower)) {
                val rest = p.substring(prefix.length)
                val nextSeg = rest.substringBefore(sep)
                if (nextSeg.isNotEmpty()) {
                    val full = prefix + nextSeg
                    if (seen.add(full.lowercase())) out.add(full)
                }
            }
        }
        return out.sorted()
    }

    private fun browseNormalized(
        db: SQLiteDatabase, scanId: Int, parentPath: String?
    ): List<BrowseEntry> {
        val allPaths = folderPathsForScan(db, scanId)
        val entries = ArrayList<BrowseEntry>()

        val subs = immediateSubfolders(allPaths, parentPath)
        val sep = if (allPaths.isNotEmpty()) detectSeparator(allPaths) else "\\"
        for (folderPath in subs) {
            entries.add(
                BrowseEntry(
                    name = folderPath.substringAfterLast(sep).ifEmpty { folderPath },
                    fullPath = folderPath,
                    isFolder = true,
                    fileItem = null
                )
            )
        }

        // Files directly in parentPath (only when we're inside a folder).
        if (parentPath != null) {
            val matchingFolderIds = folderPathMap.entries
                .filter { it.value.equals(parentPath, ignoreCase = true) }
                .map { it.key }
            // v3.3 (B8): empty folders exist only as is_folder=1 rows (their
            // own path never enters the folders table). Surface them too.
            if (matchingFolderIds.isNotEmpty()) {
                val seen = HashSet<String>()
                for (e in entries) if (e.isFolder) seen.add(e.name.lowercase())
                val ph = matchingFolderIds.joinToString(",") { "?" }
                val prm = ArrayList<String>()
                matchingFolderIds.forEach { prm.add(it.toString()) }
                prm.add(scanId.toString())
                db.rawQuery(
                    """SELECT filename FROM files
                       WHERE folder_id IN ($ph) AND scan_id = ? AND is_folder = 1
                       ORDER BY filename COLLATE NOCASE""",
                    prm.toTypedArray()
                ).use {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: continue
                        if (seen.add(name.lowercase())) {
                            entries.add(BrowseEntry(name, parentPath + sep + name, true, null))
                        }
                    }
                }
                entries.sortWith(
                    compareByDescending<BrowseEntry> { it.isFolder }
                        .thenBy { it.name.lowercase() }
                )
            }
            if (matchingFolderIds.isNotEmpty()) {
                val placeholders = matchingFolderIds.joinToString(",") { "?" }
                val params = ArrayList<String>()
                matchingFolderIds.forEach { params.add(it.toString()) }
                params.add(scanId.toString())
                val sql = """
                    SELECT f.id, f.scan_id, f.filename, f.folder_id, f.file_type,
                           COALESCE(f.size,0), f.date_modified, f.is_folder
                    FROM files f
                    WHERE f.folder_id IN ($placeholders) AND f.scan_id = ?
                      AND f.is_folder = 0
                    ORDER BY f.filename COLLATE NOCASE
                """.trimIndent()
                db.rawQuery(sql, params.toTypedArray()).use {
                    while (it.moveToNext()) {
                        val fId = if (it.isNull(3)) null else it.getInt(3)
                        val item = FileItem(
                            id = it.getLong(0), scanId = it.getInt(1),
                            filename = it.getString(2) ?: "",
                            filePath = parentPath,
                            fileType = it.getString(4),
                            size = it.getDouble(5),
                            folderId = fId,
                            dateModifiedEpoch = if (it.isNull(6)) null else it.getLong(6),
                            isFolder = false,
                            scanName = scanNameCache[it.getInt(1)] ?: "Unknown"
                        )
                        entries.add(BrowseEntry(item.filename, parentPath, false, item))
                    }
                }
            }
        }
        return entries
    }

    private fun browseFlat(
        db: SQLiteDatabase, scanId: Int, parentPath: String?
    ): List<BrowseEntry> {
        val allPaths = folderPathsForScan(db, scanId)
        val entries = ArrayList<BrowseEntry>()

        val subs = immediateSubfolders(allPaths, parentPath)
        val sep = if (allPaths.isNotEmpty()) detectSeparator(allPaths) else "\\"
        for (folderPath in subs) {
            entries.add(
                BrowseEntry(
                    name = folderPath.substringAfterLast(sep).ifEmpty { folderPath },
                    fullPath = folderPath,
                    isFolder = true,
                    fileItem = null
                )
            )
        }

        if (parentPath != null) {
            // v3.3 (B8): surface empty folders stored only as is_folder=1 rows.
            val seenFolders = HashSet<String>()
            for (e in entries) if (e.isFolder) seenFolders.add(e.name.lowercase())
            db.rawQuery(
                """SELECT filename FROM files
                   WHERE scan_id = ? AND file_path = ? COLLATE NOCASE AND is_folder = 1
                   ORDER BY filename COLLATE NOCASE""",
                arrayOf(scanId.toString(), parentPath)
            ).use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    if (seenFolders.add(name.lowercase())) {
                        entries.add(BrowseEntry(name, parentPath + sep + name, true, null))
                    }
                }
            }
            entries.sortWith(
                compareByDescending<BrowseEntry> { it.isFolder }
                    .thenBy { it.name.lowercase() }
            )

            val scanCol = if (hasScanNameColumn) "f.scan_name" else "f.scan_id"
            val sql = """
                SELECT f.id, f.scan_id, f.filename, f.file_path, f.file_type,
                       COALESCE(f.size,0), f.date_modified, f.is_folder, $scanCol
                FROM files f
                WHERE f.scan_id = ? AND f.file_path = ? COLLATE NOCASE AND f.is_folder = 0
                ORDER BY f.filename COLLATE NOCASE
            """.trimIndent()
            db.rawQuery(sql, arrayOf(scanId.toString(), parentPath)).use {
                while (it.moveToNext()) {
                    val sId = it.getInt(1)
                    val item = FileItem(
                        id = it.getLong(0), scanId = sId,
                        filename = it.getString(2) ?: "",
                        filePath = it.getString(3),
                        fileType = it.getString(4),
                        size = it.getDouble(5),
                        dateModifiedText = it.getString(6),
                        isFolder = false,
                        scanName = resolveScanName(sId, if (hasScanNameColumn) it.getString(8) else null)
                    )
                    entries.add(BrowseEntry(item.filename, parentPath, false, item))
                }
            }
        }
        return entries
    }

    // ----------------------------------------------------------------------

    private fun warmUpMmap(db: SQLiteDatabase) {
        try {
            db.rawQuery("SELECT COUNT(*) FROM files", null).use { it.moveToFirst() }
            db.rawQuery("SELECT COUNT(*) FROM scans", null).use { it.moveToFirst() }
        } catch (e: Exception) { /* non-critical */ }
    }

    // --- FTS5 disk search index (v3.2) ------------------------------------

    /**
     * True when the local copy of the database has an FTS5 filename index.
     * Built on-device into the app's private copy — the original file (mobile
     * export or raw desktop DB) is never modified, and the desktop app needs
     * no changes.
     */
    private fun hasFts(db: SQLiteDatabase): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='files_fts'",
                null
            ).use { it.moveToFirst() }
        } catch (e: Exception) { false }
    }

    /** v3.3 stored an FTS table with a raw `filename` column. v4 search uses
     * `filename_norm`; the old table is derived data and can be rebuilt safely. */
    private fun hasNormalizedFtsSchema(db: SQLiteDatabase): Boolean {
        if (!hasFts(db)) return false
        return try {
            db.rawQuery("PRAGMA table_info(files_fts)", null).use { c ->
                var found = false
                while (c.moveToNext()) {
                    if ((c.getString(1) ?: "").equals("filename_norm", ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                found
            }
        } catch (_: Exception) { false }
    }

    /** Build an on-device normalized filename FTS index for low-memory mode.
     * Prefer FTS5 trigram because it preserves substring semantics; older
     * Android SQLite builds fall back to unicode61 and the search path then
     * uses the slower correctness-first SQL normalization. */
    fun ensureFtsIndex(onProgress: ((msg: String) -> Unit)? = null): Boolean {
        val db = database ?: return false
        if (hasFts(db)) {
            if (hasNormalizedFtsSchema(db)) return true
            // Upgrade the disposable v3.x FTS shape in place.
            try { db.execSQL("DROP TABLE IF EXISTS files_fts") } catch (_: Exception) {}
        }
        return try {
            onProgress?.invoke("Building disk search index...")
            var trigram = true
            try {
                db.execSQL("CREATE VIRTUAL TABLE files_fts USING fts5(filename_norm, content='', tokenize='trigram')")
            } catch (_: Exception) {
                trigram = false
                db.execSQL("CREATE VIRTUAL TABLE files_fts USING fts5(filename_norm, content='', tokenize='unicode61')")
            }
            db.beginTransaction()
            try {
                val stmt = db.compileStatement("INSERT INTO files_fts(rowid, filename_norm) VALUES (?, ?)")
                var n = 0
                db.rawQuery("SELECT id, filename FROM files", null).use { c ->
                    while (c.moveToNext()) {
                        stmt.clearBindings()
                        stmt.bindLong(1, c.getLong(0))
                        stmt.bindString(2, SearchNormalizer.normalize(c.getString(1) ?: ""))
                        stmt.executeInsert()
                        n++
                        if (n % 100_000 == 0) onProgress?.invoke("Building disk search index... ${String.format("%,d", n)}")
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            onProgress?.invoke(
                if (trigram) "Disk substring index ready"
                else "Compatibility disk index ready — tolerant substring search may be slower on this Android SQLite"
            )
            true
        } catch (e: Exception) {
            try { db.execSQL("DROP TABLE IF EXISTS files_fts") } catch (_: Exception) {}
            e.printStackTrace(); false
        }
    }

    private fun ftsUsesTrigram(db: SQLiteDatabase): Boolean {
        if (!hasFts(db)) return false
        return try {
            db.rawQuery("SELECT sql FROM sqlite_master WHERE name='files_fts'", null).use {
                it.moveToFirst() && (it.getString(0) ?: "").contains("trigram", ignoreCase = true)
            }
        } catch (_: Exception) { false }
    }

    private fun ftsAndQuery(tokens: List<String>): String =
        tokens.joinToString(" AND ") { "\"${it.replace("\"", "\"\"")}\"" }

    // --- Caches ---

    private fun buildScanCache(db: SQLiteDatabase) {
        val cache = mutableMapOf<Int, String>()
        try {
            db.rawQuery("SELECT id, name FROM scans", null).use {
                while (it.moveToNext()) {
                    cache[it.getInt(0)] = it.getString(1) ?: "Unknown"
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        scanNameCache = cache
    }

    private fun joinArchivedPath(root: String?, relative: String?): String? {
        val rel = relative?.trim('/')?.trim('\\') ?: ""
        if (root.isNullOrBlank()) return relative
        if (rel.isBlank()) return root
        val sep = if (root.contains("\\")) "\\" else "/"
        return root.trimEnd('\\', '/') + sep + rel.replace("/", sep).replace("\\", sep)
    }

    private fun buildRootMap(db: SQLiteDatabase) {
        if (!tableExists(db, "scan_roots")) {
            rootPathMap = emptyMap()
            return
        }
        val map = HashMap<Int, String>()
        try {
            db.rawQuery("SELECT id, path FROM scan_roots", null).use {
                while (it.moveToNext()) map[it.getInt(0)] = it.getString(1) ?: ""
            }
        } catch (e: Exception) { e.printStackTrace() }
        rootPathMap = map
    }

    /** Load folder hierarchy/path data. In v3 each folder id is an actual
     * directory node; in v2 it is the legacy path lookup id. */
    private fun buildFolderMap(db: SQLiteDatabase) {
        if (!hasFolderIdColumn || !tableExists(db, "folders")) {
            folderPathMap = emptyMap()
            folderDisplayPathMap = emptyMap()
            return
        }
        val relative = HashMap<Int, String>(4096)
        val display = HashMap<Int, String>(4096)
        try {
            val sql = if (hasFolderHierarchy && columnExists(db, "folders", "root_id")) {
                "SELECT id, path, root_id FROM folders"
            } else {
                "SELECT id, path, NULL AS root_id FROM folders"
            }
            db.rawQuery(sql, null).use {
                while (it.moveToNext()) {
                    val id = it.getInt(0)
                    val path = it.getString(1) ?: ""
                    val rootId = if (it.isNull(2)) null else it.getInt(2)
                    relative[id] = path
                    display[id] = joinArchivedPath(rootId?.let(rootPathMap::get), path) ?: path
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        folderPathMap = relative
        folderDisplayPathMap = display
    }

    private fun resolvePath(folderId: Int?, rootId: Int? = null): String? {
        if (folderId != null) return folderDisplayPathMap[folderId] ?: folderPathMap[folderId]
        return rootId?.let(rootPathMap::get)
    }

    // --- Scans (browsing + summaries) ---

    fun getScans(): List<Pair<Int, String>> {
        val db = database ?: return emptyList()
        val scans = mutableListOf<Pair<Int, String>>()
        try {
            db.rawQuery(
                "SELECT id, name FROM scans" +
                    (if (hasCorruptedScanColumn) " WHERE COALESCE(corrupted,0)=0" else "") +
                    " ORDER BY scan_date DESC", null
            ).use {
                while (it.moveToNext()) {
                    scans.add(Pair(it.getInt(0), it.getString(1) ?: "Unknown"))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return scans
    }

    /**
     * Rich scan summaries for the browse screen. Reads the pre-computed
     * file_count / total_size the desktop stores in the scans table, so no
     * heavy aggregation is needed. Falls back to COUNT(*) if those columns
     * are absent or zero.
     */
    fun getScanSummaries(): List<ScanSummary> {
        val db = database ?: return emptyList()
        val out = mutableListOf<ScanSummary>()
        val hasCount = columnExists(db, "scans", "file_count")
        val hasSize = columnExists(db, "scans", "total_size")
        val hasDate = columnExists(db, "scans", "scan_date")
        val hasTags = columnExists(db, "scans", "tags")
        val hasNotes = columnExists(db, "scans", "notes")

        val cols = StringBuilder("id, name")
        cols.append(if (hasDate) ", scan_date" else ", '' AS scan_date")
        cols.append(if (hasCount) ", file_count" else ", 0 AS file_count")
        cols.append(if (hasSize) ", total_size" else ", 0 AS total_size")
        cols.append(if (hasTags) ", tags" else ", '' AS tags")
        cols.append(if (hasNotes) ", notes" else ", '' AS notes")

        try {
            val validScansWhere = if (hasCorruptedScanColumn) " WHERE COALESCE(corrupted,0)=0" else ""
            db.rawQuery("SELECT $cols FROM scans$validScansWhere ORDER BY scan_date DESC", null).use {
                while (it.moveToNext()) {
                    val id = it.getInt(0)
                    var count = it.getLong(3)
                    if (count <= 0) {
                        // Fallback: count files for this scan.
                        count = db.rawQuery(
                            "SELECT COUNT(*) FROM files WHERE scan_id = ?",
                            arrayOf(id.toString())
                        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                    }
                    out.add(
                        ScanSummary(
                            id = id,
                            name = it.getString(1) ?: "Unknown",
                            scanDate = it.getString(2) ?: "",
                            fileCount = count,
                            totalSizeMB = it.getDouble(4),
                            tags = it.getString(5) ?: "",
                            notes = it.getString(6) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return out
    }

    // --- Search ---

    fun searchFiles(
        query: String,
        filter: SearchFilter,
        isActive: (() -> Boolean)? = null
    ): SearchResult {
        val spec = SearchNormalizer.parse(query)
        if (!spec.isUsable) return SearchResult(emptyList(), 0)

        // v4 Fast index already matches BOTH filename and archived path, so
        // there is no second SQL append stage and totalCount is the true union.
        if (searchIndex.isLoaded) {
            return searchIndex.search(query, filter, scanNameCache, isActive)
        }
        return searchWithSql(query, filter)
    }

    private fun searchWithSql(query: String, filter: SearchFilter): SearchResult {
        val db = database ?: return SearchResult(emptyList(), 0)
        return searchDiskTolerant(db, query, filter)
    }

    /** SQL fallback with the same user-visible tolerant semantics as Fast mode.
     * It is intentionally slower than the RAM index, but it does not silently
     * change dots/dashes/Arabic matching rules. */
    private fun searchDiskTolerant(
        db: SQLiteDatabase, query: String, filter: SearchFilter
    ): SearchResult {
        val spec = SearchNormalizer.parse(query)
        if (!spec.isUsable) return SearchResult(emptyList(), 0)
        return try {
            val (filterWhere, filterParams) = buildFilterConditions(filter)
            val params = ArrayList<String>()
            val asciiSafeTokens = !spec.exact && spec.tokens.all { token ->
                token.isNotEmpty() && token.all { ch -> ch.code < 128 && ch.isLetterOrDigit() }
            }
            // For ordinary Latin/digit tokens, raw LOWER()+LIKE has identical
            // token semantics while avoiding the large nested normalization
            // expression. Arabic/variant-sensitive fallback keeps full SQL
            // normalization; modern builds normally use the trigram FTS path.
            val matchFilenameExpr = if (asciiSafeTokens) "LOWER(f.filename)" else normalizedSqlExpr("f.filename")
            val scoreFilenameExpr = normalizedSqlExpr("f.filename")
            val canUseTrigram = !spec.exact && spec.tokens.all { it.length >= 3 } && ftsUsesTrigram(db)
            val nameCondition = when {
                spec.exact -> {
                    // INSTR keeps quoted search truly literal: '%' and '_' are
                    // characters, not LIKE wildcards.
                    params.add(spec.exactText)
                    "INSTR(LOWER(f.filename), LOWER(?)) > 0"
                }
                canUseTrigram -> {
                    params.add(ftsAndQuery(spec.tokens))
                    "f.id IN (SELECT rowid FROM files_fts WHERE files_fts MATCH ?)"
                }
                else -> spec.tokens.joinToString(" AND ") { token ->
                    params.add("%$token%")
                    "$matchFilenameExpr LIKE ?"
                }
            }

            val pathCondition: String? = when {
                hasFolderIdColumn -> {
                    val matching = folderDisplayPathMap.entries
                        .asSequence()
                        .filter { (_, path) -> SearchNormalizer.matches(path, spec) }
                        .map { it.key }.toList()
                    if (matching.isEmpty()) null else {
                        matching.forEach { params.add(it.toString()) }
                        "f.folder_id IN (${matching.joinToString(",") { "?" }})"
                    }
                }
                hasFilePathColumn -> {
                    val pathExpr = if (asciiSafeTokens) "LOWER(f.file_path)" else normalizedSqlExpr("f.file_path")
                    if (spec.exact) {
                        params.add(spec.exactText)
                        "INSTR(LOWER(f.file_path), LOWER(?)) > 0"
                    } else {
                        spec.tokens.joinToString(" AND ") { token ->
                            params.add("%$token%")
                            "$pathExpr LIKE ?"
                        }
                    }
                }
                else -> null
            }

            val where = ArrayList<String>()
            where.add(if (pathCondition != null) "(($nameCondition) OR ($pathCondition))" else "($nameCondition)")
            if (filterWhere.isNotEmpty()) {
                where.add(filterWhere)
                params.addAll(filterParams)
            }
            val whereClause = where.joinToString(" AND ")

            val selectCols = if (hasFolderIdColumn) {
                val root = if (hasRootIdColumn) "f.root_id" else "NULL"
                "f.id,f.scan_id,$root,f.filename,f.folder_id,f.file_type,COALESCE(f.size,0)," +
                    (if (hasSizeBytesColumn) "COALESCE(f.size_bytes,0)" else "CAST(ROUND(COALESCE(f.size,0)*1048576.0) AS INTEGER)") +
                    ",f.date_modified,f.is_folder"
            } else {
                val scanCol = if (hasScanNameColumn) "f.scan_name" else "f.scan_id"
                "f.id,f.scan_id,f.filename,f.file_path,f.file_type,COALESCE(f.size,0),f.date_modified,f.is_folder,$scanCol"
            }

            val resultParams = params.toMutableList()
            val orderClause = if (filter.sortField == SortField.RELEVANCE) {
                val score = if (spec.exact) {
                    resultParams.add(spec.exactText)
                    resultParams.add(spec.exactText)
                    resultParams.add(spec.exactText)
                    "CASE WHEN LOWER(f.filename)=LOWER(?) THEN 0 " +
                        "WHEN INSTR(LOWER(f.filename), LOWER(?))=1 THEN 1 " +
                        "WHEN INSTR(LOWER(f.filename), LOWER(?))>0 THEN 2 ELSE 10 END"
                } else {
                    resultParams.add(spec.normalized)
                    resultParams.add(spec.normalized + "%")
                    resultParams.add("%" + spec.normalized + "%")
                    val allTokens = spec.tokens.joinToString(" AND ") { token ->
                        resultParams.add("%$token%")
                        "$scoreFilenameExpr LIKE ?"
                    }
                    "CASE WHEN $scoreFilenameExpr=? THEN 0 WHEN $scoreFilenameExpr LIKE ? THEN 1 " +
                        "WHEN $scoreFilenameExpr LIKE ? THEN 2 WHEN ($allTokens) THEN 3 ELSE 10 END"
                }
                "ORDER BY $score ASC, f.filename COLLATE NOCASE ASC"
            } else {
                val orderColumn = if (filter.sortField == SortField.SCAN) "s.name COLLATE NOCASE" else getOrderColumn(filter)
                "ORDER BY f.is_folder DESC, $orderColumn ${filter.sortOrder.sqlOrder}"
            }
            resultParams.add(filter.limit.toString())
            val sql = "SELECT $selectCols FROM files f LEFT JOIN scans s ON s.id=f.scan_id WHERE $whereClause $orderClause LIMIT ?"
            val items = readFileRowsV4(db, sql, resultParams.toTypedArray())
            val total = db.rawQuery(
                "SELECT COUNT(*) FROM files f WHERE $whereClause", params.toTypedArray()
            ).use { if (it.moveToFirst()) it.getInt(0) else items.size }
            SearchResult(items, total)
        } catch (e: Exception) {
            e.printStackTrace(); SearchResult(emptyList(), 0)
        }
    }

    private fun normalizedSqlExpr(column: String): String {
        var expr = "LOWER($column)"
        val replacements = listOf(
            "أ" to "ا", "إ" to "ا", "آ" to "ا", "ٱ" to "ا",
            "ى" to "ي", "ی" to "ي", "ک" to "ك", "ـ" to "",
            "ً" to "", "ٌ" to "", "ٍ" to "", "َ" to "", "ُ" to "", "ِ" to "",
            "ّ" to "", "ْ" to "", "ٰ" to "", "ٔ" to "", "ٕ" to "",
            "٠" to "0", "١" to "1", "٢" to "2", "٣" to "3", "٤" to "4",
            "٥" to "5", "٦" to "6", "٧" to "7", "٨" to "8", "٩" to "9",
            "۰" to "0", "۱" to "1", "۲" to "2", "۳" to "3", "۴" to "4",
            "۵" to "5", "۶" to "6", "۷" to "7", "۸" to "8", "۹" to "9",
            "." to " ", "_" to " ", "-" to " ", "–" to " ", "—" to " ",
            "," to " ", "،" to " ", ";" to " ", "؛" to " ", ":" to " ",
            "(" to " ", ")" to " ", "[" to " ", "]" to " ", "{" to " ", "}" to " ",
            "/" to " ", "\\" to " ", "+" to " ", "=" to " "
        )
        for ((from, to) in replacements) {
            val safeFrom = from.replace("'", "''")
            val safeTo = to.replace("'", "''")
            expr = "REPLACE($expr,'$safeFrom','$safeTo')"
        }
        repeat(3) { expr = "REPLACE($expr,'  ',' ')" }
        return "TRIM($expr)"
    }

    /**
     * Get full file details by ID (for the detail view when a result is tapped).
     */
    fun getFileDetails(fileId: Long): FileItem? {
        val db = database ?: return null
        return try {
            if (hasFolderIdColumn) {
                val root = if (hasRootIdColumn) "f.root_id" else "NULL"
                val bytes = if (hasSizeBytesColumn) "COALESCE(f.size_bytes,0)" else "CAST(ROUND(COALESCE(f.size,0)*1048576.0) AS INTEGER)"
                val sql = """SELECT f.id,f.scan_id,$root,f.filename,f.folder_id,f.file_type,
                    COALESCE(f.size,0),$bytes,f.date_modified,f.is_folder
                    FROM files f WHERE f.id=? LIMIT 1""".trimIndent()
                readFileRowsV4(db, sql, arrayOf(fileId.toString())).firstOrNull()
            } else {
                val scanCol = if (hasScanNameColumn) "f.scan_name" else "f.scan_id"
                val sql = """SELECT f.id,f.scan_id,f.filename,f.file_path,f.file_type,
                    COALESCE(f.size,0),f.date_modified,f.is_folder,$scanCol
                    FROM files f WHERE f.id=? LIMIT 1""".trimIndent()
                readFileRowsV4(db, sql, arrayOf(fileId.toString())).firstOrNull()
            }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // --- Helpers ---

    private fun resolveScanName(scanId: Int, fromColumn: String?): String {
        return fromColumn ?: scanNameCache[scanId] ?: "Unknown"
    }

    private fun readFileRowsV4(
        db: SQLiteDatabase, sql: String, args: Array<String>
    ): MutableList<FileItem> {
        val items = mutableListOf<FileItem>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                if (hasFolderIdColumn) {
                    val scanId = c.getInt(1)
                    val rootId = if (c.isNull(2)) null else c.getInt(2)
                    val folderId = if (c.isNull(4)) null else c.getInt(4)
                    items.add(FileItem(
                        id=c.getLong(0), scanId=scanId, rootId=rootId, filename=c.getString(3) ?: "",
                        filePath=resolvePath(folderId, rootId), fileType=c.getString(5),
                        size=c.getDouble(6), sizeBytes=c.getLong(7), folderId=folderId,
                        dateModifiedEpoch=if (c.isNull(8)) null else c.getLong(8),
                        isFolder=c.getInt(9)==1, scanName=scanNameCache[scanId] ?: "Unknown"
                    ))
                } else {
                    val scanId=c.getInt(1)
                    items.add(FileItem(
                        id=c.getLong(0), scanId=scanId, filename=c.getString(2) ?: "",
                        filePath=c.getString(3), fileType=c.getString(4), size=c.getDouble(5),
                        sizeBytes=(c.getDouble(5)*1048576.0).toLong(),
                        dateModifiedText=c.getString(6), isFolder=c.getInt(7)==1,
                        scanName=resolveScanName(scanId, if (hasScanNameColumn) c.getString(8) else null)
                    ))
                }
            }
        }
        return items
    }

    private fun buildFilterConditions(filter: SearchFilter): Pair<String, List<String>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        // Desktop databases can retain hidden/incomplete scans while an atomic
        // capture/update is in progress. Mobile Export v3 omits them, but raw
        // desktop DB compatibility must not make them searchable as valid data.
        if (hasCorruptedScanColumn) {
            conditions.add("f.scan_id IN (SELECT id FROM scans WHERE COALESCE(corrupted,0)=0)")
        }

        when {
            filter.includeFiles && filter.includeFolders -> {}
            filter.includeFiles -> conditions.add("f.is_folder = 0")
            filter.includeFolders -> conditions.add("f.is_folder = 1")
            else -> conditions.add("1 = 0")
        }

        if (filter.category != "all") {
            val extensions = FileCategory.getExtensionsForCategory(filter.category)
            if (extensions.isNotEmpty()) {
                conditions.add("LOWER(f.file_type) IN (${extensions.joinToString(",") { "?" }})")
                params.addAll(extensions.map { it.lowercase() })
            }
        }

        if (filter.minSizeMB > 0f) {
            conditions.add("f.size >= ?")
            params.add(filter.minSizeMB.toString())
        }
        if (filter.maxSizeMB < Float.MAX_VALUE) {
            conditions.add("f.size <= ?")
            params.add(filter.maxSizeMB.toString())
        }

        if (filter.extensionFilter.isNotBlank()) {
            val exts = filter.extensionFilter.split(",")
                .map { it.trim().lowercase().trimStart('.') }
                .filter { it.isNotEmpty() }
            if (exts.isNotEmpty()) {
                conditions.add("LOWER(f.file_type) IN (${exts.joinToString(",") { "?" }})")
                params.addAll(exts)
            }
        }

        if (filter.scanIds.isNotEmpty()) {
            conditions.add("f.scan_id IN (${filter.scanIds.joinToString(",") { "?" }})")
            params.addAll(filter.scanIds.map { it.toString() })
        }

        return Pair(
            if (conditions.isEmpty()) "" else conditions.joinToString(" AND "),
            params
        )
    }

    private fun getOrderColumn(filter: SearchFilter): String {
        return when (filter.sortField) {
            SortField.RELEVANCE -> "f.filename COLLATE NOCASE"
            SortField.NAME -> "f.filename COLLATE NOCASE"
            SortField.SIZE -> "f.size"
            SortField.DATE -> "f.date_modified"
            SortField.EXTENSION -> "f.file_type COLLATE NOCASE"
            SortField.SCAN -> if (hasScanNameColumn) "f.scan_name" else "f.scan_id"
        }
    }

    fun getTotalFileCount(): Int {
        val db = database ?: return 0
        return try {
            val validWhere = if (hasCorruptedScanColumn) {
                " WHERE scan_id IN (SELECT id FROM scans WHERE COALESCE(corrupted,0)=0)"
            } else ""
            db.rawQuery("SELECT COUNT(*) FROM files$validWhere", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) { 0 }
    }

    fun releaseIndex() {
        searchIndex.release()
    }

    /**
     * Accept either the normalized (files+folders+scans) or the legacy flat
     * (files+scans) schema. Require at minimum the scans and files tables.
     */
    private fun verifyDatabase(db: SQLiteDatabase): Boolean {
        return try {
            val version = readSchemaVersion(db)
            val hasScans = tableExists(db, "scans")
            val hasFiles = tableExists(db, "files")
            hasScans && hasFiles && version <= SUPPORTED_MOBILE_SCHEMA
        } catch (e: Exception) { false }
    }

    fun clearSavedDatabase() {
        close()
        val dbFile = File(context.filesDir, DB_FILENAME)
        val importing = File(context.filesDir, IMPORT_FILENAME)
        val previous = File(context.filesDir, BACKUP_FILENAME)
        for (file in listOf(dbFile, importing, previous)) {
            file.delete()
            cleanupSidecars(file)
        }
        prefs.edit()
            .putBoolean(PREF_DB_IMPORTED, false)
            .remove(PREF_DB_NAME)
            .remove(PREF_DB_FILE_COUNT)
            .remove(PREF_INDEX_MODE)
            .apply()
    }

    fun close() {
        searchIndex.release()
        database?.close()
        database = null
        scanNameCache = emptyMap()
        folderPathMap = emptyMap()
        folderDisplayPathMap = emptyMap()
        rootPathMap = emptyMap()
        hasFilePathColumn = false
        hasFolderIdColumn = false
        hasScanNameColumn = false
        hasRootIdColumn = false
        hasSizeBytesColumn = false
        hasCorruptedScanColumn = false
        hasFolderHierarchy = false
        dateIsEpoch = false
        schemaVersion = 1
    }
}

/**
 * Per-scan summary for the browse screen.
 */
data class ScanSummary(
    val id: Int,
    val name: String,
    val scanDate: String,
    val fileCount: Long,
    val totalSizeMB: Double,
    val tags: String,
    val notes: String
) {
    fun getFormattedSize(): String {
        return when {
            totalSizeMB >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f TB", totalSizeMB / (1024 * 1024))
            totalSizeMB >= 1024 -> String.format(java.util.Locale.US, "%.2f GB", totalSizeMB / 1024)
            totalSizeMB >= 1 -> String.format(java.util.Locale.US, "%.1f MB", totalSizeMB)
            else -> String.format(java.util.Locale.US, "%.0f KB", totalSizeMB * 1024)
        }
    }
}
