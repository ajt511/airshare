package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import me.pipi.easyshare.IMacAddressService
import me.pipi.easyshare.utils.TAG
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class MacAddressService(private val context: Context): IMacAddressService.Stub() {
    override fun destroy() {
        exit()
    }

    override fun exit() {
        Log.i(TAG, "Exit")
        exitProcess(0)
    }

    @SuppressLint("MissingPermission")
    override fun getP2pMacAddress(): String? {
        val thread = HandlerThread("P2pIdentity").apply { start() }
        val handler = Handler(thread.looper)
        val result = CompletableFuture<String?>()
        var channel: WifiP2pManager.Channel? = null
        handler.post {
            try {
                // A Shizuku context keeps the app's operation package. Wi-Fi requires the
                // package and attribution to belong to the user service's actual UID.
                val uid = Process.myUid()
                val operationPackage = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
                    ?: context.packageName
                val serviceContext = object : ContextWrapper(context) {
                    override fun getOpPackageName() = operationPackage
                    override fun getAttributionSource() = AttributionSource.Builder(uid)
                        .setPackageName(operationPackage).build()
                    @RequiresApi(34)
                    override fun createDeviceContext(deviceId: Int): Context {
                        require(deviceId == Context.DEVICE_ID_DEFAULT)
                        return this
                    }
                }
                val manager = context.getSystemService(WifiP2pManager::class.java)
                channel = manager.initialize(serviceContext, thread.looper) { result.complete(null) }
                manager.requestDeviceInfo(channel) { result.complete(it?.deviceAddress) }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return try {
            result.get(3, TimeUnit.SECONDS)
        } finally {
            handler.post {
                try {
                    channel?.close()
                } finally {
                    thread.quitSafely()
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun getMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }

    override fun listRecentImageFiles(
        rootDir: String?,
        sinceMillis: Long,
        maxDepth: Int,
        maxCount: Int,
    ): Array<String> {
        val root = rootDir?.let(::File) ?: return emptyArray()
        if (!root.isDirectory) return emptyArray()
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
        val found = ArrayList<Pair<Long, File>>()
        walkRecentImages(root, 0, maxDepth, sinceMillis, deadline, found)
        found.sortByDescending { it.first }
        return found.take(maxCount)
            .map { "${it.first}|${it.second.absolutePath}" }
            .toTypedArray()
    }

    /** Bounded walk: depth cap, file cap and a wall-clock budget so a
     *  sprawling cache tree cannot stall the grab gesture. */
    private fun walkRecentImages(
        dir: File,
        depth: Int,
        maxDepth: Int,
        sinceMillis: Long,
        deadline: Long,
        out: ArrayList<Pair<Long, File>>,
    ) {
        if (depth > maxDepth || System.currentTimeMillis() > deadline) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (System.currentTimeMillis() > deadline) return
            if (entry.isDirectory) {
                if (entry.name.equals("thumb", true) || entry.name.startsWith(".thumb", true)) continue
                walkRecentImages(entry, depth + 1, maxDepth, sinceMillis, deadline, out)
            } else if (
                entry.isFile &&
                entry.lastModified() >= sinceMillis &&
                isImageFileName(entry.name)
            ) {
                out.add(entry.lastModified() to entry)
                if (out.size >= MAX_SCAN_FILES) return
            }
        }
    }

    override fun openImageFile(path: String?): ParcelFileDescriptor? {
        val file = path?.let(::File) ?: return null
        // Restrict this shell-privileged reader to image files under shared
        // storage (where QQ/WeChat keep their view caches).
        val clean = try {
            file.canonicalPath
        } catch (_: Exception) {
            return null
        }
        if (!clean.startsWith("/storage/emulated/0/") && !clean.startsWith("/sdcard/")) return null
        if (!isImageFileName(file.name)) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            Log.w(TAG, "openImageFile failed", error)
            null
        }
    }

    private fun isImageFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        private const val SCAN_BUDGET_MS = 2_000L
        private const val MAX_SCAN_FILES = 64
    }
}
