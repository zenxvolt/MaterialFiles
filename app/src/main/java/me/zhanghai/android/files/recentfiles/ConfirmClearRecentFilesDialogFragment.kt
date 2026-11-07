package me.zhanghai.android.files.recentfiles

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.show

class ConfirmClearRecentFilesDialogFragment : AppCompatDialogFragment() {
    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setMessage(R.string.recent_file_list_clear_message)
            .setPositiveButton(R.string.recent_file_list_clear_positive) { _, _ ->
                listener.clearRecentFiles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    companion object {
        fun show(fragment: Fragment) {
            ConfirmClearRecentFilesDialogFragment().show(fragment)
        }
    }

    interface Listener {
        fun clearRecentFiles()
    }
}
