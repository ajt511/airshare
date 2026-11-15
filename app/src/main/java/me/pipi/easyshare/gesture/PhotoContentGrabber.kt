package me.pipi.easyshare.gesture

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.utils.FileInfoFactory
import me.pipi.easyshare.utils.ShizukuUtils
import java.io.File

/**
 * Finds the image the user is currently viewing so the grab gesture can send
 * it without any picker:
 *  - gallery / file-manager pages: match the node hints harvested by the
 *    accessibility service against MediaStore and send the original file;
 *  - Tencent apps (QQ/WeChat): read their image view caches through the
 *    Shizuku user service (scoped storage hides Android/data from us),
 *    picking the most recently modified image; WeChat cache files are stored
 *    single-byte-XOR obfuscated and get unmasked by [unmaskTencentImage].
 *
 * Everything that can fail degrades to `null` — the capture session then
 * ends with a "nothing found" badge instead of sending a wrong file.
 */
object PhotoContentGrabber {

    // ---------------------------------------------------------------------
    // Pure helpers (unit-tested)
    // ---------------------------------------------------------------------

    /**
     * Picks the best cache candidate: nothing that looks like a thumbnail
     * (thumb directory or dot-thumb file), restricted to the freshness
     * window, newest lastModified wins.
     */
    fun pickMostRecent(
        entries: List<Pair<Long, String>>,
        atMillis: Long,
        windowMillis: Long,
    ): String? {
        val since = atMillis - windowMillis
        return entries
            .filter { (mtime, path) -> mtime >= since && !isThumbPath(path) }
            .maxByOrNull { it.first }
            ?.second
    }

    /** True when any path segment is a thumbnail (dir "thumb" / ".thumb_x"). */
    fun isThumbPath(path: String): Boolean {
        val segments = path.lowercase().split('/', '\\')
        return segments.any { it.startsWith(".thumb") || it == "thumb" }
    }

    /**
     * WeChat stores viewed images in its cache with every byte XORed by one
     * constant key. Recover the key by comparing the leading bytes against
     * the image-format magics; pass plain data through untouched.
     */
    fun unmaskTencentImage(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) return bytes
        val magics = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),          // JPEG
            byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte()),          // PNG
            byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte()),          // GIF
            byteArrayOf(0x42.toByte(), 0x4D.toByte()),                         // BMP
            byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()), // RIFF (WEBP)
        )
        if (magics.any { magic -> magic.indices.all { bytes[it] == magic[it] } }) return bytes
        for (magic in magics) {
            val key = bytes[0].toInt() xor magic[0].toInt()
            if (key != 0 && magic.indices.all { (bytes[it].toInt() xor key) == magic[it].toInt() }) {
                val out = bytes.clone()
                for (i in out.indices) out[i] = (out[i].toInt() xor key).toByte()
                return out
            }
        }
        return bytes
    }

    private val IMAGE_FILE_RE = Regex(
        "(?i)([\\w\\-]+\\.(?:jpg|jpeg|png|gif|bmp|webp))",
    )

    /**
     * First node hint that carries a concrete image file name (galleries put
     * "IMG_20261115_142300.jpg" or similar into contentDescription); the
     * bare file name is extracted even when surrounded by extra wording.
     */
    fun extractFilenameToken(hints: List<String>): String? {
        return hints.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 200 }
            .firstOrNull { IMAGE_FILE_RE.containsMatchIn(it) }
            ?.let { IMAGE_FILE_RE.find(it)?.groupValues?.get(1) }
    }

    // ---------------------------------------------------------------------
    // Grab entry point
    // ---------------------------------------------------------------------

    private val TENCENT_CACHE_ROOTS: Map<String, List<String>> = mapOf(
        "com.tencent.mobileqq" to listOf(
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/files",
        ),
        "com.tencent.qqlite" to listOf(
            "/storage/emulated/0/Android/data/com.tencent.qqlite/Tencent/MobileQQ",
        ),
        "com.tencent.tim" to listOf(
            "/storage/emulated/0/Android/data/com.tencent.tim/Tencent/Tim",
        ),
        "com.tencent.mm" to listOf(
            "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg",
        ),
    )

    private const val CACHE_WINDOW_MILLIS = 90_000L

    fun isTencentPackage(sourcePackage: String?): Boolean {
        val pkg = sourcePackage?.lowercase() ?: return false
        return TENCENT_CACHE_ROOTS.keys.any { pkg.startsWith(it) }
    }

    /**
     * Resolves the currently viewed image to a sendable [FileInfo]. Returns
     * null when nothing can be matched — callers show a failure badge and
     * never fall back to sending something unverified.
     */
    suspend fun grab(context: Context, hints: List<String>, sourcePackage: String?, atMillis: Long): FileInfo? {
        return try {
            if (isTencentPackage(sourcePackage)) {
                grabTencentCache(context, sourcePackage, atMillis)
            } else {
                grabGallery(context, hints)
            }
        } catch (error: Exception) {
            android.util.Log.w("PhotoContentGrabber", "grab failed", error)
            null
        }
    }

    /** Gallery / file manager: node hint → MediaStore original. */
    private suspend fun grabGallery(context: Context, hints: List<String>): FileInfo? {
        val token = extractFilenameToken(hints) ?: return null
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(token),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val id = it.getLong(0)
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            return runCatching { FileInfoFactory.fromUri(context, uri) }.getOrNull()
        }
    }

    /** QQ/WeChat: most recent cache image via the Shizuku user service. */
    private suspend fun grabTencentCache(context: Context, sourcePackage: String?, atMillis: Long): FileInfo? {
        val pkg = sourcePackage?.lowercase() ?: return null
        val roots = TENCENT_CACHE_ROOTS.filterKeys { pkg.startsWith(it) }.values.flatten()
        val since = atMillis - CACHE_WINDOW_MILLIS
        for (root in roots) {
            val entries = ShizukuUtils.listRecentImageFiles(root, since)
            val best = pickMostRecent(entries, atMillis, CACHE_WINDOW_MILLIS) ?: continue
            val plain = readPlainBytes(pkg, best) ?: continue
            val outFile = writeToCache(context, plain, atMillis) ?: continue
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", outFile)
            }.getOrNull() ?: continue
            val info = runCatching { FileInfoFactory.fromUri(context, uri) }.getOrNull() ?: continue
            // OpenableColumns reports the provider path; use the original name.
            return info.copy(name = outFile.name, nameIsFallback = false)
        }
        return null
    }

    private suspend fun readPlainBytes(pkg: String, path: String): ByteArray? {
        return try {
            val pfd = ShizukuUtils.openImageFile(path) ?: return null
            pfd.use {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                if (pkg.startsWith("com.tencent.mm")) unmaskTencentImage(bytes) else bytes
            }
        } catch (error: Exception) {
            android.util.Log.w("PhotoContentGrabber", "read cache image failed: $path", error)
            null
        }
    }

    private fun writeToCache(context: Context, bytes: ByteArray, atMillis: Long): File? {
        return try {
            val dir = File(context.cacheDir, "photo_grab").apply { mkdirs() }
            File(dir, "grab_$atMillis.jpg").apply { writeBytes(bytes) }
        } catch (error: Exception) {
            android.util.Log.w("PhotoContentGrabber", "cache write failed", error)
            null
        }
    }
}
