package me.zhanghai.android.files.recentfiles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileItemSet
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.getParcelableExtraSafe
import me.zhanghai.android.files.util.putArgs

class RecentFileListActivity : AppActivity() {
    private val args by args<RecentFileListFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                add(android.R.id.content, RecentFileListFragment().putArgs(args))
            }
        }
    }

    /**
     * An action that needs the file list, e.g. a target directory to paste into.
     */
    @Parcelize
    class Result(
        val action: Action,
        val path: @WriteWith<ParcelableParceler> Path?,
        val files: FileItemSet?
    ) : Parcelable {
        init {
            require(if (action == Action.SHOW_IN_DIRECTORY) path != null else files != null)
        }

        enum class Action {
            SHOW_IN_DIRECTORY,
            CUT,
            COPY,
            EXTRACT
        }
    }

    /**
     * Input is whether the file list is in pick mode.
     */
    class Contract : ActivityResultContract<Boolean, Result?>() {
        override fun createIntent(context: Context, input: Boolean): Intent =
            RecentFileListActivity::class.createIntent()
                .putArgs(RecentFileListFragment.Args(input))

        override fun parseResult(resultCode: Int, intent: Intent?): Result? =
            if (resultCode == RESULT_OK) intent?.getParcelableExtraSafe(EXTRA_RESULT) else null
    }

    companion object {
        private val EXTRA_RESULT = "${RecentFileListActivity::class.java.name}.extra.RESULT"

        fun createResultIntent(result: Result): Intent = Intent().putExtra(EXTRA_RESULT, result)
    }
}
