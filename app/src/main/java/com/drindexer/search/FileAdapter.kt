package com.drindexer.search

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter with DiffUtil for efficient updates.
 * V2.3: Added long-press callback and fixed empty type tag visibility.
 */
class FileAdapter(
    private val onItemClick: ((FileItem) -> Unit)? = null,
    private val onItemLongClick: ((FileItem, View) -> Unit)? = null
) : ListAdapter<FileItem, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    /** v3.3 (U3): current query, bolded inside matching filenames. */
    private var highlightQuery: String = ""

    fun setHighlightQuery(query: String) {
        if (query != highlightQuery) {
            highlightQuery = query
            // Rows that survive the diff unchanged would otherwise keep the
            // previous query's bold span — force them to rebind.
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view, onItemClick, onItemLongClick)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position), highlightQuery)
    }

    class FileViewHolder(
        itemView: View,
        private val onItemClick: ((FileItem) -> Unit)?,
        private val onItemLongClick: ((FileItem, View) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {

        private val iconView: ImageView = itemView.findViewById(R.id.fileIcon)
        private val nameView: TextView = itemView.findViewById(R.id.fileName)
        private val pathView: TextView = itemView.findViewById(R.id.filePath)
        private val sizeView: TextView = itemView.findViewById(R.id.fileSize)
        private val typeView: TextView = itemView.findViewById(R.id.fileType)
        private val scanView: TextView = itemView.findViewById(R.id.scanName)

        private var currentItem: FileItem? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let { item -> onItemClick?.invoke(item) }
            }
            itemView.setOnLongClickListener {
                currentItem?.let { item ->
                    onItemLongClick?.invoke(item, itemView)
                }
                true
            }
        }

        fun bind(item: FileItem, highlightQuery: String) {
            currentItem = item

            nameView.text = FileAdapter.highlighted(item.filename, highlightQuery)
            // v4: Fast results normally carry archived paths; legacy/fallback rows may
            // still have no path, so collapse the row only in that case.
            if (item.filePath.isNullOrEmpty()) {
                pathView.visibility = View.GONE
            } else {
                pathView.visibility = View.VISIBLE
                pathView.text = item.filePath
            }
            sizeView.text = item.getFormattedSize()
            scanView.text = item.scanName

            val ext = item.getDisplayExtension()
            if (item.isFolder || ext.isEmpty()) {
                typeView.visibility = View.GONE
                typeView.text = ""
            } else {
                typeView.visibility = View.VISIBLE
                typeView.text = ext
            }

            if (item.isFolder) {
                iconView.setImageResource(R.drawable.ic_folder)
            } else {
                val category = FileCategory.getCategoryForExtension(item.getExtension())
                val iconRes = when (category) {
                    "video" -> R.drawable.ic_video
                    "audio" -> R.drawable.ic_audio
                    "document" -> R.drawable.ic_document
                    "image" -> R.drawable.ic_image
                    "archive" -> R.drawable.ic_archive
                    "code" -> R.drawable.ic_code
                    "executable" -> R.drawable.ic_executable
                    else -> R.drawable.ic_file
                }
                iconView.setImageResource(iconRes)
            }
        }
    }

    companion object {
        /**
         * v3.3 (U3): bold the first case-insensitive occurrence of the query
         * within the filename so users can see WHY a result matched.
         */
        fun highlighted(text: String, query: String): CharSequence {
            if (query.length < 3) return text
            val idx = text.indexOf(query, ignoreCase = true)
            if (idx < 0) return text
            val span = SpannableString(text)
            span.setSpan(
                StyleSpan(Typeface.BOLD), idx, idx + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return span
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem == newItem
    }
}
