package me.pipi.easyshare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceName

class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceName: String
        get() = BleUtils.normalizeDeviceName(prefs.getString("deviceName", null) ?: DeviceName.get())
        set(value) {
            prefs.edit { putString("deviceName", BleUtils.normalizeDeviceName(value)) }
        }

    var downloadUri: String?
        get() = prefs.getString("downloadUri", null)
        set(value) {
            prefs.edit { putString("downloadUri", value) }
        }

    var enhancedModeEnabled: Boolean
        get() = prefs.getBoolean("enhancedModeEnabled", true)
        set(value) {
            prefs.edit { putBoolean("enhancedModeEnabled", value) }
        }

    var secureReceiveOnly: Boolean
        get() = prefs.getBoolean("secureReceiveOnly", false)
        set(value) {
            prefs.edit { putBoolean("secureReceiveOnly", value) }
        }

    var backgroundReceiveEnabled: Boolean
        get() = prefs.getBoolean("backgroundReceiveEnabled", false)
        set(value) {
            prefs.edit { putBoolean("backgroundReceiveEnabled", value) }
        }

    var brandId: Int
        get() = prefs.getInt("brandId", -1)
        set(value) {
            prefs.edit { putInt("brandId", value) }
        }

    /** Open-palm release auto-accepts incoming LAN/alliance requests while the gesture screen is up. */
    var palmAutoAcceptEnabled: Boolean
        get() = prefs.getBoolean("palmAutoAcceptEnabled", true)
        set(value) {
            prefs.edit { putBoolean("palmAutoAcceptEnabled", value) }
        }

    /** Shake in any app screen opens the gesture transfer screen. */
    var shakeToShareEnabled: Boolean
        get() = prefs.getBoolean("shakeToShareEnabled", false)
        set(value) {
            prefs.edit { putBoolean("shakeToShareEnabled", value) }
        }

    /** LAN channel (same Wi-Fi / hotspot) discovery + transfer. */
    var lanEnabled: Boolean
        get() = prefs.getBoolean("lanEnabled", true)
        set(value) {
            prefs.edit { putBoolean("lanEnabled", value) }
        }

    /** Haptic feedback on grab/release. */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("hapticsEnabled", true)
        set(value) {
            prefs.edit { putBoolean("hapticsEnabled", value) }
        }

    /** Show the gesture pill when the user browses images/picks files elsewhere. */
    var photoGestureEnabled: Boolean
        get() = prefs.getBoolean("photoGestureEnabled", false)
        set(value) {
            prefs.edit { putBoolean("photoGestureEnabled", value) }
        }

}
