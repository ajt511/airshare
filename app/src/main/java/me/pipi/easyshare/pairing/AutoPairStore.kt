package me.pipi.easyshare.pairing

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.pipi.easyshare.AppSettings

/**
 * Trust table for the auto-pairing (beta) feature. When the setting is on,
 * every discovered GestureShare device is written here; one accepted request
 * from a device opens an accept-window (user-configurable, default 5 min)
 * during which further requests from the same device are auto-accepted.
 *
 * Keys are channel-scoped: "ble:<peerAddress>" for the Share-Alliance
 * channel and "lan:<announcement id>" for the LAN channel.
 */
object AutoPairStore {
    @Serializable
    data class Entry(
        val name: String,
        val brandId: Int? = null,
        val trustedAtMillis: Long,
        val windowUntilMillis: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences("autopair", Context.MODE_PRIVATE)

    private fun readAll(context: Context): MutableMap<String, Entry> = try {
        val raw = prefs(context).getString("devices", null) ?: return mutableMapOf()
        val decoded = json.decodeFromString<Map<String, Entry>>(raw)
        decoded.toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun writeAll(context: Context, map: Map<String, Entry>) {
        prefs(context).edit {
            putString("devices", json.encodeToString(map))
        }
    }

    /** Writes/refreshes a device entry (discovery or incoming request).
     *  Returns false when auto-pairing is off (nothing written).
     *  Synchronized: Ktor/Netty threads, the BLE scan main thread and the UI
     *  all mutate the same JSON blob; an unsynchronized read-modify-write
     *  silently drops the other writer's entry. */
    @Synchronized
    fun trust(context: Context, key: String, name: String, brandId: Int?): Boolean {
        if (key.isBlank() || !AppSettings(context).autoPairEnabled) return false
        val map = readAll(context)
        val old = map[key]
        map[key] = Entry(
            name = name,
            brandId = brandId ?: old?.brandId,
            trustedAtMillis = System.currentTimeMillis(),
            windowUntilMillis = old?.windowUntilMillis ?: 0L,
        )
        writeAll(context, map)
        return true
    }

    /** True when the device has a currently open accept-window. */
    @Synchronized
    fun hasOpenWindow(context: Context, key: String): Boolean {
        if (key.isBlank() || !AppSettings(context).autoPairEnabled) return false
        val entry = readAll(context)[key] ?: return false
        return System.currentTimeMillis() < entry.windowUntilMillis
    }

    /** True when the device has any entry in the trust table (paired at all). */
    @Synchronized
    fun isTrusted(context: Context, key: String): Boolean {
        if (key.isBlank()) return false
        return readAll(context).containsKey(key)
    }

    /** Opens/extends the accept-window for one device. Call on every acceptance. */
    @Synchronized
    fun extendWindow(context: Context, key: String) {
        if (key.isBlank()) return
        val map = readAll(context)
        val old = map[key] ?: return
        val windowMillis = AppSettings(context).pairAcceptWindowMinutes * 60_000L
        map[key] = old.copy(windowUntilMillis = System.currentTimeMillis() + windowMillis)
        writeAll(context, map)
    }

    @Synchronized
    fun count(context: Context): Int = readAll(context).size

    @Synchronized
    fun clearAll(context: Context) {
        prefs(context).edit { remove("devices") }
    }
}
