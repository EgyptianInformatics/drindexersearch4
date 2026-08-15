package com.drindexer.search

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration-change-safe controller for DatabaseActivity.
 *
 * Import/verify/index work belongs to the ViewModel rather than an Activity
 * lifecycleScope, so rotating/recreating the screen cannot orphan a validated
 * import halfway through its post-import indexing stage. DatabaseHelper itself
 * performs the atomic candidate -> validate -> publish/rollback transaction.
 */
class DatabaseViewModel : ViewModel() {
    data class UiMessage(val id: Long, val text: String)

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _stage = MutableLiveData("")
    val stage: LiveData<String> = _stage

    private val _message = MutableLiveData<UiMessage?>()
    val message: LiveData<UiMessage?> = _message

    private val _refreshToken = MutableLiveData(0L)
    val refreshToken: LiveData<Long> = _refreshToken

    private var operation: Job? = null
    private val messageIds = AtomicLong(0L)
    private val refreshIds = AtomicLong(0L)


    fun loadExisting(dbHelper: DatabaseHelper) {
        launchExclusive("Opening saved database…") {
            val ok = dbHelper.loadSavedDatabase { loaded, total ->
                _stage.postValue("Building search index… ${fmt(loaded)} / ${fmt(total)}")
            }
            if (ok && !dbHelper.searchIndex.isLoaded) {
                dbHelper.ensureFtsIndex { msg -> _stage.postValue(msg) }
            }
            if (!ok) postMessage("Saved database could not be opened")
        }
    }

    fun verify(dbHelper: DatabaseHelper) {
        launchExclusive("Checking SQLite integrity…") {
            val ok = dbHelper.verifyCurrentDatabase()
            postMessage(if (ok) "Database check: OK" else "Database check failed")
        }
    }

    fun rebuild(dbHelper: DatabaseHelper) {
        launchExclusive("Rebuilding search index…") {
            val ok = dbHelper.rebuildSearchIndex { loaded, total ->
                _stage.postValue(
                    "Building search index… ${fmt(loaded)} / ${fmt(total)}"
                )
            }
            postMessage(
                if (ok) "Search index ready — ${dbHelper.getActiveSearchEngineLabel()}"
                else "Index rebuild failed"
            )
        }
    }

    fun importDatabase(uri: Uri, dbHelper: DatabaseHelper) {
        launchExclusive("Copying / decompressing to a safe temporary database…") {
            val prep = dbHelper.prepareImport(uri)
            if (!prep.success) {
                postMessage(prep.errorMessage ?: "Import failed")
                return@launchExclusive
            }

            _stage.postValue(
                "Validated and published ${fmt(prep.fileCount)} entries. Building search engine…"
            )
            val ok = dbHelper.rebuildSearchIndex { loaded, total ->
                _stage.postValue(
                    "Building search index… ${fmt(loaded)} / ${fmt(total)}"
                )
            }
            postMessage(
                if (ok) {
                    "Import complete — previous database stayed protected until validation passed • ${dbHelper.getActiveSearchEngineLabel()}"
                } else {
                    "Imported database is valid, but the search index rebuild failed"
                }
            )
        }
    }

    fun consumeMessage(id: Long) {
        if (_message.value?.id == id) _message.value = null
    }

    private fun launchExclusive(initialStage: String, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        _busy.value = true
        _stage.value = initialStage
        operation = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                e.printStackTrace()
                postMessage(e.message ?: "Database operation failed")
            } finally {
                _stage.postValue("")
                _busy.postValue(false)
                _refreshToken.postValue(refreshIds.incrementAndGet())
            }
        }
    }

    private fun postMessage(text: String) {
        _message.postValue(UiMessage(messageIds.incrementAndGet(), text))
    }

    private fun fmt(n: Int): String = String.format("%,d", n)
}
