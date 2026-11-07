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
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFileListFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.EditFileActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.util.withChooser

class RecentFileListFragment : Fragment(), RecentFileListAdapter.Listener {
    private val args by args<Args>()

    private val viewModel by viewModels { { RecentFileListViewModel() } }

    private lateinit var binding: RecentFileListFragmentBinding

    private lateinit var adapter: RecentFileListAdapter

    private var isFirstStart = true

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
        val activity = requireActivity()
        activity.setResult(Activity.RESULT_OK, Intent().apply { extraPath = recentFile.path })
        activity.finish()
    }

    @Parcelize
    class Args(val isPickMode: Boolean) : ParcelableArgs
}
