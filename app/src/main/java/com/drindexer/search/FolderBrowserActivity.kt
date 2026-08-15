package com.drindexer.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 root-aware folder browser.
 *
 * Mobile schema v3 browses scan_roots -> folders(parent_id) directly, avoiding
 * the old DISTINCT folder-id reconstruction on every tap. v1/v2 databases keep
 * the legacy path-based browser as a compatibility fallback.
 */
class FolderBrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_SCAN_NAME = "scan_name"
        private const val STATE_ROOT_ID = "root_id"
        private const val STATE_FOLDER_IDS = "folder_ids"
        private const val STATE_FOLDER_NAMES = "folder_names"
        private const val STATE_PATH_STACK = "path_stack"
        private const val NULL_TOKEN = "\u0000ROOT\u0000"
    }

    private lateinit var dbHelper: DatabaseHelper
    private var scanId = -1
    private var scanName = ""
    private var directHierarchy = false

    private var roots: List<DatabaseHelper.ScanRootSummary> = emptyList()
    private var currentRoot: DatabaseHelper.ScanRootSummary? = null
    private val folderIds = ArrayList<Int?>()
    private val folderNames = ArrayList<String>()

    // v1/v2 fallback navigation.
    private val pathStack = ArrayList<String?>()
    private var currentPath: String? = null

    private lateinit var recycler: RecyclerView
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var crumbRow: LinearLayoutCompat
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText
    private var searchGeneration = 0
    private var contentGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        scanId = intent.getIntExtra(EXTRA_SCAN_ID, -1)
        scanName = intent.getStringExtra(EXTRA_SCAN_NAME) ?: "Scan"

        val rootLayout = LinearLayoutCompat(this).apply {
            orientation = LinearLayoutCompat.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(getColor(R.color.primary)); setTitleTextColor(0xFFFFFFFF.toInt())
            setSubtitleTextColor(0xFFBBDEFB.toInt())
        }
        crumbRow = LinearLayoutCompat(this).apply { orientation = LinearLayoutCompat.HORIZONTAL; setPadding(16, 8, 16, 8) }
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(crumbRow) }
        searchInput = EditText(this).apply {
            hint = "Search inside this location…"; setSingleLine(true); setPadding(20, 12, 20, 12)
        }
        emptyView = TextView(this).apply {
            setPadding(48, 96, 48, 48); textSize = 14f; text = "This location is empty."; visibility = View.GONE
        }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FolderBrowserActivity)
            layoutParams = LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        }
        rootLayout.addView(toolbar); rootLayout.addView(crumbScroll); rootLayout.addView(searchInput)
        rootLayout.addView(emptyView); rootLayout.addView(recycler); setContentView(rootLayout)

        setSupportActionBar(toolbar)
        supportActionBar?.apply { title = scanName; subtitle = "Browse • v${BuildConfig.VERSION_NAME}"; setDisplayHomeAsUpEnabled(true) }
        dbHelper = DbProvider.get(this)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val generation = ++searchGeneration
                lifecycleScope.launch {
                    delay(220)
                    if (generation != searchGeneration) return@launch
                    loadCurrent(s?.toString().orEmpty())
                }
            }
        })

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) { dbHelper.isLoaded() || dbHelper.loadScansOnly(buildFolders = true) }
            if (!opened) { Toast.makeText(this@FolderBrowserActivity, "Could not open database", Toast.LENGTH_LONG).show(); finish(); return@launch }
            directHierarchy = dbHelper.supportsDirectHierarchy()
            if (directHierarchy) {
                roots = withContext(Dispatchers.IO) { dbHelper.getScanRoots(scanId) }
                restoreDirectState(savedInstanceState)
            } else {
                restoreLegacyState(savedInstanceState)
            }
        }
    }

    private suspend fun restoreDirectState(state: Bundle?) {
        val savedRootId = state?.getInt(STATE_ROOT_ID, -1) ?: -1
        val savedIds = state?.getIntegerArrayList(STATE_FOLDER_IDS)
        val savedNames = state?.getStringArrayList(STATE_FOLDER_NAMES)
        if (savedRootId >= 0) {
            currentRoot = roots.firstOrNull { it.id == savedRootId }
            folderIds.clear(); folderNames.clear()
            if (savedIds != null && savedNames != null) {
                for (i in savedIds.indices) {
                    folderIds.add(savedIds[i].takeIf { it >= 0 }); folderNames.add(savedNames.getOrElse(i) { "Folder" })
                }
            }
            if (folderIds.isEmpty()) { folderIds.add(null); folderNames.add(currentRoot?.path ?: "Root") }
            renderBreadcrumbs(); loadCurrent("")
        } else if (roots.size == 1) {
            selectRoot(roots.first())
        } else {
            showRootChoices()
        }
    }

    private suspend fun restoreLegacyState(state: Bundle?) {
        val saved = state?.getStringArrayList(STATE_PATH_STACK)
        if (!saved.isNullOrEmpty()) {
            pathStack.clear(); saved.mapTo(pathStack) { if (it == NULL_TOKEN) null else it }
            navigateLegacy(pathStack.last(), false)
        } else {
            val rootPath = withContext(Dispatchers.IO) { dbHelper.getScanRootPath(scanId) }
            navigateLegacy(rootPath, true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (directHierarchy) {
            outState.putInt(STATE_ROOT_ID, currentRoot?.id ?: -1)
            outState.putIntegerArrayList(STATE_FOLDER_IDS, ArrayList(folderIds.map { it ?: -1 }))
            outState.putStringArrayList(STATE_FOLDER_NAMES, ArrayList(folderNames))
        } else {
            outState.putStringArrayList(STATE_PATH_STACK, ArrayList(pathStack.map { it ?: NULL_TOKEN }))
        }
    }

    private fun showRootChoices() {
        contentGeneration++
        currentRoot = null; folderIds.clear(); folderNames.clear(); renderBreadcrumbs()
        val entries = roots.map {
            val label = if (it.volumeLabel.isNotBlank()) "${it.volumeLabel} — ${it.path}" else it.path
            DatabaseHelper.BrowseEntry(label, it.path, true, null, null, it.id)
        }
        bindEntries(entries, false)
        emptyView.text = if (entries.isEmpty()) "No roots were exported for this scan." else ""
    }

    private fun selectRoot(root: DatabaseHelper.ScanRootSummary) {
        currentRoot = root; folderIds.clear(); folderNames.clear()
        folderIds.add(null); folderNames.add(if (root.volumeLabel.isNotBlank()) root.volumeLabel else root.path)
        searchInput.setText(""); renderBreadcrumbs(); loadCurrent("")
    }

    private fun navigateFolder(entry: DatabaseHelper.BrowseEntry) {
        val fid = entry.folderId ?: return
        folderIds.add(fid); folderNames.add(entry.name); searchInput.setText("")
        renderBreadcrumbs(); loadCurrent("")
    }

    private fun loadCurrent(query: String) {
        val generation = ++contentGeneration
        if (directHierarchy) {
            val root = currentRoot ?: return
            val parent = folderIds.lastOrNull()
            lifecycleScope.launch {
                val spec = SearchNormalizer.parse(query)
                val entries = withContext(Dispatchers.IO) {
                    if (spec.isUsable) dbHelper.searchWithinFolderDirect(scanId, root.id, parent, query)
                    else dbHelper.getFolderChildrenDirect(scanId, root.id, parent)
                }
                if (generation != contentGeneration) return@launch
                bindEntries(entries, spec.isUsable)
            }
        } else {
            val path = currentPath
            lifecycleScope.launch {
                val entries = withContext(Dispatchers.IO) { dbHelper.getFolderChildren(scanId, path) }
                val spec = SearchNormalizer.parse(query)
                val filtered = if (spec.isUsable) entries.filter {
                    SearchNormalizer.matches(it.name, spec) || SearchNormalizer.matches(it.fullPath, spec)
                } else entries
                if (generation != contentGeneration) return@launch
                bindEntries(filtered, spec.isUsable)
            }
        }
    }

    private fun navigateLegacy(path: String?, push: Boolean) {
        if (push) pathStack.add(path)
        currentPath = path
        renderBreadcrumbs()
        val generation = ++contentGeneration
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { dbHelper.getFolderChildren(scanId, path) }
            if (generation != contentGeneration) return@launch
            bindEntries(entries, false)
        }
    }

    private fun bindEntries(entries: List<DatabaseHelper.BrowseEntry>, searchContext: Boolean = false) {
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        recycler.adapter = BrowseAdapter(entries, searchContext,
            onFolder = { e ->
                if (directHierarchy && currentRoot == null && e.rootId != null) {
                    roots.firstOrNull { it.id == e.rootId }?.let(::selectRoot)
                } else if (directHierarchy) navigateFolder(e) else navigateLegacy(e.fullPath, true)
            },
            onFolderLong = { e -> showFolderMenu(e) },
            onFile = { e -> e.fileItem?.let(::showFileDetails) },
            onFileLong = { e -> e.fileItem?.let(::showFileMenu) })
    }

    private fun renderBreadcrumbs() {
        crumbRow.removeAllViews()
        fun add(label: String, current: Boolean, action: (() -> Unit)? = null) {
            crumbRow.addView(TextView(this).apply {
                text = if (current) label else "$label ›"; textSize = 13f; setPadding(8,8,8,8)
                if (current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                else if (action != null) { isClickable = true; setOnClickListener { action() } }
            })
        }
        if (directHierarchy) {
            if (currentRoot == null) { add("🗂 $scanName", true); return }
            add("🗂 $scanName", false) { showRootChoices() }
            folderNames.forEachIndexed { idx, name ->
                add(name, idx == folderNames.lastIndex) {
                    while (folderIds.size > idx + 1) { folderIds.removeAt(folderIds.lastIndex); folderNames.removeAt(folderNames.lastIndex) }
                    searchInput.setText(""); renderBreadcrumbs(); loadCurrent("")
                }
            }
        } else {
            pathStack.forEachIndexed { idx, p ->
                val label = if (p == null) "🗂 $scanName" else if (idx == 0) p else p.substringAfterLast(if (p.contains("\\")) "\\" else "/")
                add(label, idx == pathStack.lastIndex) {
                    while (pathStack.size > idx + 1) pathStack.removeAt(pathStack.lastIndex)
                    navigateLegacy(pathStack.last(), false)
                }
            }
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun goUp(): Boolean {
        if (directHierarchy) {
            if (currentRoot == null) return false
            if (folderIds.size > 1) {
                folderIds.removeAt(folderIds.lastIndex); folderNames.removeAt(folderNames.lastIndex)
                searchInput.setText(""); renderBreadcrumbs(); loadCurrent(""); return true
            }
            if (roots.size > 1) { showRootChoices(); return true }
            return false
        }
        if (pathStack.size <= 1) return false
        pathStack.removeAt(pathStack.lastIndex); navigateLegacy(pathStack.last(), false); return true
    }

    override fun onSupportNavigateUp(): Boolean { if (!goUp()) finish(); return true }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (!goUp()) { @Suppress("DEPRECATION") super.onBackPressed() } }

    private fun buildFullPath(item: FileItem): String {
        val folder = item.filePath ?: return item.filename
        if (item.isFolder) return folder
        val sep = if (folder.contains("\\")) "\\" else "/"
        return folder.trimEnd('\\','/') + sep + item.filename
    }

    private fun showFolderMenu(entry: DatabaseHelper.BrowseEntry) {
        android.app.AlertDialog.Builder(this).setTitle(entry.name)
            .setItems(arrayOf("Copy Path", "Share Path")) { _, which ->
                if (which == 0) copyToClipboard("Folder Path", entry.fullPath) else shareText(entry.fullPath)
            }.show()
    }

    private fun showFileDetails(item: FileItem) {
        val fullPath = buildFullPath(item)
        val details = buildString {
            append("📄 ${item.filename}\n\n📁 Path: $fullPath\n📊 Size: ${item.getFormattedSize()}\n")
            if (!item.isFolder) append("📋 Type: ${item.getDisplayExtension()}\n")
            append("📅 Modified: ${item.dateModified ?: "N/A"}\n🗂 Scan: ${item.scanName}")
        }
        android.app.AlertDialog.Builder(this).setTitle("Item Details").setMessage(details)
            .setPositiveButton("OK", null).setNeutralButton("Copy Path") { _, _ -> copyToClipboard("Path", fullPath) }
            .setNegativeButton("Share") { _, _ -> shareText(fullPath) }.show()
    }

    private fun showFileMenu(item: FileItem) {
        val fullPath = buildFullPath(item)
        android.app.AlertDialog.Builder(this).setTitle(item.filename)
            .setItems(arrayOf("Copy Path", "Copy Filename", "Share Path", "Details")) { _, which ->
                when (which) { 0 -> copyToClipboard("Path",fullPath); 1 -> copyToClipboard("Filename",item.filename); 2 -> shareText(fullPath); 3 -> showFileDetails(item) }
            }.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,text))
        Toast.makeText(this,"$label copied",Toast.LENGTH_SHORT).show()
    }
    private fun shareText(text: String) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,text) },"Share")) }

    private class BrowseAdapter(
        private val items: List<DatabaseHelper.BrowseEntry>,
        private val searchContext: Boolean,
        private val onFolder: (DatabaseHelper.BrowseEntry)->Unit,
        private val onFolderLong: (DatabaseHelper.BrowseEntry)->Unit,
        private val onFile: (DatabaseHelper.BrowseEntry)->Unit,
        private val onFileLong: (DatabaseHelper.BrowseEntry)->Unit
    ): RecyclerView.Adapter<BrowseAdapter.VH>() {
        class VH(v: View): RecyclerView.ViewHolder(v) { val icon:TextView=v.findViewById(R.id.browseIcon); val name:TextView=v.findViewById(R.id.browseName); val meta:TextView=v.findViewById(R.id.browseMeta) }
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=VH(LayoutInflater.from(parent.context).inflate(R.layout.item_browse,parent,false))
        override fun onBindViewHolder(h:VH,pos:Int) {
            val e=items[pos]
            h.icon.text=if(e.isFolder)"📁" else "📄"
            h.name.text=e.name
            h.meta.text = if (searchContext) {
                if (e.isFolder) e.fullPath else listOfNotNull(e.fileItem?.getFormattedSize(), e.fileItem?.filePath).joinToString(" • ")
            } else if(e.isFolder) "Folder" else e.fileItem?.getFormattedSize().orEmpty()
            h.itemView.setOnClickListener{if(e.isFolder)onFolder(e) else onFile(e)}
            h.itemView.setOnLongClickListener{if(e.isFolder)onFolderLong(e) else onFileLong(e);true}
        }
        override fun getItemCount()=items.size
    }
}
