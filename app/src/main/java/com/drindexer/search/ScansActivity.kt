package com.drindexer.search

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-scan browsing screen. v3.3
 *
 * v3.3 changes:
 *  - Real Toolbar (the NoActionBar app theme made supportActionBar null, so
 *    the title and up button silently never rendered — B2).
 *  - Uses the shared DbProvider DatabaseHelper: opens instantly, no duplicate
 *    folder map in RAM (B4). Falls back to a scans-only open after process
 *    death, skipping the folder map it doesn't need.
 *  - Empty state when the database contains no scans (U4).
 */
class ScansActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_SCAN_NAME = "scan_name"
    }

    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayoutCompat(this).apply {
            orientation = LinearLayoutCompat.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(getColor(R.color.primary))
            setTitleTextColor(0xFFFFFFFF.toInt())
            setSubtitleTextColor(0xFFBBDEFB.toInt())
        }
        val emptyView = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 15f
            text = "No scans in this database."
            visibility = View.GONE
        }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ScansActivity)
            setPadding(0, 8, 0, 8)
            clipToPadding = false
            layoutParams = LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        root.addView(toolbar)
        root.addView(emptyView)
        root.addView(recycler)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(Button(this).apply {
            text = "⌕ Search"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@ScansActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(Button(this).apply { text = "🗂 Browse"; isAllCaps = false; isEnabled = false }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(Button(this).apply {
            text = "🗄 Database"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@ScansActivity, DatabaseActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(nav)
        setContentView(root)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = "Browse Scans"
            subtitle = "Tap to browse • hold to search this scan"
            setDisplayHomeAsUpEnabled(true)
        }

        dbHelper = DbProvider.get(this)

        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) {
                // Shared helper is usually already open (opened by Main).
                // After process death, do a light scans-only open — the scans
                // list never needs the folder map.
                if (dbHelper.isLoaded() || dbHelper.loadScansOnly(buildFolders = false)) {
                    dbHelper.getScanSummaries()
                } else emptyList()
            }
            if (summaries.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            }
            recycler.adapter = ScanAdapter(
                summaries,
                onClick = { scan ->
                    startActivity(Intent(this@ScansActivity, FolderBrowserActivity::class.java).apply {
                        putExtra(FolderBrowserActivity.EXTRA_SCAN_ID, scan.id)
                        putExtra(FolderBrowserActivity.EXTRA_SCAN_NAME, scan.name)
                    })
                },
                onLongClick = { scan ->
                    startActivity(Intent(this@ScansActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra(MainActivity.EXTRA_SCOPE_SCAN_ID, scan.id)
                        putExtra(MainActivity.EXTRA_SCOPE_SCAN_NAME, scan.name)
                    })
                }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // v3.3: dbHelper is shared (DbProvider) — never close it here.

    private class ScanAdapter(
        private val items: List<ScanSummary>,
        private val onClick: (ScanSummary) -> Unit,
        private val onLongClick: (ScanSummary) -> Unit
    ) : RecyclerView.Adapter<ScanAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.scanTitle)
            val meta: TextView = view.findViewById(R.id.scanMeta)
            val extra: TextView = view.findViewById(R.id.scanExtra)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scan, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.title.text = s.name
            val date = if (s.scanDate.isNotBlank()) s.scanDate else "—"
            holder.meta.text = "${String.format("%,d", s.fileCount)} files  •  ${s.getFormattedSize()}  •  $date"

            val extras = mutableListOf<String>()
            if (s.tags.isNotBlank()) extras.add("\uD83C\uDFF7\uFE0F ${s.tags}")
            if (s.notes.isNotBlank()) extras.add(s.notes)
            if (extras.isEmpty()) {
                holder.extra.visibility = View.GONE
            } else {
                holder.extra.visibility = View.VISIBLE
                holder.extra.text = extras.joinToString("  •  ")
            }

            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener { onLongClick(s); true }
        }

        override fun getItemCount(): Int = items.size
    }
}
