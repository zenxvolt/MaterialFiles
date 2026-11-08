package me.zhanghai.android.files.recentfiles

import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.WorkerThread
import java8.nio.file.Paths
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A recent file, with the time it was last added or modified, in milliseconds.
 */
class RecentFile(val file: FileItem, val recentTime: Long)

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
 * modification time alone isn't enough. So we also use the time it was added to MediaStore, and the
 * status change time of the file, which is updated when a file is written to, renamed or has its
 * modification time changed.
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

    @WorkerThread
    fun load(showHiddenFiles: Boolean): List<RecentFile> {
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
        scanDownloadsCandidates(showHiddenFiles, candidates)
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
            val recentTimeSeconds = listOf(mediaStoreTimeSeconds, stat.st_mtime, stat.st_ctime)
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
        return recentFiles
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

    private fun scanDownloadsCandidates(
        showHiddenFiles: Boolean,
        candidates: MutableMap<String, Long>
    ) {
        @Suppress("DEPRECATION")
        val downloadsDirectory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var scannedFileCount = 0
        fun scan(directory: File, depth: Int) {
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
    }

    private fun isInHiddenPath(path: String): Boolean =
        path.split('/').any { it.startsWith('.') }
}
