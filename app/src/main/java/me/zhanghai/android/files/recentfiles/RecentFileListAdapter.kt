package me.zhanghai.android.files.recentfiles

import android.text.TextUtils
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.compat.isSingleLineCompat
import me.zhanghai.android.files.databinding.FileItemListBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filelist.FileItemSet
import me.zhanghai.android.files.filelist.appDirectoryPackageName
import me.zhanghai.android.files.filelist.fileItemSetOf
import me.zhanghai.android.files.filelist.isArchiveFile
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.supportsThumbnail
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.CheckableItemBackground
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.valueCompat

/**
 * Mirrors [me.zhanghai.android.files.filelist.FileListAdapter] in list view, using the same item
 * layout for the same look, thumbnails and selection behavior.
 */
class RecentFileListAdapter(
    private val isPickMode: Boolean,
    private val listener: Listener
) : SimpleAdapter<FileItem, RecentFileListAdapter.ViewHolder>() {
    // Paths are unique in the list, but their hash codes aren't guaranteed to be.
    override val hasStableIds: Boolean
        get() = false

    private val selectedFiles = fileItemSetOf()

    fun replaceSelectedFiles(files: FileItemSet) {
        val changedFiles = fileItemSetOf()
        val iterator = selectedFiles.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file !in files) {
                iterator.remove()
                changedFiles.add(file)
            }
        }
        for (file in files) {
            if (file !in selectedFiles) {
                selectedFiles.add(file)
                changedFiles.add(file)
            }
        }
        if (changedFiles.isEmpty()) {
            return
        }
        for (index in 0..<itemCount) {
            if (getItem(index) in changedFiles) {
                notifyItemChanged(index, PAYLOAD_STATE_CHANGED)
            }
        }
    }

    fun selectAllFiles() {
        listener.selectFiles(list.toCollection(fileItemSetOf()), true)
    }

    private fun selectFile(file: FileItem) {
        // Selection mode isn't available when picking files, where the user picks the file in its
        // directory instead.
        if (isPickMode) {
            return
        }
        listener.selectFile(file, file !in selectedFiles)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(FileItemListBinding.inflate(parent.context.layoutInflater, parent, false))
            .apply {
                binding.itemLayout.background =
                    CheckableItemBackground.create(0f, 0f, binding.itemLayout.context)
                popupMenu = PopupMenu(binding.menuButton.context, binding.menuButton)
                    .apply {
                        inflate(R.menu.recent_file_item)
                        inflate(R.menu.file_item)
                    }
                binding.menuButton.setOnClickListener { popupMenu.show() }
            }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        throw UnsupportedOperationException()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val file = getItem(position)
        val binding = holder.binding
        binding.itemLayout.isChecked = file in selectedFiles
        binding.nameText.apply {
            if (isSingleLineCompat) {
                val nameEllipsize = Settings.FILE_NAME_ELLIPSIZE.valueCompat
                ellipsize = nameEllipsize
                isSelected = nameEllipsize == TextUtils.TruncateAt.MARQUEE
            }
        }
        if (payloads.isNotEmpty()) {
            return
        }
        binding.itemLayout.apply {
            setOnClickListener {
                if (selectedFiles.isEmpty()) {
                    listener.openRecentFile(file)
                } else {
                    selectFile(file)
                }
            }
            setOnLongClickListener {
                when {
                    isPickMode -> holder.popupMenu.show()
                    selectedFiles.isEmpty() -> selectFile(file)
                    else -> listener.openRecentFile(file)
                }
                true
            }
        }
        binding.iconLayout.setOnClickListener { selectFile(file) }
        val path = file.path
        val attributes = file.attributes
        binding.iconImage.apply {
            isVisible = true
            setImageResource(file.mimeType.iconRes)
        }
        binding.thumbnailImage.apply {
            dispose()
            setImageDrawable(null)
            val supportsThumbnail = file.supportsThumbnail
            isVisible = supportsThumbnail
            if (supportsThumbnail) {
                load(path to attributes) {
                    listener { _, _ -> binding.iconImage.isVisible = false }
                }
            }
        }
        binding.appIconBadgeImage.apply {
            dispose()
            setImageDrawable(null)
            val appDirectoryPackageName = file.appDirectoryPackageName
            val hasAppIconBadge = appDirectoryPackageName != null
            isVisible = hasAppIconBadge
            if (hasAppIconBadge) {
                load(AppIconPackageName(appDirectoryPackageName!!))
            }
        }
        binding.badgeImage.apply {
            val badgeIconRes = if (file.attributesNoFollowLinks.isSymbolicLink) {
                if (file.isSymbolicLinkBroken) {
                    R.drawable.error_badge_icon_18dp
                } else {
                    R.drawable.symbolic_link_badge_icon_18dp
                }
            } else if (file.attributesNoFollowLinks.isEncrypted()) {
                R.drawable.encrypted_badge_icon_18dp
            } else {
                null
            }
            val hasBadge = badgeIconRes != null
            isVisible = hasBadge
            if (hasBadge) {
                setImageResource(badgeIconRes!!)
            } else {
                setImageDrawable(null)
            }
        }
        val context = binding.root.context
        binding.nameText.text = file.name
        val timeString = DateUtils.getRelativeTimeSpanString(
            attributes.lastModifiedTime().toMillis(), System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        )
        val sizeString = attributes.fileSize.formatHumanReadable(context)
        val directoryString = path.parent?.toUserFriendlyString()
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
                R.id.action_extract -> !isPickMode && file.isArchiveFile
                R.id.action_add_bookmark -> false
                else -> !isPickMode
            }
        }
        holder.popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_show_in_directory -> listener.showRecentFileInDirectory(file)
                R.id.action_open_with -> listener.openRecentFileWith(file)
                R.id.action_cut -> listener.cutRecentFiles(fileItemSetOf(file))
                R.id.action_copy -> listener.copyRecentFiles(fileItemSetOf(file))
                R.id.action_delete -> listener.confirmDeleteRecentFiles(fileItemSetOf(file))
                R.id.action_rename -> listener.showRenameRecentFileDialog(file)
                R.id.action_extract -> listener.extractRecentFiles(fileItemSetOf(file))
                R.id.action_archive -> listener.showCreateArchiveDialog(fileItemSetOf(file))
                R.id.action_share -> listener.shareRecentFiles(fileItemSetOf(file))
                R.id.action_copy_path -> listener.copyRecentFilePath(file)
                R.id.action_create_shortcut -> listener.createRecentFileShortcut(file)
                R.id.action_properties -> listener.showRecentFilePropertiesDialog(file)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    companion object {
        private val PAYLOAD_STATE_CHANGED = Any()
    }

    class ViewHolder(val binding: FileItemListBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var popupMenu: PopupMenu
    }

    interface Listener {
        fun selectFile(file: FileItem, selected: Boolean)
        fun selectFiles(files: FileItemSet, selected: Boolean)
        fun openRecentFile(file: FileItem)
        fun openRecentFileWith(file: FileItem)
        fun showRecentFileInDirectory(file: FileItem)
        fun cutRecentFiles(files: FileItemSet)
        fun copyRecentFiles(files: FileItemSet)
        fun confirmDeleteRecentFiles(files: FileItemSet)
        fun showRenameRecentFileDialog(file: FileItem)
        fun extractRecentFiles(files: FileItemSet)
        fun showCreateArchiveDialog(files: FileItemSet)
        fun shareRecentFiles(files: FileItemSet)
        fun copyRecentFilePath(file: FileItem)
        fun createRecentFileShortcut(file: FileItem)
        fun showRecentFilePropertiesDialog(file: FileItem)
    }
}
