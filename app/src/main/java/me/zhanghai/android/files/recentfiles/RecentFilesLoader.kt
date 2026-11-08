package me.zhanghai.android.files.recentfiles

import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.WorkerThread
import java8.nio.file.Paths
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * A recent file, with the time it was last added or modified, in milliseconds.
 */
class RecentFile(val file: FileItem, val recentTime: Long)

/**
 * Recent files, along with what they were loaded from, for checking whether they need to be
 * reloaded.
 */
class RecentFilesResult(val recentFiles: List<RecentFile>, val changeKey: RecentFilesChangeKey?)

/**
 * A cheap snapshot of what recent files depend on, which changes whenever they may have changed.
 *
 * @see RecentFilesLoader.getChangeKey
 */
data class RecentFilesChangeKey(
    // Whether a file is from today changes at midnight.
    val epochDay: Long,
    val showHiddenFiles: Boolean,
    // Changes whenever MediaStore changes.
    val mediaStoreGenerations: Map<String, Long>,
    // The modification time of a directory changes whenever a file is added to, removed from or
    // renamed in it.
    val downloadsDirectoryModifiedTimes: Map<String, Long>
)

/**
 * Loads the files most recently added or modified on the device.
 *
 * Files are mostly found from MediaStore, which is the system's index of files on shared storage.
 * This works right after the app is installed and is much faster than walking the file system. The
 * downloads directory is also scanned directly, because new files there may not be in MediaStore
 * yet (or at all).
 *
 * A file is recent if it was recently added or modified. Downloaded files may keep the modification
 * time from the server, and extracted or copied files may keep the one of their source, so the
 * modification time alone isn't enough. So we also use the time it was added to MediaStore, and for
 * files in the downloads directory (which may not be in MediaStore), the status change time of the
 * file, which is updated when a file is written to, renamed or has its modification time changed.
 * The status change time isn't used for other files, because it's also updated when moving or
 * renaming a directory of old files, which would then flood recent files.
 */
object RecentFilesLoader {
    private const val MAX_SIZE = 100

    // The number of candidates to take from MediaStore for each sort order, leaving room for files
    // that turn out to be missing or empty.
    private const val MAX_MEDIA_STORE_CANDIDATE_COUNT_PER_ORDER = 300

    // Guard against scanning the whole index when most entries are filtered out.
    private const val MAX_MEDIA_STORE_SCANNED_ROW_COUNT_PER_ORDER = 5000

    // Include files in subdirectories of the downloads directory, e.g. those of messaging apps.
    private const val MAX_DOWNLOADS_SCAN_DEPTH = 2

    private const val MAX_DOWNLOADS_SCANNED_FILE_COUNT = 5000

    // MediaStore.Files.FileColumns.FORMAT is hidden from the SDK, but the column is available.
    private const val COLUMN_FORMAT = "format"

    // @see android.mtp.MtpConstants.FORMAT_ASSOCIATION
    private const val FORMAT_ASSOCIATION = 0x3001

    /**
     * Returns a key for checking whether recent files may have changed since they were loaded, or
     * `null` if that can't be checked cheaply.
     *
     * @param lastChangeKey the key of the last load, for the downloads directories to check
     */
    @WorkerThread
    fun getChangeKey(
        showHiddenFiles: Boolean,
        lastChangeKey: RecentFilesChangeKey?
    ): RecentFilesChangeKey? {
        val mediaStoreGenerations = getMediaStoreGenerations() ?: return null
        // A new subdirectory changes the modification time of its parent, so checking the
        // directories we scanned last time is enough.
        val downloadsDirectoryModifiedTimes = lastChangeKey?.downloadsDirectoryModifiedTimes?.keys
            ?.associateWith { File(it).lastModified() }
            ?: emptyMap()
        return RecentFilesChangeKey(
            LocalDate.now().toEpochDay(), showHiddenFiles, mediaStoreGenerations,
            downloadsDirectoryModifiedTimes
        )
    }

    private fun getMediaStoreGenerations(): Map<String, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return try {
            MediaStore.getExternalVolumeNames(application).associateWith {
                MediaStore.getGeneration(application, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @WorkerThread
    fun load(showHiddenFiles: Boolean): RecentFilesResult {
        // Get the generations before loading, so that changes during loading cause a reload.
        val mediaStoreGenerations = getMediaStoreGenerations()
        // Times in the future (with a day of tolerance for time zone issues) are ignored, so that
        // files with a wrong time don't stick to the top.
        val maxTimeSeconds =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(1)
        // Paths to their time in MediaStore, or 0 if unknown, in seconds.
        val candidates = mutableMapOf<String, Long>()
        // Sorting by the maximum of both times isn't reliably supported by MediaStore, so we query
        // the most recent files by each, which together contain the most recent files by the
        // maximum.
        for (sortColumn in listOf(
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED
        )) {
            queryMediaStoreCandidates(sortColumn, showHiddenFiles, maxTimeSeconds, candidates)
        }
        val downloadsDirectoryModifiedTimes = mutableMapOf<String, Long>()
        val downloadsDirectory = scanDownloadsCandidates(
            showHiddenFiles, candidates, downloadsDirectoryModifiedTimes
        )
        val downloadsDirectoryPrefix = downloadsDirectory.path + File.separator
        // Use the actual file to check whether it's still a non-empty regular file, and to get its
        // modification and status change times.
        val recentTimes = mutableListOf<Pair<String, Long>>()
        for ((path, mediaStoreTimeSeconds) in candidates) {
            val stat = try {
                Os.stat(path)
            } catch (e: ErrnoException) {
                continue
            }
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size == 0L) {
                continue
            }
            val times = mutableListOf(mediaStoreTimeSeconds, stat.st_mtime)
            if (path.startsWith(downloadsDirectoryPrefix)) {
                times += stat.st_ctime
            }
            val recentTimeSeconds = times
                .filter { it in 1..maxTimeSeconds }
                .maxOrNull()
                ?: continue
            recentTimes += path to recentTimeSeconds
        }
        recentTimes.sortByDescending { it.second }
        val recentFiles = mutableListOf<RecentFile>()
        for ((path, recentTimeSeconds) in recentTimes) {
            if (recentFiles.size >= MAX_SIZE) {
                break
            }
            // Load the file the same way as the file list does, so that we get the same MIME type,
            // thumbnail and badges.
            val file = try {
                Paths.get(path).loadFileItem()
            } catch (e: IOException) {
                continue
            }
            if (file.attributes.isDirectory) {
                continue
            }
            recentFiles += RecentFile(file, TimeUnit.SECONDS.toMillis(recentTimeSeconds))
        }
        val changeKey = mediaStoreGenerations?.let {
            RecentFilesChangeKey(
                LocalDate.now().toEpochDay(), showHiddenFiles, it, downloadsDirectoryModifiedTimes
            )
        }
        return RecentFilesResult(recentFiles, changeKey)
    }

    private fun queryMediaStoreCandidates(
        sortColumn: String,
        showHiddenFiles: Boolean,
        maxTimeSeconds: Long,
        candidates: MutableMap<String, Long>
    ) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        // Exclude directories, and files with a time in the future. Columns may be null for files
        // that MediaStore hasn't fully scanned, so we shouldn't exclude them for that, and we check
        // the size of the actual file later instead.
        val selection = "($COLUMN_FORMAT IS NULL OR $COLUMN_FORMAT != ?)" +
            " AND ($sortColumn IS NULL OR $sortColumn <= ?)"
        val selectionArgs = arrayOf(FORMAT_ASSOCIATION.toString(), maxTimeSeconds.toString())
        val sortOrder = "$sortColumn DESC"
        val cursor = try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            // Don't fail completely if MediaStore is unavailable, as we also scan downloads.
            e.printStackTrace()
            null
        } ?: return
        cursor.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val dateModifiedColumn =
                it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            var candidateCount = 0
            var scannedRowCount = 0
            while (candidateCount < MAX_MEDIA_STORE_CANDIDATE_COUNT_PER_ORDER &&
                scannedRowCount < MAX_MEDIA_STORE_SCANNED_ROW_COUNT_PER_ORDER && it.moveToNext()) {
                ++scannedRowCount
                val data = it.getString(dataColumn)
                if (data.isNullOrEmpty()) {
                    continue
                }
                if (!showHiddenFiles && isInHiddenPath(data)) {
                    continue
                }
                // Getting a null column as long returns 0, and times in the future are ignored
                // later.
                val dateAdded = it.getLong(dateAddedColumn)
                val dateModified = it.getLong(dateModifiedColumn)
                val time = listOf(dateAdded, dateModified).filter { time ->
                    time <= maxTimeSeconds
                }.maxOrNull() ?: 0
                candidates[data] = maxOf(candidates[data] ?: 0, time)
                ++candidateCount
            }
        }
    }

    /**
     * @return the downloads directory
     */
    private fun scanDownloadsCandidates(
        showHiddenFiles: Boolean,
        candidates: MutableMap<String, Long>,
        directoryModifiedTimes: MutableMap<String, Long>
    ): File {
        @Suppress("DEPRECATION")
        val downloadsDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var scannedFileCount = 0
        fun scan(directory: File, depth: Int) {
            // Get the modification time before listing, so that changes during listing cause a
            // reload.
            directoryModifiedTimes[directory.path] = directory.lastModified()
            val files = directory.listFiles() ?: return
            for (file in files) {
                if (scannedFileCount >= MAX_DOWNLOADS_SCANNED_FILE_COUNT) {
                    return
                }
                ++scannedFileCount
                if (!showHiddenFiles && file.name.startsWith('.')) {
                    continue
                }
                if (file.isDirectory) {
                    if (depth < MAX_DOWNLOADS_SCAN_DEPTH) {
                        scan(file, depth + 1)
                    }
                } else if (file.path !in candidates) {
                    candidates[file.path] = 0
                }
            }
        }
        scan(downloadsDirectory, 0)
        return downloadsDirectory
    }

    private fun isInHiddenPath(path: String): Boolean =
        path.split('/').any { it.startsWith('.') }
}
