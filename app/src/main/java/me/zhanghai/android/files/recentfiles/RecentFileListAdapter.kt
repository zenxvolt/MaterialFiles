package me.zhanghai.android.files.recentfiles

import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFileItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filelist.isArchiveFile
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
                        inflate(R.menu.file_item)
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
        // Mirror the visibility rules of the file list for a regular file, while only allowing to
        // show the file in its directory when picking files.
        val menu = holder.popupMenu.menu
        val isReadOnly = path.fileSystem.isReadOnly
        for (index in 0..<menu.size()) {
            val item = menu.getItem(index)
            item.isVisible = when (item.itemId) {
                R.id.action_show_in_directory -> true
                R.id.action_cut, R.id.action_delete, R.id.action_rename ->
                    !isPickMode && !isReadOnly
                R.id.action_extract -> !isPickMode && path.isArchiveFile(recentFile.mimeType)
                R.id.action_add_bookmark -> false
                else -> !isPickMode
            }
        }
        holder.popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_show_in_directory -> listener.showRecentFileInDirectory(recentFile)
                R.id.action_open_with -> listener.openRecentFileWith(recentFile)
                R.id.action_cut -> listener.cutRecentFile(recentFile)
                R.id.action_copy -> listener.copyRecentFile(recentFile)
                R.id.action_delete -> listener.confirmDeleteRecentFile(recentFile)
                R.id.action_rename -> listener.showRenameRecentFileDialog(recentFile)
                R.id.action_extract -> listener.extractRecentFile(recentFile)
                R.id.action_archive -> listener.showCreateArchiveDialog(recentFile)
                R.id.action_share -> listener.shareRecentFile(recentFile)
                R.id.action_copy_path -> listener.copyRecentFilePath(recentFile)
                R.id.action_create_shortcut -> listener.createRecentFileShortcut(recentFile)
                R.id.action_properties -> listener.showRecentFilePropertiesDialog(recentFile)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    class ViewHolder(val binding: RecentFileItemBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var popupMenu: PopupMenu
    }

    interface Listener {
        fun openRecentFile(recentFile: RecentFile)
        fun openRecentFileWith(recentFile: RecentFile)
        fun showRecentFileInDirectory(recentFile: RecentFile)
        fun cutRecentFile(recentFile: RecentFile)
        fun copyRecentFile(recentFile: RecentFile)
        fun confirmDeleteRecentFile(recentFile: RecentFile)
        fun showRenameRecentFileDialog(recentFile: RecentFile)
        fun extractRecentFile(recentFile: RecentFile)
        fun showCreateArchiveDialog(recentFile: RecentFile)
        fun shareRecentFile(recentFile: RecentFile)
        fun copyRecentFilePath(recentFile: RecentFile)
        fun createRecentFileShortcut(recentFile: RecentFile)
        fun showRecentFilePropertiesDialog(recentFile: RecentFile)
    }
}
