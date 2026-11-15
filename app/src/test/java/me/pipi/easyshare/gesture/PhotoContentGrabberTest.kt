package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoContentGrabberTest {

    // ------------------------------------------------------------ mtime pick

    @Test
    fun `pickMostRecent chooses the newest file inside the window`() {
        val entries = listOf(
            1_000L to "/a/old.jpg",
            5_000L to "/a/new.jpg",
            3_000L to "/a/mid.jpg",
        )
        assertEquals("/a/new.jpg", PhotoContentGrabber.pickMostRecent(entries, 6_000L, 90_000L))
    }

    @Test
    fun `pickMostRecent ignores thumbnails and stale entries`() {
        val entries = listOf(
            1_000L to "/a/stale.jpg",
            50_000L to "/a/thumb/inner/pic.jpg",
            55_000L to "/a/dir/.thumb_abc.jpg",
            30_000L to "/a/near.jpg",
        )
        assertEquals("/a/near.jpg", PhotoContentGrabber.pickMostRecent(entries, 100_000L, 90_000L))
    }

    @Test
    fun `pickMostRecent returns null when nothing qualifies`() {
        val entries = listOf(1_000L to "/a/old.jpg")
        assertNull(PhotoContentGrabber.pickMostRecent(entries, 200_000L, 90_000L))
        assertNull(PhotoContentGrabber.pickMostRecent(emptyList(), 200_000L, 90_000L))
    }

    @Test
    fun `isThumbPath detects thumb dirs and dot-thumb files`() {
        assertTrue(PhotoContentGrabber.isThumbPath("/a/Thumb/pic.jpg"))
        assertTrue(PhotoContentGrabber.isThumbPath("/a/.thumb_b.jpg"))
        assertFalse(PhotoContentGrabber.isThumbPath("/a/preview/pic.jpg"))
    }

    // ---------------------------------------------------------------- unmask

    private fun xor(bytes: ByteArray, key: Byte): ByteArray =
        ByteArray(bytes.size) { (bytes[it].toInt() xor key.toInt()).toByte() }

    @Test
    fun `unmaskTencentImage passes plain images through`() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        assertTrue(PhotoContentGrabber.unmaskTencentImage(plain).contentEquals(plain))
    }

    @Test
    fun `unmaskTencentImage recovers a xored jpeg`() {
        val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 7, 8, 9)
        val key = 0x42.toByte()
        val masked = xor(original, key)
        val restored = PhotoContentGrabber.unmaskTencentImage(masked)
        assertTrue(restored.contentEquals(original))
    }

    @Test
    fun `unmaskTencentImage recovers a xored png`() {
        val original = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 1, 2)
        val key = 0x11.toByte()
        val restored = PhotoContentGrabber.unmaskTencentImage(xor(original, key))
        assertTrue(restored.contentEquals(original))
    }

    @Test
    fun `unmaskTencentImage keeps unknown payloads untouched`() {
        val unknown = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        assertTrue(PhotoContentGrabber.unmaskTencentImage(unknown).contentEquals(unknown))
    }

    // -------------------------------------------------------- filename token

    @Test
    fun `extractFilenameToken finds an image file name hint`() {
        assertEquals(
            "IMG_20261115_142300.jpg",
            PhotoContentGrabber.extractFilenameToken(listOf("相册", "IMG_20261115_142300.jpg", "2048x1536")),
        )
    }

    @Test
    fun `extractFilenameToken is case-insensitive on extensions`() {
        assertEquals(
            "photo.PNG",
            PhotoContentGrabber.extractFilenameToken(listOf("shot photo.PNG")),
        )
    }

    @Test
    fun `extractFilenameToken returns null without a filename`() {
        assertNull(PhotoContentGrabber.extractFilenameToken(emptyList()))
        assertNull(PhotoContentGrabber.extractFilenameToken(listOf("2026年11月15日 14:23")))
    }

    // ------------------------------------------------------------- tencent pkg

    @Test
    fun `tencent packages are classified for cache lookup`() {
        assertTrue(PhotoContentGrabber.isTencentPackage("com.tencent.mobileqq"))
        assertTrue(PhotoContentGrabber.isTencentPackage("com.tencent.mm"))
        assertTrue(PhotoContentGrabber.isTencentPackage("com.tencent.tim"))
        assertFalse(PhotoContentGrabber.isTencentPackage("com.miui.gallery"))
        assertFalse(PhotoContentGrabber.isTencentPackage(null))
    }
}
