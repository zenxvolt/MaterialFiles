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
 * Loads the files most recently added or modified on the device from MediaStore, which is the
 * system's index of files on shared storage. This works right after the app is installed and is
 * much faster than walking the file system.
 */
object RecentFilesLoader {
    private const val MAX_SIZE = 100

    // Guard against scanning the whole index when most entries are filtered out.
    private const val MAX_SCANNED_ROW_COUNT = 5000

    // MediaStore.Files.FileColumns.FORMAT is hidden from the SDK, but the column is available.
    private const val COLUMN_FORMAT = "format"

    // @see android.mtp.MtpConstants.FORMAT_ASSOCIATION
    private const val FORMAT_ASSOCIATION = 0x3001

    @WorkerThread
    fun load(showHiddenFiles: Boolean): List<FileItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        // Exclude directories and empty files, and files with a modification time in the future
        // (with a day of tolerance for time zone issues) that would otherwise stick to the top.
        val selection = "($COLUMN_FORMAT IS NULL" +
            " OR $COLUMN_FORMAT != ?)" +
            " AND ${MediaStore.Files.FileColumns.SIZE} > 0" +
            " AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        val maxDateModified =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(1)
        val selectionArgs = arrayOf(FORMAT_ASSOCIATION.toString(), maxDateModified.toString())
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?: return emptyList()
        val recentFiles = mutableListOf<FileItem>()
        cursor.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            var scannedRowCount = 0
            while (recentFiles.size < MAX_SIZE && scannedRowCount < MAX_SCANNED_ROW_COUNT
                && it.moveToNext()) {
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
                // Load the file the same way as the file list does, so that we get the same MIME
                // type, thumbnail and badges. This also skips files that MediaStore still has but
                // were already deleted.
                val file = try {
                    Paths.get(data).loadFileItem()
                } catch (e: IOException) {
                    continue
                }
                if (file.attributes.isDirectory) {
                    continue
                }
                recentFiles += file
            }
        }
        return recentFiles
    }
}
