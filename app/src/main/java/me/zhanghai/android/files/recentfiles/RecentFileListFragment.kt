package me.zhanghai.android.files.recentfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFileListFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.EditFileActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.withChooser

class RecentFileListFragment : Fragment(), RecentFileListAdapter.Listener,
    ConfirmClearRecentFilesDialogFragment.Listener {
    private val args by args<Args>()

    private lateinit var binding: RecentFileListFragmentBinding

    private lateinit var adapter: RecentFileListAdapter

    private var clearMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
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

        Settings.RECENT_FILES.observe(viewLifecycleOwner) { onRecentFileListChanged(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.recent_file_list, menu)
        clearMenuItem = menu.findItem(R.id.action_clear)
        updateOptionsMenu()
    }

    override fun onDestroyOptionsMenu() {
        super.onDestroyOptionsMenu()

        clearMenuItem = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_clear -> {
                ConfirmClearRecentFilesDialogFragment.show(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun onRecentFileListChanged(recentFiles: List<RecentFile>) {
        binding.emptyView.fadeToVisibilityUnsafe(recentFiles.isEmpty())
        adapter.replace(recentFiles)
        updateOptionsMenu()
    }

    private fun updateOptionsMenu() {
        clearMenuItem?.isVisible = Settings.RECENT_FILES.valueCompat.isNotEmpty()
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
        val mimeType = recentFile.mimeType
        viewLifecycleOwner.lifecycleScope.launch {
            // Files.notExists() only returns true when the file is known to be missing, so that we
            // don't drop files that are temporarily inaccessible (e.g. on a remote server).
            val isMissing = withContext(Dispatchers.IO) {
                try {
                    Files.notExists(path)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            if (isMissing) {
                showToast(R.string.recent_file_not_found)
                RecentFiles.remove(path)
                return@launch
            }
            RecentFiles.add(path, mimeType)
            openFile(path, mimeType, withChooser)
        }
    }

    private fun openFile(path: Path, mimeType: MimeType, withChooser: Boolean) {
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, withChooser, requireContext())
            return
        }
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
        val activity = requireActivity()
        activity.setResult(Activity.RESULT_OK, Intent().apply { extraPath = recentFile.path })
        activity.finish()
    }

    override fun removeRecentFile(recentFile: RecentFile) {
        RecentFiles.remove(recentFile.path)
    }

    override fun clearRecentFiles() {
        RecentFiles.clear()
    }

    @Parcelize
    class Args(val isPickMode: Boolean) : ParcelableArgs
}
