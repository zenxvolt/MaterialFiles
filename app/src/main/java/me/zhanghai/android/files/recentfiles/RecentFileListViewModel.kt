package me.zhanghai.android.files.recentfiles

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    init {
        reload()
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
}
