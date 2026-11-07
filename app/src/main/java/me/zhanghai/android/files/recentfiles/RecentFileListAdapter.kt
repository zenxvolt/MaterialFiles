package me.zhanghai.android.files.recentfiles

import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFileItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

class RecentFileListAdapter(
    private val isPickMode: Boolean,
    private val listener: Listener
) : SimpleAdapter<RecentFile, RecentFileListAdapter.ViewHolder>() {
    // Paths are unique in the list, but their hash codes aren't guaranteed to be.
    override val hasStableIds: Boolean
        get() = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(RecentFileItemBinding.inflate(parent.context.layoutInflater, parent, false))
            .apply {
                popupMenu = PopupMenu(binding.menuButton.context, binding.menuButton)
                    .apply {
                        inflate(R.menu.recent_file_item)
                        menu.findItem(R.id.action_open_with).isVisible = !isPickMode
                    }
                binding.menuButton.setOnClickListener { popupMenu.show() }
            }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recentFile = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        binding.root.setOnClickListener { listener.openRecentFile(recentFile) }
        binding.root.setOnLongClickListener {
            holder.popupMenu.show()
            true
        }
        binding.iconImage.setImageResource(recentFile.mimeType.iconRes)
        val path = recentFile.path
        binding.nameText.text = path.name
        val directoryString = path.parent?.toUserFriendlyString()
        val timeString = DateUtils.getRelativeTimeSpanString(
            recentFile.lastModifiedTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
        val sizeString = recentFile.size.asFileSize().formatHumanReadable(context)
        binding.descriptionText.text = listOfNotNull(timeString, sizeString, directoryString)
            .joinToString(context.getString(R.string.recent_file_item_description_separator))
        holder.popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_open_with -> {
                    listener.openRecentFileWith(recentFile)
                    true
                }
                R.id.action_show_in_directory -> {
                    listener.showRecentFileInDirectory(recentFile)
                    true
                }
                else -> false
            }
        }
    }

    class ViewHolder(val binding: RecentFileItemBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var popupMenu: PopupMenu
    }

    interface Listener {
        fun openRecentFile(recentFile: RecentFile)
        fun openRecentFileWith(recentFile: RecentFile)
        fun showRecentFileInDirectory(recentFile: RecentFile)
    }
}
