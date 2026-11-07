package me.zhanghai.android.files.recentfiles

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat

class RecentFileListViewModel : ViewModel() {
    private val _recentFileListLiveData = MutableLiveData<Stateful<List<RecentFile>>>()
    val recentFileListLiveData: LiveData<Stateful<List<RecentFile>>>
        get() = _recentFileListLiveData

    private var loadJob: Job? = null

    private var reloadDebounceJob: Job? = null

    // MediaStore is updated by our file jobs (and other apps), so this keeps the list in sync after
    // e.g. deleting or renaming a file.
    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reloadDebounceJob?.cancel()
            reloadDebounceJob = viewModelScope.launch {
                // Changes usually come in bursts, e.g. when copying multiple files.
                delay(RELOAD_DEBOUNCE_MILLIS)
                reload()
            }
        }
    }

    init {
        reload()
        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, mediaStoreObserver
        )
    }

    override fun onCleared() {
        contentResolver.unregisterContentObserver(mediaStoreObserver)
    }

    fun reload() {
        loadJob?.cancel()
        val oldRecentFiles = _recentFileListLiveData.value?.value
        _recentFileListLiveData.value = Loading(oldRecentFiles)
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        loadJob = viewModelScope.launch {
            _recentFileListLiveData.value = try {
                Success(withContext(Dispatchers.IO) { RecentFilesLoader.load(showHiddenFiles) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                Failure(oldRecentFiles, e)
            }
        }
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MILLIS = 1000L
    }
}
