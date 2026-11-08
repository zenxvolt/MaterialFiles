package me.zhanghai.android.files.recentfiles

import android.os.Build
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import java8.nio.file.Paths
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
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
    val mediaStoreGenerations: Map<String, Long>
)

/**
 * Loads the files most recently added or modified on the device from MediaStore, which is the
 * system's index of files on shared storage, like system file managers do. This works right after
 * the app is installed and is much faster than walking the file system. Files only show up once
 * MediaStore has indexed them, which may take a while for some new files, e.g. some downloads.
 *
 * A file is recent if it was recently added or modified. Downloaded files may keep the modification
 * time from the server, and extracted or copied files may keep the one of their source, so the
 * modification time alone isn't enough, and we also use the time it was added to MediaStore.
 */
object RecentFilesLoader {
    private const val MAX_SIZE = 100

    // The number of candidates to take for each sort order, leaving room for files that turn out to
    // be missing, empty or directories.
    private const val MAX_CANDIDATE_COUNT_PER_ORDER = 300

    // Guard against scanning the whole index when most entries are filtered out.
    private const val MAX_SCANNED_ROW_COUNT_PER_ORDER = 5000

    // MediaStore.Files.FileColumns.FORMAT is hidden from the SDK, but the column is available.
    private const val COLUMN_FORMAT = "format"

    // @see android.mtp.MtpConstants.FORMAT_ASSOCIATION
    private const val FORMAT_ASSOCIATION = 0x3001

    /**
     * Returns a key for checking whether recent files may have changed since they were loaded, or
     * `null` if that can't be checked cheaply.
     */
    @WorkerThread
    fun getChangeKey(showHiddenFiles: Boolean): RecentFilesChangeKey? {
        val mediaStoreGenerations = getMediaStoreGenerations() ?: return null
        return RecentFilesChangeKey(
            LocalDate.now().toEpochDay(), showHiddenFiles, mediaStoreGenerations
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
        // Get the key before loading, so that changes during loading cause a reload.
        val changeKey = getChangeKey(showHiddenFiles)
        // Times in the future (with a day of tolerance for time zone issues) are ignored, so that
        // files with a wrong time don't stick to the top.
        val maxTimeSeconds =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(1)
        // Paths to their time, in seconds.
        val candidates = mutableMapOf<String, Long>()
        // Sorting by the maximum of both times isn't reliably supported by MediaStore, so we query
        // the most recent files by each, which together contain the most recent files by the
        // maximum.
        for (sortColumn in listOf(
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED
        )) {
            queryCandidates(sortColumn, showHiddenFiles, maxTimeSeconds, candidates)
        }
        val recentFiles = mutableListOf<RecentFile>()
        for ((path, recentTimeSeconds) in candidates.entries.sortedByDescending { it.value }) {
            if (recentFiles.size >= MAX_SIZE) {
                break
            }
            // Load the file the same way as the file list does, so that we get the same MIME type,
            // thumbnail and badges. This also skips files that MediaStore still has but were
            // already deleted.
            val file = try {
                Paths.get(path).loadFileItem()
            } catch (e: IOException) {
                continue
            }
            val attributes = file.attributes
            if (attributes.isDirectory || attributes.size() == 0L) {
                continue
            }
            recentFiles += RecentFile(file, TimeUnit.SECONDS.toMillis(recentTimeSeconds))
        }
        return RecentFilesResult(recentFiles, changeKey)
    }

    private fun queryCandidates(
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
        // the actual file later instead.
        val selection = "($COLUMN_FORMAT IS NULL OR $COLUMN_FORMAT != ?)" +
            " AND ($sortColumn IS NULL OR $sortColumn <= ?)"
        val selectionArgs = arrayOf(FORMAT_ASSOCIATION.toString(), maxTimeSeconds.toString())
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
                // Getting a null column as long returns 0.
                val time = listOf(it.getLong(dateAddedColumn), it.getLong(dateModifiedColumn))
                    .filter { time -> time in 1..maxTimeSeconds }
                    .maxOrNull()
                    ?: continue
                candidates[data] = maxOf(candidates[data] ?: 0, time)
                ++candidateCount
            }
        }
    }
}
