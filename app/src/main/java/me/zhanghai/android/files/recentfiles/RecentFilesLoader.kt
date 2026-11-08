package me.zhanghai.android.files.recentfiles

import android.provider.MediaStore
import androidx.annotation.WorkerThread
import java8.nio.file.Paths
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A recent file, with the time it was last added or modified, in milliseconds.
 */
class RecentFile(val file: FileItem, val recentTime: Long)

/**
 * Loads the files most recently added or modified on the device from MediaStore, which is the
 * system's index of files on shared storage. This works right after the app is installed and is
 * much faster than walking the file system.
 */
object RecentFilesLoader {
    private const val MAX_SIZE = 100

    // The number of candidates to take for each sort order, leaving room for files that turn out to
    // be missing.
    private const val MAX_CANDIDATE_COUNT_PER_ORDER = 300

    // Guard against scanning the whole index when most entries are filtered out.
    private const val MAX_SCANNED_ROW_COUNT_PER_ORDER = 5000

    // MediaStore.Files.FileColumns.FORMAT is hidden from the SDK, but the column is available.
    private const val COLUMN_FORMAT = "format"

    // @see android.mtp.MtpConstants.FORMAT_ASSOCIATION
    private const val FORMAT_ASSOCIATION = 0x3001

    @WorkerThread
    fun load(showHiddenFiles: Boolean): List<RecentFile> {
        // A file is recent if it was either recently added or recently modified. Downloaded files
        // may keep the modification time from the server and extracted or copied files may keep
        // the one of their source, so the modification time alone isn't enough.
        //
        // Sorting by the maximum of both isn't reliably supported by MediaStore, so we query the
        // most recent files by each, which together contain the most recent files by the maximum.
        val candidates = mutableMapOf<String, Long>()
        for (sortColumn in listOf(
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED
        )) {
            queryCandidates(sortColumn, showHiddenFiles, candidates)
        }
        val recentFiles = mutableListOf<RecentFile>()
        for ((data, recentTimeSeconds) in candidates.entries.sortedByDescending { it.value }) {
            if (recentFiles.size >= MAX_SIZE) {
                break
            }
            // Load the file the same way as the file list does, so that we get the same MIME type,
            // thumbnail and badges. This also skips files that MediaStore still has but were
            // already deleted.
            val file = try {
                Paths.get(data).loadFileItem()
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

    private fun queryCandidates(
        sortColumn: String,
        showHiddenFiles: Boolean,
        candidates: MutableMap<String, Long>
    ) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        // Exclude directories and empty files, and files with a time in the future (with a day of
        // tolerance for time zone issues) that would otherwise stick to the top.
        val maxTime =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(1)
        val selection = "($COLUMN_FORMAT IS NULL OR $COLUMN_FORMAT != ?)" +
            " AND ${MediaStore.Files.FileColumns.SIZE} > 0" +
            " AND ${MediaStore.Files.FileColumns.DATE_ADDED} <= ?" +
            " AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        val selectionArgs =
            arrayOf(FORMAT_ASSOCIATION.toString(), maxTime.toString(), maxTime.toString())
        val sortOrder = "$sortColumn DESC"
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?: return
        cursor.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val dateModifiedColumn =
                it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            var candidateCount = 0
            var scannedRowCount = 0
            while (candidateCount < MAX_CANDIDATE_COUNT_PER_ORDER &&
                scannedRowCount < MAX_SCANNED_ROW_COUNT_PER_ORDER && it.moveToNext()) {
                ++scannedRowCount
                val data = it.getString(dataColumn)
                if (data.isNullOrEmpty()) {
                    continue
                }
                if (!showHiddenFiles && data.split('/').any { segment ->
                        segment.startsWith('.')
                    }) {
                    continue
                }
                val dateAdded = it.getLong(dateAddedColumn)
                val dateModified = it.getLong(dateModifiedColumn)
                candidates[data] = maxOf(dateAdded, dateModified)
                ++candidateCount
            }
        }
    }
}
