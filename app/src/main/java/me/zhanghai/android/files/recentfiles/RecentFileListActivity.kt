package me.zhanghai.android.files.recentfiles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
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
     * Input is whether the file list is in pick mode, and output is the recent file that the user
     * wants to show in its directory, if any.
     */
    class ShowInDirectoryContract : ActivityResultContract<Boolean, Path?>() {
        override fun createIntent(context: Context, input: Boolean): Intent =
            RecentFileListActivity::class.createIntent()
                .putArgs(RecentFileListFragment.Args(input))

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
