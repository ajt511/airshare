package me.pipi.easyshare.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Devices on the same Wi-Fi / hotspot announce themselves over UDP so the
 * gesture radar can list them alongside Share-Alliance targets.
 */
@Serializable
data class LanAnnouncement(
    /** Protocol identifier; receivers drop packets that do not match. */
    val proto: String = PROTO,
    /** Stable random id so the same device re-announcing does not duplicate. */
    val id: String,
    val name: String,
    val brand: String,
    val brandId: Int,
    /** TCP port of the receiver's AirShare HTTP server. */
    val port: Int,
) {
    companion object {
        const val PROTO = "airshare-lan-1"
    }
}

/** Sender → receiver handoff before the actual bytes flow. */
@Serializable
data class LanPrepareRequest(
    val senderName: String,
    val senderBrandId: Int,
    val textContent: String? = null,
    val files: List<LanFileMeta> = emptyList(),
)

@Serializable
data class LanFileMeta(
    val name: String,
    val mimeType: String,
    val size: Long,
)

@Serializable
data class LanPrepareResponse(
    val taskId: Int,
    val accepted: Boolean,
    val message: String? = null,
)

@Serializable
data class LanCompleteRequest(
    val taskId: Int,
    val canceled: Boolean = false,
)

object LanProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
