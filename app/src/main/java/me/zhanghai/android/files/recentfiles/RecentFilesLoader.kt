package me.zhanghai.android.files.recentfiles

import android.provider.MediaStore
import androidx.annotation.WorkerThread
import java8.nio.file.Paths
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.guessFromPath
import java.io.File
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

    // @see android.mtp.MtpConstants.FORMAT_ASSOCIATION
    private const val FORMAT_ASSOCIATION = 0x3001

    @WorkerThread
    fun load(showHiddenFiles: Boolean): List<RecentFile> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        // Exclude directories and empty files, and files with a modification time in the future
        // (with a day of tolerance for time zone issues) that would otherwise stick to the top.
        val selection = "(${MediaStore.Files.FileColumns.FORMAT} IS NULL" +
            " OR ${MediaStore.Files.FileColumns.FORMAT} != ?)" +
            " AND ${MediaStore.Files.FileColumns.SIZE} > 0" +
            " AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} <= ?"
        val maxDateModified =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + TimeUnit.DAYS.toSeconds(1)
        val selectionArgs = arrayOf(FORMAT_ASSOCIATION.toString(), maxDateModified.toString())
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?: return emptyList()
        val recentFiles = mutableListOf<RecentFile>()
        cursor.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val mimeTypeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateModifiedColumn =
                it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
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
                // MediaStore may lag behind the file system.
                val file = File(data)
                if (!file.isFile) {
                    continue
                }
                // Prefer our own guess for consistency with the file list, which also has better
                // coverage than MediaStore for some types.
                val mimeType = MimeType.guessFromPath(data).takeIf { mimeType ->
                    mimeType != MimeType.GENERIC
                } ?: it.getString(mimeTypeColumn)?.asMimeTypeOrNull() ?: MimeType.GENERIC
                recentFiles += RecentFile(
                    Paths.get(data), mimeType, it.getLong(sizeColumn),
                    TimeUnit.SECONDS.toMillis(it.getLong(dateModifiedColumn))
                )
            }
        }
        return recentFiles
    }
}
