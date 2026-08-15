package com.drindexer.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dr Indexer mobile v4 compact in-memory search index.
 *
 * v4 changes:
 *  - two-pass blob construction: no retained per-filename byte arrays / million temporary objects
 *  - tolerant normalized filename blob (Latin separators + Arabic normalization)
 *  - folder/root/date metadata retained so result paths and sorting are truthful
 *  - real Scan Name and Date Modified sorting
 *  - Relevance sorting for ordinary filename search
 *  - Fast and disk engines share SearchNormalizer semantics
 */
class SearchIndex {
    private var _loaded = false
    private var _entryCount = 0

    private var filenameBytes = ByteArray(0)
    private var offsets = IntArray(0)
    private var byteLengths = IntArray(0)

    private var normalizedBytes = ByteArray(0)
    private var normalizedOffsets = IntArray(0)
    private var normalizedLengths = IntArray(0)

    private var ids = LongArray(0)
    private var scanIds = IntArray(0)
    private var rootIds = IntArray(0)
    private var folderIds = IntArray(0)
    private var sizes = DoubleArray(0)
    private var sizeBytes = LongArray(0)
    private var modifiedEpoch = LongArray(0)
    private var isFolders = ByteArray(0)

    private var typeIndices = ShortArray(0)
    private var typeTable = emptyArray<String>()

    private var folderDisplayPaths: Map<Int, String> = emptyMap()
    private var folderSearchPaths: Map<Int, String> = emptyMap()
    private var scanNames: Map<Int, String> = emptyMap()

    private var prevQueryKey: String? = null
    private var prevFilterKey: FilterKey? = null
    private var prevMatches: IntArray? = null

    private var executor: ExecutorService? = null
    private var poolSize = 1

    val isLoaded: Boolean get() = _loaded
    val entryCount: Int get() = _entryCount

    private data class FilterKey(
        val category: String,
        val extensionFilter: String,
        val minSizeMB: Float,
        val maxSizeMB: Float,
        val includeFiles: Boolean,
        val includeFolders: Boolean,
        val scanIds: Set<Int>
    ) {
        companion object {
            fun of(f: SearchFilter) = FilterKey(
                f.category, f.extensionFilter, f.minSizeMB, f.maxSizeMB,
                f.includeFiles, f.includeFolders, f.scanIds.toSet()
            )
        }
    }

    fun estimatedMemoryMB(): Int {
        if (!_loaded) return 0
        val bytes = filenameBytes.size.toLong() + normalizedBytes.size.toLong() +
            offsets.size * 4L + byteLengths.size * 4L +
            normalizedOffsets.size * 4L + normalizedLengths.size * 4L +
            ids.size * 8L + scanIds.size * 4L + rootIds.size * 4L +
            folderIds.size * 4L + sizes.size * 8L + sizeBytes.size * 8L +
            modifiedEpoch.size * 8L + isFolders.size + typeIndices.size * 2L
        return (bytes / (1024 * 1024)).toInt()
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) if (c.getString(1) == column) return true
                false
            }
        } catch (_: Exception) { false }
    }

    /** Two database passes trade a little import time for dramatically lower peak heap. */
    fun load(
        db: SQLiteDatabase,
        folderPaths: Map<Int, String> = emptyMap(),
        scanNameCache: Map<Int, String> = emptyMap(),
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Boolean {
        return try {
            release()
            // Raw desktop databases can contain hidden/incomplete scan rows. Mobile
            // Export v3 omits them, but when a desktop DB is opened directly we keep
            // the same user-visible rule: ordinary all-scan search covers valid scans.
            val validWhere = if (columnExists(db, "scans", "corrupted")) {
                " WHERE scan_id IN (SELECT id FROM scans WHERE COALESCE(corrupted,0)=0)"
            } else ""
            val count = db.rawQuery("SELECT COUNT(*) FROM files$validWhere", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            if (count == 0) return false

            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            // Metadata alone is ~65 bytes/entry, plus two filename blobs.
            if (count * 105L > available * 0.52) return false
            onProgress?.invoke(0, count)

            // Pass 1: exact blob sizes only. No per-entry byte arrays retained.
            var originalTotal = 0L
            var normalizedTotal = 0L
            db.rawQuery("SELECT filename FROM files$validWhere", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: ""
                    originalTotal += name.toByteArray(StandardCharsets.UTF_8).size
                    normalizedTotal += SearchNormalizer.normalize(name)
                        .toByteArray(StandardCharsets.UTF_8).size
                    if (originalTotal > Int.MAX_VALUE || normalizedTotal > Int.MAX_VALUE) return false
                }
            }

            // Re-check with the measured blob sizes before allocating the final
            // arrays. The first estimate intentionally errs small because filenames
            // vary wildly; this second guard protects large/long-path archives from
            // crossing the real Android heap ceiling during index construction.
            val availableAfterPass1 = runtime.maxMemory() -
                (runtime.totalMemory() - runtime.freeMemory())
            val projectedFinalBytes = originalTotal + normalizedTotal + count * 72L
            if (projectedFinalBytes > (availableAfterPass1 * 0.68).toLong()) return false

            val newFilenameBytes = ByteArray(originalTotal.toInt())
            val newNormalizedBytes = ByteArray(normalizedTotal.toInt())
            val newOffsets = IntArray(count)
            val newLengths = IntArray(count)
            val newNormOffsets = IntArray(count)
            val newNormLengths = IntArray(count)
            val newIds = LongArray(count)
            val newScanIds = IntArray(count)
            val newRootIds = IntArray(count) { -1 }
            val newFolderIds = IntArray(count) { -1 }
            val newSizes = DoubleArray(count)
            val newSizeBytes = LongArray(count)
            val newModified = LongArray(count)
            val newFolders = ByteArray(count)
            val newTypeIndices = ShortArray(count) { -1 }

            val hasRoot = columnExists(db, "files", "root_id")
            val hasFolder = columnExists(db, "files", "folder_id")
            val hasSizeBytes = columnExists(db, "files", "size_bytes")
            val rootExpr = if (hasRoot) "root_id" else "NULL"
            val folderExpr = if (hasFolder) "folder_id" else "NULL"
            val sizeMbExpr = if (hasSizeBytes) "COALESCE(size_bytes,0)/1048576.0" else "COALESCE(size,0)"
            val bytesExpr = if (hasSizeBytes) "COALESCE(size_bytes,0)" else "CAST(ROUND(COALESCE(size,0)*1048576.0) AS INTEGER)"

            val sql = """SELECT id,scan_id,$rootExpr,filename,$folderExpr,file_type,
                        $sizeMbExpr,$bytesExpr,date_modified,is_folder FROM files$validWhere"""

            val typeMap = HashMap<String, Short>(256)
            val typeList = ArrayList<String>(256)
            var originalPos = 0
            var normalizedPos = 0
            var i = 0
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    newIds[i] = c.getLong(0)
                    newScanIds[i] = c.getInt(1)
                    if (!c.isNull(2)) newRootIds[i] = c.getInt(2)
                    val name = c.getString(3) ?: ""
                    val raw = name.toByteArray(StandardCharsets.UTF_8)
                    val norm = SearchNormalizer.normalize(name).toByteArray(StandardCharsets.UTF_8)
                    newOffsets[i] = originalPos
                    newLengths[i] = raw.size
                    System.arraycopy(raw, 0, newFilenameBytes, originalPos, raw.size)
                    originalPos += raw.size
                    newNormOffsets[i] = normalizedPos
                    newNormLengths[i] = norm.size
                    System.arraycopy(norm, 0, newNormalizedBytes, normalizedPos, norm.size)
                    normalizedPos += norm.size

                    if (!c.isNull(4)) newFolderIds[i] = c.getInt(4)
                    val type = c.getString(5)
                    if (type != null) {
                        var typeIndex = typeMap[type]
                        if (typeIndex == null) {
                            typeIndex = typeList.size.toShort()
                            typeMap[type] = typeIndex
                            typeList.add(type)
                        }
                        newTypeIndices[i] = typeIndex
                    }
                    newSizes[i] = c.getDouble(6)
                    newSizeBytes[i] = c.getLong(7)
                    newModified[i] = readEpoch(c, 8)
                    newFolders[i] = c.getInt(9).toByte()
                    i++
                    if (i % 50_000 == 0) onProgress?.invoke(i, count)
                }
            }

            filenameBytes = newFilenameBytes
            normalizedBytes = newNormalizedBytes
            offsets = newOffsets
            byteLengths = newLengths
            normalizedOffsets = newNormOffsets
            normalizedLengths = newNormLengths
            ids = newIds
            scanIds = newScanIds
            rootIds = newRootIds
            folderIds = newFolderIds
            sizes = newSizes
            sizeBytes = newSizeBytes
            modifiedEpoch = newModified
            isFolders = newFolders
            typeIndices = newTypeIndices
            typeTable = typeList.toTypedArray()
            folderDisplayPaths = folderPaths
            folderSearchPaths = folderPaths.mapValues { SearchNormalizer.normalize(it.value) }
            scanNames = scanNameCache
            _entryCount = i
            _loaded = true
            invalidateIncrementalCache()

            poolSize = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            if (poolSize > 1 && i > 200_000) {
                executor = Executors.newFixedThreadPool(poolSize) { r ->
                    Thread(r, "search-scan").apply { isDaemon = true }
                }
            }
            onProgress?.invoke(i, count)
            true
        } catch (e: OutOfMemoryError) {
            release(); System.gc(); false
        } catch (e: Exception) {
            e.printStackTrace(); release(); false
        }
    }

    private fun readEpoch(c: Cursor, column: Int): Long {
        if (c.isNull(column)) return 0L
        return try {
            when (c.getType(column)) {
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(column)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(column).toLong()
                Cursor.FIELD_TYPE_STRING -> parseLegacyDate(c.getString(column))
                else -> 0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun parseLegacyDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let { return it }
        val patterns = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                return (fmt.parse(value)?.time ?: 0L) / 1000L
            } catch (_: Exception) { }
        }
        return 0L
    }

    fun search(
        query: String,
        filter: SearchFilter,
        scanNameCache: Map<Int, String>,
        isActive: (() -> Boolean)? = null
    ): SearchResult {
        if (!_loaded) return SearchResult(emptyList(), 0)
        val spec = SearchNormalizer.parse(query)
        if (!spec.isUsable) return SearchResult(emptyList(), 0)
        val filterKey = FilterKey.of(filter)
        val queryKey = if (spec.exact) "E:${spec.exactText.lowercase()}" else "N:${spec.normalized}"
        val tokenBytes = spec.tokens.map { it.toByteArray(StandardCharsets.UTF_8) }

        val categoryExts: HashSet<String>? = if (filter.category != "all") {
            FileCategory.getExtensionsForCategory(filter.category).map { it.lowercase() }.toHashSet()
        } else null
        val extFilter: HashSet<String>? = if (filter.extensionFilter.isNotBlank()) {
            filter.extensionFilter.split(',').map { it.trim().lowercase().trimStart('.') }
                .filter { it.isNotEmpty() }.toHashSet()
        } else null

        fun entryMatches(i: Int): Boolean {
            val dir = isFolders[i].toInt() == 1
            if (!filter.includeFiles && !dir) return false
            if (!filter.includeFolders && dir) return false
            if (filter.scanIds.isNotEmpty() && scanIds[i] !in filter.scanIds) return false
            if (filter.minSizeMB > 0f && sizes[i] < filter.minSizeMB) return false
            if (filter.maxSizeMB < Float.MAX_VALUE && sizes[i] > filter.maxSizeMB) return false
            if (categoryExts != null || extFilter != null) {
                val ti = typeIndices[i].toInt()
                val type = if (ti >= 0) typeTable[ti].lowercase() else ""
                if (categoryExts != null && type !in categoryExts) return false
                if (extFilter != null && type !in extFilter) return false
            }
            val nameMatch = if (spec.exact) exactNameMatch(i, spec.exactText) else tokensMatch(i, tokenBytes)
            if (nameMatch) return true
            val fid = folderIds[i]
            if (fid < 0) return false
            val path = folderDisplayPaths[fid] ?: return false
            return if (spec.exact) {
                path.contains(spec.exactText, ignoreCase = true)
            } else {
                val normalizedPath = folderSearchPaths[fid] ?: return false
                spec.tokens.all { normalizedPath.contains(it) }
            }
        }

        val prevKey = prevQueryKey
        val previous = prevMatches
        val canNarrow = prevKey != null && previous != null && filterKey == prevFilterKey &&
            queryKey.length > prevKey.length && queryKey.startsWith(prevKey)

        val matches = when {
            canNarrow -> {
                val out = IntArrayBuilder(minOf(previous!!.size, 4096))
                var k = 0
                for (idx in previous) {
                    if (entryMatches(idx)) out.add(idx)
                    if (++k and 0x3FFF == 0 && isActive?.invoke() == false) return SearchResult(emptyList(), 0)
                }
                out.toArray()
            }
            executor != null && _entryCount > 200_000 ->
                parallelScan(::entryMatches, isActive) ?: return SearchResult(emptyList(), 0)
            else -> {
                val out = IntArrayBuilder(minOf(_entryCount / 10 + 16, 50_000))
                for (i in 0 until _entryCount) {
                    if (entryMatches(i)) out.add(i)
                    if (i and 0xFFFF == 0 && isActive?.invoke() == false) return SearchResult(emptyList(), 0)
                }
                out.toArray()
            }
        }

        prevQueryKey = queryKey
        prevFilterKey = filterKey
        prevMatches = matches
        val total = matches.size
        if (total == 0) return SearchResult(emptyList(), 0)
        val limit = minOf(filter.limit, total)
        val ordered = selectTop(matches, limit, filter, spec, scanNameCache)
        val items = ArrayList<FileItem>(limit)
        for (idx in ordered) {
            val filename = originalName(idx)
            val ti = typeIndices[idx].toInt()
            val folderId = folderIds[idx].takeIf { it >= 0 }
            val rootId = rootIds[idx].takeIf { it >= 0 }
            items.add(
                FileItem(
                    id = ids[idx], scanId = scanIds[idx], rootId = rootId,
                    filename = filename,
                    filePath = folderId?.let(folderDisplayPaths::get),
                    fileType = if (ti >= 0) typeTable[ti] else null,
                    size = sizes[idx], sizeBytes = sizeBytes[idx], folderId = folderId,
                    dateModifiedEpoch = modifiedEpoch[idx].takeIf { it > 0 },
                    isFolder = isFolders[idx].toInt() == 1,
                    scanName = scanNameCache[scanIds[idx]] ?: scanNames[scanIds[idx]] ?: "Unknown"
                )
            )
        }
        return SearchResult(items, total)
    }

    private fun tokensMatch(i: Int, tokenBytes: List<ByteArray>): Boolean {
        if (tokenBytes.isEmpty()) return false
        val start = normalizedOffsets[i]
        val len = normalizedLengths[i]
        for (token in tokenBytes) if (!containsBytes(normalizedBytes, start, len, token)) return false
        return true
    }

    private fun exactNameMatch(i: Int, phrase: String): Boolean =
        originalName(i).contains(phrase, ignoreCase = true)

    private fun originalName(i: Int): String =
        String(filenameBytes, offsets[i], byteLengths[i], StandardCharsets.UTF_8)

    private fun containsBytes(blob: ByteArray, start: Int, len: Int, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > len) return false
        val max = start + len - needle.size
        var p = start
        while (p <= max) {
            if (blob[p] == needle[0]) {
                var ok = true
                for (j in 1 until needle.size) {
                    if (blob[p + j] != needle[j]) { ok = false; break }
                }
                if (ok) return true
            }
            p++
        }
        return false
    }

    private fun startsWithBytes(blob: ByteArray, start: Int, len: Int, needle: ByteArray): Boolean {
        if (needle.size > len) return false
        for (j in needle.indices) if (blob[start + j] != needle[j]) return false
        return true
    }

    private fun equalsBytes(blob: ByteArray, start: Int, len: Int, needle: ByteArray): Boolean =
        len == needle.size && startsWithBytes(blob, start, len, needle)

    private fun relevanceScore(i: Int, spec: SearchNormalizer.QuerySpec): Int {
        if (spec.exact) {
            val name = originalName(i)
            if (name.equals(spec.exactText, ignoreCase = true)) return 0
            if (name.startsWith(spec.exactText, ignoreCase = true)) return 1
            if (name.contains(spec.exactText, ignoreCase = true)) return 2
            val fid = folderIds[i]
            val path = if (fid >= 0) folderDisplayPaths[fid] else null
            return if (path?.contains(spec.exactText, ignoreCase = true) == true) 10 else 1000
        }
        val phrase = spec.normalized.toByteArray(StandardCharsets.UTF_8)
        val start = normalizedOffsets[i]; val len = normalizedLengths[i]
        if (equalsBytes(normalizedBytes, start, len, phrase)) return 0
        if (startsWithBytes(normalizedBytes, start, len, phrase)) return 1
        if (containsBytes(normalizedBytes, start, len, phrase)) return 2
        val tokenBytes = spec.tokens.map { it.toByteArray(StandardCharsets.UTF_8) }
        if (tokensMatch(i, tokenBytes)) return 3
        val fid = folderIds[i]
        val p = if (fid >= 0) folderSearchPaths[fid] else null
        return if (p != null && spec.tokens.all { p.contains(it) }) 10 else 1000
    }

    fun invalidateIncrementalCache() {
        prevQueryKey = null; prevFilterKey = null; prevMatches = null
    }

    private fun parallelScan(
        entryMatches: (Int) -> Boolean,
        isActive: (() -> Boolean)?
    ): IntArray? {
        val exec = executor ?: return null
        val n = _entryCount
        val chunk = (n + poolSize - 1) / poolSize
        val tasks = ArrayList<Callable<IntArray?>>(poolSize)
        for (part in 0 until poolSize) {
            val from = part * chunk
            val to = minOf(from + chunk, n)
            if (from >= to) break
            tasks.add(Callable {
                val out = IntArrayBuilder(minOf((to - from) / 10 + 16, 50_000))
                var k = 0
                for (i in from until to) {
                    if (entryMatches(i)) out.add(i)
                    if (++k and 0xFFFF == 0 && isActive?.invoke() == false) return@Callable null
                }
                out.toArray()
            })
        }
        return try {
            val futures = exec.invokeAll(tasks)
            val chunks = ArrayList<IntArray>(futures.size)
            var total = 0
            for (f in futures) {
                val a = f.get() ?: return null
                chunks.add(a); total += a.size
            }
            val merged = IntArray(total)
            var pos = 0
            for (a in chunks) { System.arraycopy(a, 0, merged, pos, a.size); pos += a.size }
            merged
        } catch (_: Exception) {
            val out = IntArrayBuilder(4096)
            for (i in 0 until n) if (entryMatches(i)) out.add(i)
            out.toArray()
        }
    }

    private fun selectTop(
        matches: IntArray,
        limit: Int,
        filter: SearchFilter,
        spec: SearchNormalizer.QuerySpec,
        scanNameCache: Map<Int, String>
    ): IntArray {
        val comp = buildComparator(filter, spec, scanNameCache)
        if (matches.size <= limit * 4 || matches.size <= 4096) {
            val boxed = matches.toTypedArray(); boxed.sortWith(comp)
            return IntArray(minOf(limit, boxed.size)) { boxed[it] }
        }
        val heap = PriorityQueue<Int>(limit + 1, comp.reversed())
        for (m in matches) {
            heap.add(m)
            if (heap.size > limit) heap.poll()
        }
        val out = IntArray(heap.size)
        for (j in out.indices.reversed()) out[j] = heap.poll() ?: 0
        return out
    }

    private fun buildComparator(
        filter: SearchFilter,
        spec: SearchNormalizer.QuerySpec,
        scanNameCache: Map<Int, String>
    ): Comparator<Int> {
        val field: Comparator<Int> = when (filter.sortField) {
            SortField.RELEVANCE -> Comparator { a, b -> relevanceScore(a, spec).compareTo(relevanceScore(b, spec)) }
            SortField.NAME -> Comparator { a, b -> originalName(a).compareTo(originalName(b), ignoreCase = true) }
            SortField.SIZE -> Comparator { a, b -> sizeBytes[a].compareTo(sizeBytes[b]) }
            SortField.DATE -> Comparator { a, b -> modifiedEpoch[a].compareTo(modifiedEpoch[b]) }
            SortField.EXTENSION -> Comparator { a, b ->
                val ta = typeIndices[a].toInt(); val tb = typeIndices[b].toInt()
                val sa = if (ta >= 0) typeTable[ta] else ""
                val sb = if (tb >= 0) typeTable[tb] else ""
                sa.compareTo(sb, ignoreCase = true)
            }
            SortField.SCAN -> Comparator { a, b ->
                (scanNameCache[scanIds[a]] ?: scanNames[scanIds[a]] ?: "")
                    .compareTo(scanNameCache[scanIds[b]] ?: scanNames[scanIds[b]] ?: "", ignoreCase = true)
            }
        }
        return Comparator { a, b ->
            if (filter.sortField != SortField.RELEVANCE) {
                val folderCmp = isFolders[b].compareTo(isFolders[a])
                if (folderCmp != 0) return@Comparator folderCmp
            }
            val raw = field.compare(a, b)
            val directed = if (filter.sortOrder == SortOrder.DESC) -raw else raw
            if (directed != 0) directed else a.compareTo(b)
        }
    }

    fun release() {
        executor?.shutdownNow(); executor = null
        filenameBytes = ByteArray(0); normalizedBytes = ByteArray(0)
        offsets = IntArray(0); byteLengths = IntArray(0)
        normalizedOffsets = IntArray(0); normalizedLengths = IntArray(0)
        ids = LongArray(0); scanIds = IntArray(0); rootIds = IntArray(0); folderIds = IntArray(0)
        sizes = DoubleArray(0); sizeBytes = LongArray(0); modifiedEpoch = LongArray(0)
        isFolders = ByteArray(0); typeIndices = ShortArray(0); typeTable = emptyArray()
        folderDisplayPaths = emptyMap(); folderSearchPaths = emptyMap(); scanNames = emptyMap()
        _entryCount = 0; _loaded = false; invalidateIncrementalCache()
    }

    private class IntArrayBuilder(initial: Int) {
        private var arr = IntArray(maxOf(initial, 16)); private var size = 0
        fun add(v: Int) {
            if (size == arr.size) arr = arr.copyOf(arr.size + (arr.size shr 1))
            arr[size++] = v
        }
        fun toArray(): IntArray = arr.copyOf(size)
    }
}

data class SearchResult(val items: List<FileItem>, val totalCount: Int)
