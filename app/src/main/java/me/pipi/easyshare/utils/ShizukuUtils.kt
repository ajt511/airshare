package me.pipi.easyshare.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.IMacAddressService
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.services.MacAddressService
import rikka.shizuku.Shizuku
import java.net.NetworkInterface
import kotlin.collections.iterator

object ShizukuUtils {
    private val binderLock = Any()
    private var macService: IMacAddressService? = null
    private var serviceReady = CompletableDeferred<IMacAddressService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Shizuku.addBinderReceivedListenerSticky {
            if (AppSettings(MyApplication.getInstance()).enhancedModeEnabled) {
                bindService()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                if (BuildConfig.DEBUG) Log.d(ShizukuUtils.TAG, "MAC service connected")
                val connectedService = IMacAddressService.Stub.asInterface(service)

                synchronized(binderLock) {
                    macService = connectedService
                    if (!serviceReady.isCompleted) serviceReady.complete(connectedService)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (BuildConfig.DEBUG) Log.d(ShizukuUtils.TAG, "MAC service disconnected")
            synchronized(binderLock) {
                macService = null
                serviceReady = CompletableDeferred()
            }
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val cn = ComponentName(
            BuildConfig.APPLICATION_ID, MacAddressService::class.java.name
        )
        return Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE * 100 + USER_SERVICE_REVISION)
    }

    fun unsafeBindService() {
        Shizuku.bindUserService(userServiceArgs(), serviceConnection)
    }

    fun bindService() {
        if (!AppSettings(MyApplication.getInstance()).enhancedModeEnabled) return
        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun setEnhancedModeEnabled(context: Context, enabled: Boolean) {
        AppSettings(context).enhancedModeEnabled = enabled
        if (enabled) {
            bindService()
        } else {
            try {
                Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to unbind Shizuku user service", e)
            }
            synchronized(binderLock) {
                macService = null
                serviceReady = CompletableDeferred()
            }
        }
    }

    fun getMacAddress(context: Context, name: String, l: (String?) -> Unit) {
        scope.launch {
            l(getMacAddress(context, name))
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun nativeGetMacAddressByName(name: String): String? {
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

    suspend fun getMacAddress(context: Context, name: String): String? {
        if (!AppSettings(context).enhancedModeEnabled) return null
        if (context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return withContext(Dispatchers.IO) { nativeGetMacAddressByName(name) }
        }

        return withMacService { it.getMacAddressByName(name) }
    }

    suspend fun getP2pDeviceAddress(context: Context): String? {
        if (!AppSettings(context).enhancedModeEnabled) return null
        return withMacService { it.p2pMacAddress }
    }

    /** Whether the Shizuku user service can be used right now. */
    fun userServiceAvailable(context: Context): Boolean {
        return try {
            AppSettings(context).enhancedModeEnabled && Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun <T> withMacService(read: (IMacAddressService) -> T?): T? {
        return try {
            val current = synchronized(binderLock) { macService }
            val service = current ?: run {
                val signal = synchronized(binderLock) {
                    macService?.let { return@synchronized CompletableDeferred(it) }
                    if (serviceReady.isCancelled) serviceReady = CompletableDeferred()
                    serviceReady
                }
                unsafeBindService()
                withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MS) { signal.await() }
            } ?: return null
            withContext(Dispatchers.IO) { read(service) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(ShizukuUtils.TAG, "Failed to call user service: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Image cache candidates under [rootDir] (e.g. QQ/WeChat private cache
     * trees the app itself cannot read), newest first as (mtime, path).
     * Returns null when the user service itself failed (old version, binder
     * gone) — callers treat that as "fall back to the screenshot path".
     */
    suspend fun listRecentImageFiles(
        rootDir: String,
        sinceMillis: Long,
        maxDepth: Int = 8,
        maxCount: Int = 8,
    ): List<Pair<Long, String>>? {
        return withMacService { service ->
            // Keep null: it means the service call itself failed (stale/broken
            // user service), and callers must fall back to a screenshot rather
            // than reporting "no photo".
            service.listRecentImageFiles(rootDir, sinceMillis, maxDepth, maxCount)
                ?.mapNotNull { entry ->
                    val split = entry.indexOf('|')
                    if (split <= 0) return@mapNotNull null
                    val mtime = entry.substring(0, split).toLongOrNull() ?: return@mapNotNull null
                    mtime to entry.substring(split + 1)
                }
        }
    }

    /** Opens one cache image through the user service's shell privileges. */
    suspend fun openImageFile(path: String): ParcelFileDescriptor? {
        return withMacService { service -> service.openImageFile(path) }
    }

    private const val SERVICE_CONNECT_TIMEOUT_MS = 5_000L
    private const val USER_SERVICE_REVISION = 2
}
