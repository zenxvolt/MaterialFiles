package me.zhanghai.android.files.recentfiles

import android.database.ContentObserver
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java8.nio.file.Path
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

    /**
     * The time each recent file was last added or modified, which is what the list is ordered by,
     * and may differ from the last modified time of the file.
     */
    @Volatile
    var recentFileTimes: Map<Path, Long> = emptyMap()
        private set

    // MediaStore is updated by our file jobs and other apps, e.g. when a download finishes, so this
    // keeps the list in sync.
    private val mediaStoreObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Changes usually come in bursts, e.g. when copying multiple files.
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MILLIS)
        }
    }

    private var hasBeenActive = false

    // The change key of the last successful load, for skipping automatic reloads when nothing
    // changed.
    @Volatile
    private var lastChangeKey: RecentFilesChangeKey? = null

    init {
        loadValue()
        // Observe all of MediaStore, because changes may only be notified for a specific volume
        // (e.g. "external_primary") or collection (e.g. downloads), but not "external".
        contentResolver.registerContentObserver(
            Uri.parse("content://${MediaStore.AUTHORITY}"), true, mediaStoreObserver
        )
    }

    /**
     * @param isSilent Whether this is an automatic reload, which keeps showing the current list
     * while loading, and is skipped if nothing changed.
     */
    fun loadValue(isSilent: Boolean = false) {
        // Don't skip when replacing a load in progress, which may have never finished otherwise.
        val canSkip = isSilent && value !is Loading
        future?.cancel(true)
        if (!isSilent || value == null) {
            value = Loading(value?.value)
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val lastChangeKey = lastChangeKey
            if (canSkip && lastChangeKey != null &&
                RecentFilesLoader.getChangeKey(showHiddenFiles) == lastChangeKey) {
                return@submit
            }
            val value = try {
                val result = RecentFilesLoader.load(showHiddenFiles)
                this.lastChangeKey = result.changeKey
                var recentFiles = result.recentFiles
                if (query != null) {
                    recentFiles = recentFiles.filter { it.file.name.contains(query, true) }
                }
                recentFileTimes = recentFiles.associate { it.file.path to it.recentTime }
                Success(recentFiles.map { it.file })
            } catch (e: Exception) {
                this.lastChangeKey = null
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun onChangeObserved() {
        // Changes while inactive are picked up when becoming active again.
        if (hasActiveObservers()) {
            loadValue(true)
        }
    }

    override fun onActive() {
        // Reload when coming back (except for the first time, when we've just loaded), because
        // changes while inactive aren't reloaded. This is skipped if MediaStore didn't change.
        if (hasBeenActive) {
            loadValue(true)
        }
        hasBeenActive = true
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
