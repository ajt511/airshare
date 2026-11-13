package me.pipi.easyshare.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import me.pipi.easyshare.models.FileInfo

/** Builds [FileInfo] from content URIs; shared by share intents and the gesture picker. */
object FileInfoFactory {
    fun fromUri(context: Context, uri: Uri): FileInfo? {
        val cr = context.contentResolver
        var displayName: String? = null
        var reportedSize = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { displayName = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { reportedSize = cursor.getLong(it) }
                }
            }
        if (reportedSize < 0L) {
            reportedSize = runCatching {
                cr.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.takeIf { it >= 0L } ?: 0L
        }
        val suppliedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val name = suppliedName ?: "shared_file"
        val mimeType = cr.getType(uri)?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        return FileInfo(uri, name, mimeType, reportedSize, null, nameIsFallback = suppliedName == null)
    }
}
