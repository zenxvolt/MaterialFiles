package me.zhanghai.android.files.recentfiles

import java8.nio.file.Path
import me.zhanghai.android.files.file.MimeType

data class RecentFile(
    val path: Path,
    val mimeType: MimeType,
    val size: Long,
    val lastModifiedTime: Long
)
