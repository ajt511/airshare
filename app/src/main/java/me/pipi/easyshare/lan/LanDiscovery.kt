package me.pipi.easyshare.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.utils.DeviceUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * UDP-based neighbor discovery for the LAN channel. While running, this
 * device broadcasts a [LanAnnouncement] every second and listens for the
 * same packets from peers; entries expire after [EXPIRE_MILLIS] of silence.
 */
class LanDiscovery(private val context: Context) {
    data class LanPeer(
        val announcement: LanAnnouncement,
        val address: InetAddress,
        val lastSeenMillis: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _peers = MutableStateFlow<Map<String, LanPeer>>(emptyMap())
    val peers: StateFlow<Map<String, LanPeer>> = _peers.asStateFlow()
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    val isLanEnabled: Boolean get() = AppSettings(context).lanEnabled

    fun start() {
        if (running || !isLanEnabled) return
        running = true
        listenJob = scope.launch { runListener() }
        announceJob = scope.launch { runAnnouncer() }
    }

    fun stop() {
        running = false
        announceJob?.cancel()
        listenJob?.cancel()
        socket?.close()
        socket = null
        _peers.value = emptyMap()
    }

    private fun broadcastAddress(): InetAddress? = try {
        val wifi = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val dhcp = wifi.dhcpInfo
        if (dhcp != null && dhcp.ipAddress != 0) {
            val ip = dhcp.ipAddress
            val mask = dhcp.netmask
            // Broadcast = network address | host part (bitwise NOT of the netmask).
            val broadcast = (ip and mask) or mask.inv()
            InetAddress.getByAddress(
                byteArrayOf(
                    (broadcast and 0xFF).toByte(),
                    (broadcast shr 8 and 0xFF).toByte(),
                    (broadcast shr 16 and 0xFF).toByte(),
                    (broadcast shr 24 and 0xFF).toByte(),
                )
            )
        } else {
            InetAddress.getByName("255.255.255.255")
        }
    } catch (error: Exception) {
        Log.w(TAG, "Failed to derive broadcast address", error)
        InetAddress.getByName("255.255.255.255")
    }

    private fun announcement(): LanAnnouncement {
        val settings = AppSettings(context)
        return LanAnnouncement(
            id = localId(context),
            name = settings.deviceName,
            brand = DeviceUtils.knownDeviceNameById(DeviceUtils.getLocalBrandId()) ?: "Android",
            brandId = DeviceUtils.getLocalBrandId(),
            port = LanTransferServer.PORT,
        )
    }

    private suspend fun runAnnouncer() {
        while (running && scope.isActive) {
            try {
                // Build per tick so a renamed device re-announces correctly.
                val payload = LanProtocol.json
                    .encodeToString(LanAnnouncement.serializer(), announcement())
                    .toByteArray(StandardCharsets.UTF_8)
                DatagramSocket().use { sender ->
                    sender.broadcast = true
                    sender.send(
                        DatagramPacket(payload, payload.size, broadcastAddress(), DISCOVERY_PORT)
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "Announce failed", error)
            }
            delay(ANNOUNCE_INTERVAL_MILLIS)
        }
    }

    private suspend fun runListener() {
        withContext(Dispatchers.IO) {
            try {
                val server = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                socket = server
                server.soTimeout = 500
                val buffer = ByteArray(2048)
                while (running && scope.isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        server.receive(packet)
                        handlePacket(packet)
                    } catch (_: SocketTimeoutException) {
                        expirePeers()
                    } catch (error: Exception) {
                        if (running) Log.w(TAG, "Discovery receive failed", error)
                    }
                }
            } catch (error: Exception) {
                if (running) Log.w(TAG, "Discovery listener failed (port busy?)", error)
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
        val announcement = try {
            LanProtocol.json.decodeFromString(LanAnnouncement.serializer(), text)
        } catch (_: Exception) {
            return
        }
        if (announcement.proto != LanAnnouncement.PROTO) return
        if (announcement.id == localId(context)) return
        val now = System.currentTimeMillis()
        // Traffic from other peers keeps the socket busy, so the receive
        // timeout alone cannot be relied on to expire departed peers.
        expirePeers(now)
        _peers.value = _peers.value + (announcement.id to LanPeer(announcement, packet.address, now))
    }

    private fun expirePeers(now: Long = System.currentTimeMillis()) {
        val expired = _peers.value.filterValues { now - it.lastSeenMillis > EXPIRE_MILLIS }
        if (expired.isNotEmpty()) {
            _peers.value = _peers.value - expired.keys
        }
    }

    companion object {
        private const val TAG = "LanDiscovery"
        const val DISCOVERY_PORT = 48888
        const val ANNOUNCE_INTERVAL_MILLIS = 1_000L
        const val EXPIRE_MILLIS = 4_000L

        private const val IDENTITY_PREFS = "lan"
        private const val KEY_DEVICE_ID = "deviceId"

        @Volatile
        private var cachedId: String? = null

        /** Stable id used in announcements and as the trust-table key.
         *  Persisted so pairing survives a restart; the no-arg overload is a
         *  per-process fallback for callers without a Context (tests). */
        fun localId(context: Context): String {
            cachedId?.let { return it }
            synchronized(this) {
                cachedId?.let { return it }
                val prefs = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                val stored = prefs.getString(KEY_DEVICE_ID, null)
                    ?.takeIf { it.isNotBlank() }
                val id = stored ?: UUID.randomUUID().toString().also { generated ->
                    prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
                }
                cachedId = id
                return id
            }
        }

        fun localId(): String {
            cachedId?.let { return it }
            synchronized(this) {
                cachedId?.let { return it }
                return UUID.randomUUID().toString().also { cachedId = it }
            }
        }
    }
}
