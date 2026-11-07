package me.zhanghai.android.files.recentfiles

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.appClassLoader
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.RecentFileListFragmentBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.ConfirmDeleteFilesDialogFragment
import me.zhanghai.android.files.filelist.CreateArchiveDialogFragment
import me.zhanghai.android.files.filelist.EditFileActivity
import me.zhanghai.android.files.filelist.FileItemSet
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.filelist.RenameFileDialogFragment
import me.zhanghai.android.files.filelist.isArchiveFile
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.ui.OverlayToolbarActionMode
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.ui.ToolbarActionMode
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.util.withChooser

class RecentFileListFragment : Fragment(), RecentFileListAdapter.Listener,
    ConfirmDeleteFilesDialogFragment.Listener, RenameFileDialogFragment.Listener,
    CreateArchiveDialogFragment.Listener {
    private val args by args<Args>()

    private val viewModel by viewModels { { RecentFileListViewModel() } }

    private lateinit var binding: RecentFileListFragmentBinding

    private lateinit var overlayActionMode: ToolbarActionMode

    private lateinit var adapter: RecentFileListAdapter

    private var isFirstStart = true

    // The directory where a file name dialog (rename or compress) creates its file, for checking
    // whether a file with the same name already exists.
    private var fileNameDialogDirectory: Path? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
        if (savedInstanceState != null) {
            savedInstanceState.classLoader = appClassLoader
            @Suppress("DEPRECATION")
            fileNameDialogDirectory =
                savedInstanceState.getParcelable<Parcelable>(STATE_FILE_NAME_DIALOG_DIRECTORY)
                    as Path?
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putParcelable(
            STATE_FILE_NAME_DIALOG_DIRECTORY, fileNameDialogDirectory as Parcelable?
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        RecentFileListFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        overlayActionMode = OverlayToolbarActionMode(binding.overlayToolbar, binding.toolbar)
        addOnBackPressedCallback(overlayActionMode.onBackPressedCallback)
        binding.recyclerView.layoutManager = LinearLayoutManager(
            activity, RecyclerView.VERTICAL, false
        )
        adapter = RecentFileListAdapter(args.isPickMode, this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView)
        )

        viewModel.recentFileListLiveData.observe(viewLifecycleOwner) {
            onRecentFileListChanged(it)
        }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) {
            onSelectedFilesChanged(it)
        }
    }

    override fun onStart() {
        super.onStart()

        // Files may have been added, modified or deleted while we were in the background, e.g.
        // after opening one of them in an editor.
        if (isFirstStart) {
            isFirstStart = false
        } else {
            viewModel.reload()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.recent_file_list, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_refresh -> {
                viewModel.reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun onRecentFileListChanged(stateful: Stateful<List<FileItem>>) {
        val recentFiles = stateful.value
        val isEmpty = recentFiles.isNullOrEmpty()
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && isEmpty)
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && isEmpty)
        if (stateful is Failure) {
            binding.errorText.text = stateful.throwable.toString()
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && isEmpty)
        adapter.replace(recentFiles ?: emptyList())
    }

    private fun onSelectedFilesChanged(files: FileItemSet) {
        updateOverlayToolbar()
        adapter.replaceSelectedFiles(files)
    }

    private fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            if (overlayActionMode.isActive) {
                overlayActionMode.finish()
            }
            return
        }
        overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
        overlayActionMode.setMenuResource(R.menu.file_list_select)
        val menu = overlayActionMode.menu
        val isAnyFileReadOnly = files.any { it.path.fileSystem.isReadOnly }
        menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
        menu.findItem(R.id.action_delete).isVisible = !isAnyFileReadOnly
        menu.findItem(R.id.action_extract).isVisible = files.all { it.isArchiveFile }
        // Recent files can be in different directories, and there's no current directory to put
        // the archive in, so only allow compressing files in the same directory.
        menu.findItem(R.id.action_archive).isVisible = getCommonParent(files) != null
        if (!overlayActionMode.isActive) {
            binding.appBarLayout.setExpanded(true)
            overlayActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onOverlayActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    viewModel.clearSelectedFiles()
                }
            })
        }
    }

    private fun onOverlayActionModeMenuItemClicked(item: MenuItem): Boolean {
        // Copy the set because actions may clear the selection.
        val files = viewModel.selectedFiles.toCollection(FileItemSet())
        when (item.itemId) {
            R.id.action_cut -> cutRecentFiles(files)
            R.id.action_copy -> copyRecentFiles(files)
            R.id.action_delete -> confirmDeleteRecentFiles(files)
            R.id.action_extract -> extractRecentFiles(files)
            R.id.action_archive -> showCreateArchiveDialog(files)
            R.id.action_share -> shareRecentFiles(files)
            R.id.action_select_all -> adapter.selectAllFiles()
            else -> return false
        }
        return true
    }

    private fun getCommonParent(files: FileItemSet): Path? {
        val parents = files.mapTo(mutableSetOf()) { it.path.parent }
        return parents.singleOrNull()
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openRecentFile(file: FileItem) {
        if (args.isPickMode) {
            // We can't open files while picking, so let the user pick it in its directory.
            showRecentFileInDirectory(file)
            return
        }
        openRecentFile(file, false)
    }

    override fun openRecentFileWith(file: FileItem) {
        openRecentFile(file, true)
    }

    private fun openRecentFile(file: FileItem, withChooser: Boolean) {
        val path = file.path
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) { path.exists() }
            if (!exists) {
                showToast(R.string.recent_file_not_found)
                viewModel.reload()
                return@launch
            }
            openFile(path, file.mimeType, withChooser)
        }
    }

    private fun openFile(path: Path, mimeType: MimeType, withChooser: Boolean) {
        val intent = path.fileProviderUri.createViewIntent(mimeType)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .apply { extraPath = path }
            .let {
                if (withChooser) {
                    it.withChooser(
                        EditFileActivity::class.createIntent()
                            .putArgs(EditFileActivity.Args(path, mimeType)),
                        OpenFileAsDialogActivity::class.createIntent()
                            .putArgs(OpenFileAsDialogFragment.Args(path))
                    )
                } else {
                    it
                }
            }
        startActivitySafe(intent)
    }

    override fun showRecentFileInDirectory(file: FileItem) {
        finishWithResult(
            RecentFileListActivity.Result(
                RecentFileListActivity.Result.Action.SHOW_IN_DIRECTORY, file.path, null
            )
        )
    }

    // Cut, copy and extract need a target directory, so hand them over to the file list, where
    // the files can then be pasted like any other files.
    override fun cutRecentFiles(files: FileItemSet) {
        finishWithFileListAction(files, RecentFileListActivity.Result.Action.CUT)
    }

    override fun copyRecentFiles(files: FileItemSet) {
        finishWithFileListAction(files, RecentFileListActivity.Result.Action.COPY)
    }

    override fun extractRecentFiles(files: FileItemSet) {
        finishWithFileListAction(files, RecentFileListActivity.Result.Action.EXTRACT)
    }

    private fun finishWithFileListAction(
        files: FileItemSet,
        action: RecentFileListActivity.Result.Action
    ) {
        finishWithResult(RecentFileListActivity.Result(action, null, files))
    }

    private fun finishWithResult(result: RecentFileListActivity.Result) {
        val activity = requireActivity()
        activity.setResult(
            Activity.RESULT_OK, RecentFileListActivity.createResultIntent(result)
        )
        activity.finish()
    }

    override fun confirmDeleteRecentFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, this)
    }

    override fun deleteFiles(files: FileItemSet) {
        FileJobService.delete(files.map { it.path }, requireContext())
        viewModel.selectFiles(files, false)
    }

    override fun showRenameRecentFileDialog(file: FileItem) {
        fileNameDialogDirectory = file.path.parent
        RenameFileDialogFragment.show(file, this)
    }

    override fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, requireContext())
        viewModel.selectFile(file, false)
    }

    override fun showCreateArchiveDialog(files: FileItemSet) {
        fileNameDialogDirectory = getCommonParent(files) ?: return
        CreateArchiveDialogFragment.show(files, this)
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        val directory = getCommonParent(files) ?: return
        FileJobService.archive(
            files.map { it.path }, directory.resolve(name), format, filter, password,
            requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    override fun hasFileWithName(name: String): Boolean {
        val directory = fileNameDialogDirectory ?: return false
        // Recent files are always local, so this is cheap enough for the main thread.
        return try {
            directory.resolve(name).exists()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun shareRecentFiles(files: FileItemSet) {
        val intent = files.map { it.path.fileProviderUri }
            .createSendStreamIntent(files.map { it.mimeType })
            .withChooser()
        startActivitySafe(intent)
        viewModel.selectFiles(files, false)
    }

    override fun copyRecentFilePath(file: FileItem) {
        clipboardManager.copyText(file.path.toUserFriendlyString(), requireContext())
    }

    override fun createRecentFileShortcut(file: FileItem) {
        val context = requireContext()
        val path = file.path
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(OpenFileActivity.createIntent(path, file.mimeType))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.file_shortcut_icon))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showRecentFilePropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, this)
    }

    companion object {
        private val STATE_FILE_NAME_DIALOG_DIRECTORY =
            "${RecentFileListFragment::class.java.name}.state.FILE_NAME_DIALOG_DIRECTORY"
    }

    @Parcelize
    class Args(val isPickMode: Boolean) : ParcelableArgs
}
