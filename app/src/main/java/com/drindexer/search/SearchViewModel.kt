package com.drindexer.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for v4 search and initial search-engine preparation.
 * Database import/maintenance lives in DatabaseViewModel; this class no longer
 * carries the old two-phase import UI state from v3.x.
 */
class SearchViewModel : ViewModel() {

    private val _searchResults = MutableLiveData<List<FileItem>>(emptyList())
    val searchResults: LiveData<List<FileItem>> = _searchResults

    private val _totalCount = MutableLiveData(0)
    val totalCount: LiveData<Int> = _totalCount

    private val _isSearching = MutableLiveData(false)
    val isSearching: LiveData<Boolean> = _isSearching

    private val _statusMessage = MutableLiveData("")
    val statusMessage: LiveData<String> = _statusMessage

    private val _databaseReady = MutableLiveData<Boolean?>()
    val databaseReady: LiveData<Boolean?> = _databaseReady

    private var searchJob: Job? = null
    private var prepareJob: Job? = null

    companion object {
        private const val DEBOUNCE_FAST_MS = 120L
        private const val DEBOUNCE_SQL_MS = 300L
    }

    fun search(query: String, filter: SearchFilter, dbHelper: DatabaseHelper) {
        searchJob?.cancel()
        if (!SearchNormalizer.parse(query).isUsable) {
            _searchResults.value = emptyList()
            _totalCount.value = 0
            _isSearching.value = false
            return
        }
        _isSearching.value = true

        searchJob = viewModelScope.launch {
            delay(if (dbHelper.searchIndex.isLoaded) DEBOUNCE_FAST_MS else DEBOUNCE_SQL_MS)
            val job = coroutineContext[Job]
            val result = withContext(Dispatchers.IO) {
                dbHelper.searchFiles(query, filter) { job?.isActive != false }
            }
            if (isActive) {
                _searchResults.value = result.items
                _totalCount.value = result.totalCount
                _isSearching.value = false
            }
        }
    }

    /** Open the saved DB and prepare the configured Fast/Disk engine. */
    fun loadSavedDatabase(dbHelper: DatabaseHelper) {
        if (prepareJob?.isActive == true) return
        _statusMessage.value = "Loading database…"
        _databaseReady.value = null
        prepareJob = viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                val ok = dbHelper.loadSavedDatabase { loaded, total ->
                    _statusMessage.postValue("Building search index… ${fmt(loaded)} / ${fmt(total)}")
                }
                if (ok && !dbHelper.searchIndex.isLoaded) {
                    dbHelper.ensureFtsIndex { msg -> _statusMessage.postValue(msg) }
                }
                ok
            }
            _statusMessage.value = ""
            _databaseReady.value = success
        }
    }

    /** Used when another screen opened the shared DB without preparing Search. */
    fun prepareSearchEngine(dbHelper: DatabaseHelper) {
        if (prepareJob?.isActive == true) return
        _statusMessage.value = "Preparing search engine…"
        _databaseReady.value = null
        prepareJob = viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                dbHelper.rebuildSearchIndex { loaded, total ->
                    _statusMessage.postValue("Building search index… ${fmt(loaded)} / ${fmt(total)}")
                }
            }
            _statusMessage.value = ""
            _databaseReady.value = success
        }
    }

    fun clearResults() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _totalCount.value = 0
        _isSearching.value = false
    }

    private fun fmt(n: Int): String = String.format("%,d", n)
}
