package me.pipi.easyshare.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorCompatTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    @Test
    fun `xiaomi with hyperos prop reports PengPaiOS`() {
        val info = VendorCompat.detectVendor("Xiaomi", props("ro.mi.os.version.name" to "2.0.104"))
        assertEquals(VendorCompat.Vendor.MIUI_HYPEROS, info.vendor)
        assertEquals("澎湃OS", info.displayName)
        assertEquals("2.0.104", info.versionName)
    }

    @Test
    fun `xiaomi with legacy miui prop reports MIUI`() {
        val info = VendorCompat.detectVendor("redmi", props("ro.miui.ui.version.name" to "V14"))
        assertEquals(VendorCompat.Vendor.MIUI_HYPEROS, info.vendor)
        assertEquals("MIUI", info.displayName)
        assertEquals("V14", info.versionName)
    }

    @Test
    fun `oppo reports ColorOS with oplusrom version`() {
        val info = VendorCompat.detectVendor("OPPO", props("ro.build.version.oplusrom" to "14.0.1"))
        assertEquals(VendorCompat.Vendor.COLOROS, info.vendor)
        assertEquals("ColorOS", info.displayName)
        assertEquals("14.0.1", info.versionName)
    }

    @Test
    fun `huawei reports HarmonyOS`() {
        val info = VendorCompat.detectVendor("HUAWEI", props("ro.build.version.harmonyos" to "4.2.0"))
        assertEquals(VendorCompat.Vendor.HARMONYOS, info.vendor)
        assertEquals("HarmonyOS", info.displayName)
        assertEquals("4.2.0", info.versionName)
    }

    @Test
    fun `honor maps to harmony family`() {
        val info = VendorCompat.detectVendor("honor") { null }
        assertEquals(VendorCompat.Vendor.HARMONYOS, info.vendor)
    }

    @Test
    fun `unknown brand falls back to generic`() {
        val info = VendorCompat.detectVendor("sony") { null }
        assertEquals(VendorCompat.Vendor.GENERIC, info.vendor)
        assertEquals("sony", info.displayName)
    }

    @Test
    fun `miui hyperos has autostart and background popup candidates`() {
        assertTrue(VendorCompat.autostartCandidates(VendorCompat.Vendor.MIUI_HYPEROS).isNotEmpty())
        assertTrue(VendorCompat.backgroundPopupCandidates(VendorCompat.Vendor.MIUI_HYPEROS).isNotEmpty())
    }

    @Test
    fun `coloros and harmonyos have autostart candidates but no popup gate`() {
        assertTrue(VendorCompat.autostartCandidates(VendorCompat.Vendor.COLOROS).isNotEmpty())
        assertTrue(VendorCompat.autostartCandidates(VendorCompat.Vendor.HARMONYOS).isNotEmpty())
        assertTrue(VendorCompat.backgroundPopupCandidates(VendorCompat.Vendor.COLOROS).isEmpty())
        assertTrue(VendorCompat.backgroundPopupCandidates(VendorCompat.Vendor.HARMONYOS).isEmpty())
    }
}
