package me.pipi.easyshare.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Vendor detection and ROM-specific settings navigation for the
 * device-compatibility (beta) section. Covers Xiaomi 澎湃OS/HyperOS/MIUI,
 * OPPO ColorOS, Huawei/Honor HarmonyOS 4.x (Android-based), and falls back
 * to generic Android pages everywhere else. Every jump degrades gracefully
 * to the app details page when a component is missing on the ROM build.
 */
object VendorCompat {
    enum class Vendor { MIUI_HYPEROS, COLOROS, HARMONYOS, GENERIC }

    data class VendorInfo(
        val vendor: Vendor,
        /** Human-readable ROM name, e.g. 澎湃OS / ColorOS / HarmonyOS. */
        val displayName: String,
        /** ROM version string when detectable (prop value), else null. */
        val versionName: String?,
    )

    /** Compatibility guide actions the settings screen can offer. */
    enum class Action(val id: String) {
        AUTOSTART("autostart"),
        BATTERY("battery"),
        BACKGROUND_POPUP("popup"),
    }

    /**
     * Detects the vendor from brand + ROM properties. [propReader] is
     * injected so JVM unit tests can drive it; on device it reflects into
     * android.os.SystemProperties (non-SDK API — always try-caught).
     */
    fun detectVendor(
        brand: String = android.os.Build.BRAND,
        propReader: (String) -> String? = ::systemProperty,
    ): VendorInfo {
        val b = brand.lowercase()
        return when {
            b in setOf("xiaomi", "redmi", "poco") -> {
                // 澎湃OS reports ro.mi.os.version.name; legacy MIUI uses ro.miui.ui.version.name.
                val os = propReader("ro.mi.os.version.name")
                val miui = propReader("ro.miui.ui.version.name")
                if (!os.isNullOrBlank()) {
                    VendorInfo(Vendor.MIUI_HYPEROS, "澎湃OS", os)
                } else if (!miui.isNullOrBlank()) {
                    VendorInfo(Vendor.MIUI_HYPEROS, "MIUI", miui)
                } else {
                    VendorInfo(Vendor.MIUI_HYPEROS, "MIUI / HyperOS", null)
                }
            }
            b in setOf("oppo", "oneplus", "realme") -> {
                val os = propReader("ro.build.version.oplusrom")
                    ?: propReader("ro.build.version.oppo")
                VendorInfo(Vendor.COLOROS, "ColorOS", os)
            }
            b in setOf("huawei", "harmony") -> {
                val os = propReader("ro.build.version.harmonyos")
                    ?: propReader("hw_sc.build.platform.version")
                VendorInfo(Vendor.HARMONYOS, "HarmonyOS", os)
            }
            b == "honor" -> {
                VendorInfo(Vendor.HARMONYOS, "Magic OS / HarmonyOS", null)
            }
            else -> VendorInfo(Vendor.GENERIC, brand.ifBlank { "Android" }, null)
        }
    }

    private fun systemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Autostart manager entry points, newest ROMs first. */
    fun autostartCandidates(vendor: Vendor): List<ComponentName> = when (vendor) {
        Vendor.MIUI_HYPEROS -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.autostart.AutoStartManagementActivity",
            ),
        )
        Vendor.COLOROS -> listOf(
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
        Vendor.HARMONYOS -> listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
        )
        Vendor.GENERIC -> emptyList()
    }

    /**
     * MIUI/HyperOS-only: the separate "background pop-up interface" gate
     * that governs launching our overlay screen from behind other apps.
     * Landed inside the permission editor; no stable public intent exists,
     * so we try the known components and fall back to app details.
     */
    fun backgroundPopupCandidates(vendor: Vendor): List<ComponentName> = when (vendor) {
        Vendor.MIUI_HYPEROS -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.MainPermissionDetailsActivity",
            ),
        )
        else -> emptyList()
    }

    /** Opens this vendor's autostart manager, or the app details page as fallback. */
    fun openAutoStartSettings(context: Context): Boolean =
        openFirstAvailable(context, autostartCandidates(detectVendor().vendor))

    /** Opens the MIUI background pop-up gate when on Xiaomi; false otherwise. */
    fun openBackgroundPopupSettings(context: Context): Boolean {
        val vendor = detectVendor().vendor
        if (vendor != Vendor.MIUI_HYPEROS) return false
        return openFirstAvailable(context, backgroundPopupCandidates(vendor))
    }

    private fun openFirstAvailable(context: Context, candidates: List<ComponentName>): Boolean {
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
