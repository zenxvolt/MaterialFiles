package me.zhanghai.android.files.recentfiles

import androidx.annotation.MainThread
import java8.nio.file.Path
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object RecentFiles {
    // Keep the list small since it's stored as a parcel in shared preferences.
    private const val MAX_SIZE = 50

    val isEnabled: Boolean
        get() = Settings.RECENT_FILES_ENABLED.valueCompat

    @MainThread
    fun add(path: Path, mimeType: MimeType) {
        if (!isEnabled || mimeType == MimeType.DIRECTORY) {
            return
        }
        val recentFile = RecentFile(path, mimeType, System.currentTimeMillis())
        val recentFiles = Settings.RECENT_FILES.valueCompat.toMutableList().apply {
            removeAll { it.path == path }
            add(0, recentFile)
            while (size > MAX_SIZE) {
                removeAt(lastIndex)
            }
        }
        Settings.RECENT_FILES.putValue(recentFiles)
    }

    @MainThread
    fun remove(path: Path) {
        val oldRecentFiles = Settings.RECENT_FILES.valueCompat
        val recentFiles = oldRecentFiles.filter { it.path != path }
        if (recentFiles.size != oldRecentFiles.size) {
            Settings.RECENT_FILES.putValue(recentFiles)
        }
    }

    @MainThread
    fun clear() {
        if (Settings.RECENT_FILES.valueCompat.isNotEmpty()) {
            Settings.RECENT_FILES.putValue(emptyList())
        }
    }
}
