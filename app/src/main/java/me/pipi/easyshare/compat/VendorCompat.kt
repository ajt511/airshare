package me.pipi.easyshare.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Best-effort jumps into MIUI / ColorOS (and other vendors') autostart and
 * battery screens so the receiver survives the aggressive background rules
 * on Xiaomi and OPPO phones. Every lookup degrades to the app details page.
 */
object VendorCompat {
    data class VendorHints(
        val titleRes: Int,
        val bodyRes: Int,
    )

    fun vendorName(context: Context): String {
        val brand = android.os.Build.BRAND.lowercase()
        return when {
            brand in setOf("xiaomi", "redmi", "poco") -> "MIUI / HyperOS"
            brand in setOf("oppo", "oneplus", "realme") -> "ColorOS"
            brand == "vivo" || brand == "iqoo" -> "OriginOS / FuntouchOS"
            brand in setOf("honor", "huawei") -> "HarmonyOS / EMUI"
            else -> android.os.Build.BRAND
        }
    }

    /** Opens this vendor's autostart manager, or the app details page as fallback. */
    fun openAutoStartSettings(context: Context): Boolean {
        val brand = android.os.Build.BRAND.lowercase()
        val candidates: List<ComponentName> = when {
            brand in setOf("xiaomi", "redmi", "poco") -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.autostart.AutoStartManagementActivity",
                ),
            )
            brand in setOf("oppo", "oneplus", "realme") -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oplus.battery",
                    "com.oplus.battery.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            )
            brand == "vivo" || brand == "iqoo" -> listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            )
            brand in setOf("honor", "huawei") -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )
            else -> emptyList()
        }
        for (component in candidates) {
            try {
                context.startActivity(
                    Intent().setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (_: Exception) {
                // Component missing on this ROM build; try the next one.
            }
        }
        openAppDetails(context)
        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** System battery-optimization exemption dialog; works on every vendor. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // Nothing sensible left to try.
        }
    }
}
