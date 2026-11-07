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
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.ConfirmDeleteFilesDialogFragment
import me.zhanghai.android.files.filelist.CreateArchiveDialogFragment
import me.zhanghai.android.files.filelist.EditFileActivity
import me.zhanghai.android.files.filelist.FileItemSet
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.filelist.RenameFileDialogFragment
import me.zhanghai.android.files.filelist.fileItemSetOf
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
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
import java.io.IOException

class RecentFileListFragment : Fragment(), RecentFileListAdapter.Listener,
    ConfirmDeleteFilesDialogFragment.Listener, RenameFileDialogFragment.Listener,
    CreateArchiveDialogFragment.Listener {
    private val args by args<Args>()

    private val viewModel by viewModels { { RecentFileListViewModel() } }

    private lateinit var binding: RecentFileListFragmentBinding

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

    private fun onRecentFileListChanged(stateful: Stateful<List<RecentFile>>) {
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

    override fun openRecentFile(recentFile: RecentFile) {
        if (args.isPickMode) {
            // We can't open files while picking, so let the user pick it in its directory.
            showRecentFileInDirectory(recentFile)
            return
        }
        openRecentFile(recentFile, false)
    }

    override fun openRecentFileWith(recentFile: RecentFile) {
        openRecentFile(recentFile, true)
    }

    private fun openRecentFile(recentFile: RecentFile, withChooser: Boolean) {
        val path = recentFile.path
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) { path.exists() }
            if (!exists) {
                showToast(R.string.recent_file_not_found)
                viewModel.reload()
                return@launch
            }
            openFile(path, recentFile.mimeType, withChooser)
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

    override fun showRecentFileInDirectory(recentFile: RecentFile) {
        finishWithResult(
            RecentFileListActivity.Result(
                RecentFileListActivity.Result.Action.SHOW_IN_DIRECTORY, recentFile.path, null
            )
        )
    }

    // Cut, copy and extract need a target directory, so hand them over to the file list, where
    // the file can then be pasted like any other file.
    override fun cutRecentFile(recentFile: RecentFile) {
        finishWithFileListAction(recentFile, RecentFileListActivity.Result.Action.CUT)
    }

    override fun copyRecentFile(recentFile: RecentFile) {
        finishWithFileListAction(recentFile, RecentFileListActivity.Result.Action.COPY)
    }

    override fun extractRecentFile(recentFile: RecentFile) {
        finishWithFileListAction(recentFile, RecentFileListActivity.Result.Action.EXTRACT)
    }

    private fun finishWithFileListAction(
        recentFile: RecentFile,
        action: RecentFileListActivity.Result.Action
    ) {
        withFileItem(recentFile) {
            finishWithResult(RecentFileListActivity.Result(action, recentFile.path, it))
        }
    }

    private fun finishWithResult(result: RecentFileListActivity.Result) {
        val activity = requireActivity()
        activity.setResult(
            Activity.RESULT_OK, RecentFileListActivity.createResultIntent(result)
        )
        activity.finish()
    }

    override fun confirmDeleteRecentFile(recentFile: RecentFile) {
        withFileItem(recentFile) { ConfirmDeleteFilesDialogFragment.show(fileItemSetOf(it), this) }
    }

    override fun deleteFiles(files: FileItemSet) {
        FileJobService.delete(files.map { it.path }, requireContext())
    }

    override fun showRenameRecentFileDialog(recentFile: RecentFile) {
        withFileItem(recentFile) {
            fileNameDialogDirectory = recentFile.path.parent
            RenameFileDialogFragment.show(it, this)
        }
    }

    override fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, requireContext())
    }

    override fun showCreateArchiveDialog(recentFile: RecentFile) {
        withFileItem(recentFile) {
            fileNameDialogDirectory = recentFile.path.parent
            CreateArchiveDialogFragment.show(fileItemSetOf(it), this)
        }
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        val archiveFile = files.first().path.resolveSibling(name)
        FileJobService.archive(
            files.map { it.path }, archiveFile, format, filter, password, requireContext()
        )
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

    override fun shareRecentFile(recentFile: RecentFile) {
        val intent = listOf(recentFile.path.fileProviderUri)
            .createSendStreamIntent(listOf(recentFile.mimeType))
            .withChooser()
        startActivitySafe(intent)
    }

    override fun copyRecentFilePath(recentFile: RecentFile) {
        clipboardManager.copyText(recentFile.path.toUserFriendlyString(), requireContext())
    }

    override fun createRecentFileShortcut(recentFile: RecentFile) {
        val context = requireContext()
        val path = recentFile.path
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(OpenFileActivity.createIntent(path, recentFile.mimeType))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.file_shortcut_icon))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showRecentFilePropertiesDialog(recentFile: RecentFile) {
        withFileItem(recentFile) { FilePropertiesDialogFragment.show(it, this) }
    }

    private fun withFileItem(recentFile: RecentFile, block: (FileItem) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    recentFile.path.loadFileItem()
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }
            if (file == null) {
                showToast(R.string.recent_file_not_found)
                viewModel.reload()
                return@launch
            }
            block(file)
        }
    }

    companion object {
        private val STATE_FILE_NAME_DIALOG_DIRECTORY =
            "${RecentFileListFragment::class.java.name}.state.FILE_NAME_DIALOG_DIRECTORY"
    }

    @Parcelize
    class Args(val isPickMode: Boolean) : ParcelableArgs
}
