package com.flipper.psadecrypt.filemanager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flipper.psadecrypt.R
import com.flipper.psadecrypt.storage.FlipperFile

class FileListAdapter(
    private val onItemClick: (FlipperFile) -> Unit,
    private val onDownload: (FlipperFile) -> Unit,
    private val onDelete: (FlipperFile) -> Unit
) : ListAdapter<FlipperFile, FileListAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FlipperFile>() {
            override fun areItemsTheSame(a: FlipperFile, b: FlipperFile) = a.name == b.name
            override fun areContentsTheSame(a: FlipperFile, b: FlipperFile) = a == b
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.img_icon)
        val name: TextView = view.findViewById(R.id.txt_name)
        val size: TextView = view.findViewById(R.id.txt_size)
        val moreBtn: ImageButton = view.findViewById(R.id.btn_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = getItem(position)

        holder.name.text = file.name

        if (file.isDirectory) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
            holder.size.visibility = View.GONE
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_save)
            holder.size.text = formatSize(file.size)
            holder.size.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener { onItemClick(file) }

        holder.moreBtn.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            if (!file.isDirectory) {
                popup.menu.add(0, 1, 0, "Download")
            }
            popup.menu.add(0, 2, 1, "Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { onDownload(file); true }
                    2 -> { onDelete(file); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
