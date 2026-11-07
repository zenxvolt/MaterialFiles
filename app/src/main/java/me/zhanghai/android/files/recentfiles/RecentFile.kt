package me.zhanghai.android.files.recentfiles

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.util.ParcelableParceler

@Parcelize
data class RecentFile(
    val path: @WriteWith<ParcelableParceler> Path,
    val mimeType: MimeType,
    val lastOpenedTime: Long
) : Parcelable
