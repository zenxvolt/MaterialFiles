package me.zhanghai.android.files.recentfiles

import android.database.ContentObserver
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Recent files on the device for the file list, optionally filtered by a search query.
 *
 * @see me.zhanghai.android.files.filelist.FileListLiveData
 */
class RecentFileListLiveData(
    private val query: String?
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val handler = Handler(Looper.getMainLooper())

    private val reloadRunnable = Runnable { onChangeObserved() }

    // MediaStore is updated by our file jobs (and other apps), so this keeps the list in sync after
    // e.g. deleting or renaming a file.
    private val mediaStoreObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Changes usually come in bursts, e.g. when copying multiple files.
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MILLIS)
        }
    }

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, mediaStoreObserver
        )
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                var files = RecentFilesLoader.load(showHiddenFiles)
                if (query != null) {
                    files = files.filter { it.name.contains(query, true) }
                }
                Success(files)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        handler.removeCallbacks(reloadRunnable)
        contentResolver.unregisterContentObserver(mediaStoreObserver)
        future?.cancel(true)
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MILLIS = 1000L
    }
}
