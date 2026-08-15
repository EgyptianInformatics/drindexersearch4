package com.drindexer.search

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.util.Locale

/** Central database lifecycle screen introduced in v4. */
class DatabaseActivity : AppCompatActivity() {
    private lateinit var dbHelper: DatabaseHelper
    private val viewModel: DatabaseViewModel by viewModels()

    private lateinit var summary: TextView
    private lateinit var stage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modeGroup: RadioGroup
    private lateinit var verifyButton: Button
    private lateinit var rebuildButton: Button
    private lateinit var importButton: Button
    private lateinit var removeButton: Button
    private var bindingMode = false
    private var busy = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importDatabase(uri, dbHelper)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        dbHelper = DbProvider.get(this)
        setContentView(buildUi())
        observeViewModel()
        refreshInfo()
        if (!dbHelper.isLoaded() && dbHelper.hasSavedDatabase()) {
            viewModel.loadExisting(dbHelper)
        }
    }

    private fun observeViewModel() {
        viewModel.busy.observe(this) {
            busy = it
            setBusy(it)
            if (!it) refreshInfo()
        }
        viewModel.stage.observe(this) { message ->
            stage.text = message
            stage.visibility = if (message.isBlank() && !busy) View.GONE else View.VISIBLE
        }
        viewModel.message.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message.text, Toast.LENGTH_LONG).show()
                viewModel.consumeMessage(message.id)
            }
        }
        viewModel.refreshToken.observe(this) { refreshInfo() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(getColor(R.color.primary))
            setTitleTextColor(0xFFFFFFFF.toInt())
            title = "Database"
            subtitle = "Import, verify and search-mode settings"
            setSubtitleTextColor(0xFFBBDEFB.toInt())
        }
        root.addView(toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        scroll.addView(body)
        root.addView(scroll)

        body.addView(sectionTitle("Current database"))
        summary = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 12) }
        body.addView(summary)

        stage = TextView(this).apply { textSize = 12f; visibility = View.GONE; setPadding(0, 4, 0, 8) }
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        body.addView(stage)
        body.addView(progress)

        body.addView(sectionTitle("Search mode"))
        body.addView(TextView(this).apply {
            text = "Automatic is recommended. It uses the real Android heap budget and falls back to disk search when a RAM index would be risky."
            textSize = 12f
            setPadding(0, 4, 0, 8)
        })
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            "auto" to "Automatic — Recommended",
            "fast" to "Fast — RAM index",
            "disk" to "Low memory — Disk/FTS"
        ).forEachIndexed { idx, pair ->
            modeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = pair.first
                text = pair.second
                if (idx == 0) isChecked = true
            })
        }
        body.addView(modeGroup)

        body.addView(sectionTitle("Maintenance"))
        verifyButton = actionButton("Verify database") { viewModel.verify(dbHelper) }
        rebuildButton = actionButton("Rebuild search index") { viewModel.rebuild(dbHelper) }
        importButton = actionButton("Import / Replace database safely") {
            importLauncher.launch(
                arrayOf(
                    "application/gzip", "application/x-gzip",
                    "application/x-sqlite3", "application/vnd.sqlite3",
                    "application/octet-stream", "*/*"
                )
            )
        }
        removeButton = actionButton("Remove local database copy") { confirmRemove() }
        body.addView(verifyButton)
        body.addView(rebuildButton)
        body.addView(importButton)
        body.addView(removeButton)

        modeGroup.setOnCheckedChangeListener { group, checkedId ->
            if (bindingMode || busy) return@setOnCheckedChangeListener
            val selected = group.findViewById<RadioButton>(checkedId)?.tag?.toString() ?: "auto"
            dbHelper.setIndexMode(selected)
            if (dbHelper.isLoaded()) viewModel.rebuild(dbHelper)
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 4)
        }
        val search = Button(this).apply {
            text = "⌕ Search"; isAllCaps = false; setOnClickListener { openSearch() }
        }
        val browse = Button(this).apply {
            text = "🗂 Browse"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@DatabaseActivity, ScansActivity::class.java)) }
        }
        val database = Button(this).apply { text = "🗄 Database"; isAllCaps = false; isEnabled = false }
        nav.addView(search, LinearLayout.LayoutParams(0, 48.dp, 1f))
        nav.addView(browse, LinearLayout.LayoutParams(0, 48.dp, 1f))
        nav.addView(database, LinearLayout.LayoutParams(0, 48.dp, 1f))
        root.addView(nav)
        return root
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 17f; setPadding(0, 18, 0, 4)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
    }

    private fun refreshInfo() {
        bindingMode = true
        val mode = dbHelper.getIndexMode()
        for (i in 0 until modeGroup.childCount) {
            val rb = modeGroup.getChildAt(i) as RadioButton
            rb.isChecked = rb.tag == mode
        }
        bindingMode = false

        if (!dbHelper.isLoaded()) {
            summary.text = "No database loaded.\nImport a .db or .db.gz Mobile Export from Dr. Indexer."
            updateControlAvailability()
            return
        }
        val bytes = dbHelper.getLocalDatabaseSizeBytes()
        val sourceVersion = dbHelper.getMetaValue("source_version") ?: "—"
        val exportedAt = dbHelper.getMetaValue("exported_at") ?: "—"
        summary.text = buildString {
            append("${dbHelper.getDatabaseName()}\n")
            append("Mobile schema: ${dbHelper.getSchemaVersion()} / ${DatabaseHelper.SUPPORTED_MOBILE_SCHEMA}\n")
            append("Entries: ${String.format("%,d", dbHelper.getTotalFileCount())}\n")
            append("Local size: ${formatBytes(bytes)}\n")
            append("Desktop source: $sourceVersion\n")
            append("Exported: $exportedAt\n")
            append("Active engine: ${dbHelper.getActiveSearchEngineLabel()}")
        }
        updateControlAvailability()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove local database?")
            .setMessage("This deletes only the mobile copy stored by this app. It does not change the desktop database or any indexed drive files.")
            .setPositiveButton("Remove") { _, _ ->
                dbHelper.clearSavedDatabase()
                refreshInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (!value && (viewModel.stage.value ?: "").isBlank()) stage.visibility = View.GONE
        updateControlAvailability()
    }

    private fun updateControlAvailability() {
        val loaded = dbHelper.isLoaded()
        verifyButton.isEnabled = !busy && loaded
        rebuildButton.isEnabled = !busy && loaded
        importButton.isEnabled = !busy
        removeButton.isEnabled = !busy && dbHelper.hasSavedDatabase()
        modeGroup.isEnabled = !busy
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !busy
    }

    private fun openSearch() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
