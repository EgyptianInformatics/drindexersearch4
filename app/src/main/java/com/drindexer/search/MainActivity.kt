package com.drindexer.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.drindexer.search.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dr Indexer Search v4 — search-first home screen.
 *
 * Search scope is derived from selected scans: no selection means all scans;
 * one or more selections mean exactly those scans. Search semantics are shared
 * by Fast and Disk modes through SearchNormalizer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var fileAdapter: FileAdapter
    private val viewModel: SearchViewModel by viewModels()
    private var currentFilter = SearchFilter()
    private var currentQuery = ""
    private lateinit var searchHistory: SearchHistory

    companion object {
        const val EXTRA_SCOPE_SCAN_ID = "scope_scan_id"
        const val EXTRA_SCOPE_SCAN_NAME = "scope_scan_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DbProvider.get(this)
        searchHistory = SearchHistory(this)
        fileAdapter = FileAdapter(
            onItemClick = { showFileDetails(it) },
            onItemLongClick = { item, anchor -> showContextMenu(item, anchor) }
        )

        savedInstanceState?.getBundle("current_filter")?.let { currentFilter = SearchFilter.fromBundle(it) }
        currentQuery = savedInstanceState?.getString("current_query", "") ?: ""
        applyScopeFromIntent(intent)

        binding.appVersion.text = "v${BuildConfig.VERSION_NAME}"
        setupUI()
        observeViewModel()
        loadSavedDatabase()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyScopeFromIntent(intent)) {
            updateScopeUI()
            rerunIfUsable()
        }
    }

    private fun applyScopeFromIntent(intent: Intent?): Boolean {
        val scanId = intent?.getIntExtra(EXTRA_SCOPE_SCAN_ID, -1) ?: -1
        if (scanId < 0) return false
        currentFilter.scanIds = setOf(scanId)
        // Consume the one-shot navigation instruction. Otherwise a later
        // rotation could reapply an old scan after the user chose All scans.
        intent?.removeExtra(EXTRA_SCOPE_SCAN_ID)
        intent?.removeExtra(EXTRA_SCOPE_SCAN_NAME)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("current_filter", currentFilter.toBundle())
        outState.putString("current_query", currentQuery)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            if (dbHelper.searchIndex.isLoaded) {
                dbHelper.releaseIndex()
                updateSearchModeIndicator()
            }
        }
    }

    private fun setupUI() {
        binding.resultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultsRecycler.adapter = fileAdapter

        updateThemeIcon()
        binding.btnThemeToggle.setOnClickListener { ThemeHelper.toggleTheme(this) }

        // Database management is a first-class destination in v4.
        binding.importButton.setOnClickListener { openDatabaseManager() }
        binding.btnBrowseScans.setOnClickListener { openBrowse() }
        binding.navSearch.setOnClickListener { binding.searchInput.requestFocus() }
        binding.navBrowse.setOnClickListener { openBrowse() }
        binding.navDatabase.setOnClickListener { openDatabaseManager() }
        binding.btnScope.setOnClickListener { showScopeDialog() }
        binding.btnRecent.setOnClickListener { showSearchHistory() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                updateResetButtonVisibility()
                if (canSearch()) {
                    viewModel.search(currentQuery, currentFilter, dbHelper)
                } else {
                    viewModel.clearResults()
                    updateResultCount(0, 0)
                    showEmptyState(searchHint())
                }
            }
        })
        binding.searchInput.setOnLongClickListener { showSearchHistory(); true }
        if (currentQuery.isNotEmpty()) {
            binding.searchInput.setText(currentQuery)
            binding.searchInput.setSelection(currentQuery.length)
        }

        binding.btnResetSearch.setOnClickListener { resetSearch() }
        setupQuickFilters()
        binding.btnFilter.setOnClickListener { showFilterBottomSheet() }
        binding.btnSort.setOnClickListener { showSortMenu(it) }
        binding.btnClearFilters.setOnClickListener { clearFilters() }

        supportFragmentManager.setFragmentResultListener(FilterBottomSheet.RESULT_KEY, this) { _, bundle ->
            bundle.getBundle(FilterBottomSheet.BUNDLE_FILTER)?.let {
                val scope = currentFilter.scanIds
                currentFilter = SearchFilter.fromBundle(it).also { f -> f.scanIds = scope }
                updateQuickFilterChips()
                updateFilterIndicators()
                rerunIfUsable()
            }
        }
        updateScopeUI()
        updateFilterIndicators()
        updateResetButtonVisibility()
    }

    private fun observeViewModel() {
        viewModel.searchResults.observe(this) { results ->
            fileAdapter.setHighlightQuery(currentQuery.trim().trim('"'))
            fileAdapter.submitList(results)
            if (results.isEmpty() && canSearch()) showEmptyState("No results found")
            else if (results.isNotEmpty()) {
                hideEmptyState()
                searchHistory.add(currentQuery)
            }
            updateResetButtonVisibility()
        }
        viewModel.totalCount.observe(this) { updateResultCount(viewModel.searchResults.value?.size ?: 0, it) }
        viewModel.isSearching.observe(this) { binding.searchProgress.visibility = if (it) View.VISIBLE else View.GONE }
        viewModel.statusMessage.observe(this) { message ->
            binding.importProgress.visibility = if (message.isNotBlank()) View.VISIBLE else View.GONE
            if (message.isNotBlank()) binding.dbStatus.text = message
        }
        viewModel.databaseReady.observe(this) { success ->
            success ?: return@observe
            if (success) {
                updateDatabaseStatus(); enableSearch(true); rerunIfUsable()
            } else {
                binding.dbStatus.text = "No valid database loaded"; enableSearch(false)
            }
        }
    }

    private fun updateThemeIcon() {
        val dark = ThemeHelper.isDarkMode(this)
        binding.btnThemeToggle.text = if (dark) "☀️" else "🌙"
        binding.btnThemeToggle.contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode"
    }

    private fun setupQuickFilters() {
        binding.chipAll.setOnClickListener { applyQuickCategory("all") }
        binding.chipFolder.setOnClickListener { applyQuickCategory("folder") }
        binding.chipVideo.setOnClickListener { applyQuickCategory("video") }
        binding.chipAudio.setOnClickListener { applyQuickCategory("audio") }
        binding.chipDocs.setOnClickListener { applyQuickCategory("document") }
        binding.chipImages.setOnClickListener { applyQuickCategory("image") }
        binding.chipArchive.setOnClickListener { applyQuickCategory("archive") }
        binding.chipCode.setOnClickListener { applyQuickCategory("code") }
        binding.chipExe.setOnClickListener { applyQuickCategory("executable") }
        updateQuickFilterChips()
    }

    private fun applyQuickCategory(category: String) {
        when (category) {
            "all" -> { currentFilter.category = "all"; currentFilter.includeFiles = true; currentFilter.includeFolders = true }
            "folder" -> { currentFilter.category = "all"; currentFilter.includeFiles = false; currentFilter.includeFolders = true }
            else -> { currentFilter.category = category; currentFilter.includeFiles = true; currentFilter.includeFolders = false }
        }
        updateQuickFilterChips(); updateFilterIndicators(); rerunIfUsable()
    }

    private fun updateQuickFilterChips() {
        val foldersOnly = currentFilter.includeFolders && !currentFilter.includeFiles
        binding.chipFolder.isChecked = foldersOnly
        binding.chipAll.isChecked = currentFilter.category == "all" && currentFilter.includeFiles && currentFilter.includeFolders
        binding.chipVideo.isChecked = currentFilter.category == "video" && currentFilter.includeFiles
        binding.chipAudio.isChecked = currentFilter.category == "audio" && currentFilter.includeFiles
        binding.chipDocs.isChecked = currentFilter.category == "document" && currentFilter.includeFiles
        binding.chipImages.isChecked = currentFilter.category == "image" && currentFilter.includeFiles
        binding.chipArchive.isChecked = currentFilter.category == "archive" && currentFilter.includeFiles
        binding.chipCode.isChecked = currentFilter.category == "code" && currentFilter.includeFiles
        binding.chipExe.isChecked = currentFilter.category == "executable" && currentFilter.includeFiles
    }

    private fun showScopeDialog() {
        val scans = dbHelper.getScans()
        if (scans.isEmpty()) { Toast.makeText(this, "No scans available", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(scans.size) { scans[it].first in currentFilter.scanIds }
        AlertDialog.Builder(this)
            .setTitle("Search scope")
            .setMessage("Leave every scan unchecked to search all scans.")
            .setMultiChoiceItems(scans.map { it.second }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("Apply") { _, _ ->
                currentFilter.scanIds = scans.indices.filter { checked[it] }.map { scans[it].first }.toSet()
                updateScopeUI(); updateFilterIndicators(); rerunIfUsable()
            }
            .setNeutralButton("All scans") { _, _ ->
                currentFilter.scanIds = emptySet(); updateScopeUI(); updateFilterIndicators(); rerunIfUsable()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateScopeUI() {
        val ids = currentFilter.scanIds
        binding.btnScope.text = when (ids.size) {
            0 -> "Search scope: All scans"
            1 -> "Search scope: ${dbHelper.scanNameCache[ids.first()] ?: "1 scan"}"
            else -> "Search scope: Selected scans (${ids.size})"
        }
    }

    private fun showFilterBottomSheet() {
        FilterBottomSheet.newInstance(currentFilter, dbHelper.getScans())
            .show(supportFragmentManager, FilterBottomSheet.TAG)
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        SortField.values().forEachIndexed { index, field ->
            popup.menu.add(0, index, index, field.displayName).apply {
                isCheckable = true; isChecked = currentFilter.sortField == field
            }
        }
        popup.menu.add(1, 101, 100, if (currentFilter.sortOrder == SortOrder.DESC) "Toggle: ↓ Descending" else "Toggle: ↑ Ascending")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 0 until SortField.values().size -> {
                    currentFilter.sortField = SortField.values()[item.itemId]
                    if (currentFilter.sortField == SortField.RELEVANCE) currentFilter.sortOrder = SortOrder.ASC
                    updateFilterIndicators(); rerunIfUsable(); true
                }
                101 -> {
                    currentFilter.sortOrder = if (currentFilter.sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                    updateFilterIndicators(); rerunIfUsable(); true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun clearFilters() {
        val scope = currentFilter.scanIds
        currentFilter.reset(); currentFilter.scanIds = scope
        updateQuickFilterChips(); updateFilterIndicators(); updateResetButtonVisibility(); rerunIfUsable()
        Toast.makeText(this, "Filters cleared", Toast.LENGTH_SHORT).show()
    }

    private fun hasNonScopeFilters(): Boolean = currentFilter.copy().also { it.scanIds = emptySet() }.hasActiveFilters()

    private fun updateFilterIndicators() {
        val hasFilters = hasNonScopeFilters()
        binding.btnClearFilters.visibility = if (hasFilters) View.VISIBLE else View.GONE
        binding.filterSummary.text = currentFilter.copy().also { it.scanIds = emptySet() }.getFilterSummary()
        binding.sortSummary.text = currentFilter.getSortSummary()
        binding.btnFilter.alpha = if (hasFilters) 1.0f else 0.7f
        updateScopeUI()
    }

    private fun resetSearch() {
        val scope = currentFilter.scanIds
        binding.searchInput.setText("")
        currentQuery = ""
        viewModel.clearResults(); updateResultCount(0, 0)
        currentFilter.reset(); currentFilter.scanIds = scope
        updateQuickFilterChips(); updateFilterIndicators(); updateResetButtonVisibility()
        showEmptyState(searchHint()); binding.searchInput.clearFocus()
    }

    private fun updateResetButtonVisibility() {
        val hasContent = currentQuery.isNotEmpty() || fileAdapter.itemCount > 0 || hasNonScopeFilters()
        binding.btnResetSearch.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    private fun canSearch(): Boolean = SearchNormalizer.parse(currentQuery).isUsable
    private fun searchHint(): String = "Type at least 2 letters/digits, or use quotes for an exact phrase"
    private fun rerunIfUsable() { if (canSearch()) viewModel.search(currentQuery, currentFilter, dbHelper) }

    private fun loadSavedDatabase() {
        if (dbHelper.isLoaded()) {
            updateDatabaseStatus(); enableSearch(true)
            if (dbHelper.isSearchEngineReady()) rerunIfUsable()
            else viewModel.prepareSearchEngine(dbHelper)
            return
        }
        if (dbHelper.hasSavedDatabase()) {
            binding.dbStatus.text = "Loading database…"; enableSearch(false)
            viewModel.loadSavedDatabase(dbHelper)
        } else {
            binding.dbStatus.text = "No database loaded"; enableSearch(false)
        }
    }

    private fun updateDatabaseStatus() {
        val name = dbHelper.getDatabaseName()
        val count = dbHelper.getTotalFileCount()
        val mode = if (dbHelper.searchIndex.isLoaded) "⚡" else "💾"
        binding.dbStatus.text = "✓ $name (${formatNumber(count)} entries) $mode"
        binding.dbStatus.setTextColor(ContextCompat.getColor(this, R.color.status_loaded))
        binding.btnBrowseScans.visibility = View.VISIBLE
        updateSearchModeIndicator(); updateScopeUI()
    }

    private fun updateSearchModeIndicator() {
        binding.optimizationStatus.text = when {
            dbHelper.searchIndex.isLoaded -> "⚡ ${dbHelper.getActiveSearchEngineLabel()}"
            dbHelper.isLoaded() -> "💾 ${dbHelper.getActiveSearchEngineLabel()}"
            else -> ""
        }
        binding.optimizationStatus.visibility = if (dbHelper.isLoaded()) View.VISIBLE else View.GONE
    }

    private fun enableSearch(enabled: Boolean) {
        binding.searchInput.isEnabled = enabled
        listOf(binding.chipAll, binding.chipFolder, binding.chipVideo, binding.chipAudio,
            binding.chipDocs, binding.chipImages, binding.chipArchive, binding.chipCode, binding.chipExe)
            .forEach { it.isEnabled = enabled }
        binding.btnFilter.isEnabled = enabled; binding.btnSort.isEnabled = enabled; binding.btnScope.isEnabled = enabled
        if (!enabled) {
            fileAdapter.submitList(emptyList()); binding.resultCount.text = ""; showEmptyState("Import a database to start searching")
        } else if (!canSearch()) showEmptyState(searchHint())
        updateResetButtonVisibility()
    }

    private fun updateResultCount(showing: Int, total: Int) {
        if (showing == 0) { binding.resultCount.text = ""; return }
        val filterInfo = if (hasNonScopeFilters()) " (filtered)" else ""
        binding.resultCount.text = if (total > showing) "Showing ${formatNumber(showing)} of ${formatNumber(total)} results$filterInfo"
        else "${formatNumber(showing)} results$filterInfo"
    }

    private fun showEmptyState(message: String) { binding.emptyState.visibility = View.VISIBLE; binding.emptyText.text = message }
    private fun hideEmptyState() { binding.emptyState.visibility = View.GONE }

    private fun resolveFullPath(item: FileItem, onResolved: (String) -> Unit) {
        if (!item.filePath.isNullOrBlank()) { onResolved(joinPath(item.filePath, item.filename, item.isFolder)); return }
        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) { dbHelper.getFileDetails(item.id) }
            onResolved(if (!full?.filePath.isNullOrBlank()) joinPath(full!!.filePath!!, full.filename, full.isFolder) else item.filename)
        }
    }

    private fun joinPath(folder: String, filename: String, isFolder: Boolean): String {
        if (folder.isBlank()) return filename
        if (isFolder && (folder.endsWith(filename) || folder.endsWith("/$filename") || folder.endsWith("\\$filename"))) return folder
        if (isFolder) return folder
        val sep = if (folder.contains("\\")) "\\" else "/"
        return if (folder.endsWith(sep)) "$folder$filename" else "$folder$sep$filename"
    }

    private fun showFileDetails(item: FileItem) {
        lifecycleScope.launch {
            val full = withContext(Dispatchers.IO) { dbHelper.getFileDetails(item.id) } ?: item
            val fullPath = full.filePath?.let { joinPath(it, full.filename, full.isFolder) }
            val details = buildString {
                append(if (full.isFolder) "📁 " else "📄 "); append(full.filename); append("\n\n")
                append("📁 Archived path: ${fullPath ?: "N/A"}\n")
                append("📊 Size: ${full.getFormattedSize()}\n")
                if (!full.isFolder) append("📋 Type: ${full.getDisplayExtension()}\n")
                append("📅 Modified: ${full.dateModified ?: "N/A"}\n")
                append("🗂️ Scan: ${full.scanName}")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (full.isFolder) "Folder Details" else "File Details")
                .setMessage(details)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy Path") { _, _ -> copyToClipboard("Archived Path", fullPath ?: full.filename) }
                .setNegativeButton("Share") { _, _ -> shareText(fullPath ?: full.filename) }
                .show()
        }
    }

    private fun showContextMenu(item: FileItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Copy Path")
        popup.menu.add(0, 2, 1, if (item.isFolder) "Copy Folder Name" else "Copy Filename")
        popup.menu.add(0, 4, 2, "Share Path")
        popup.menu.add(0, 3, 3, "View Details")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { resolveFullPath(item) { p -> copyToClipboard("Archived Path", p) }; true }
                2 -> { copyToClipboard(if (item.isFolder) "Folder Name" else "Filename", item.filename); true }
                4 -> { resolveFullPath(item) { p -> shareText(p) }; true }
                3 -> { showFileDetails(item); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "Share"))
    }

    private fun showSearchHistory() {
        val history = searchHistory.get()
        if (history.isEmpty()) { Toast.makeText(this, "No recent searches yet", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Recent searches")
            .setItems(history.toTypedArray()) { _, which ->
                val q = history[which]; binding.searchInput.setText(q); binding.searchInput.setSelection(q.length)
            }
            .setNeutralButton("Clear history") { _, _ -> searchHistory.clear() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openBrowse() = startActivity(Intent(this, ScansActivity::class.java))
    private fun openDatabaseManager() = startActivity(Intent(this, DatabaseActivity::class.java))
    private fun formatNumber(n: Int): String = String.format("%,d", n)
}
