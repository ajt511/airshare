package me.pipi.easyshare.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoContextMatcherTest {
    @Test
    fun `known gallery packages match`() {
        assertTrue(PhotoContextMatcher.matches("com.miui.gallery"))
        assertTrue(PhotoContextMatcher.matches("com.coloros.gallery3d"))
        assertTrue(PhotoContextMatcher.matches("com.google.android.apps.photos"))
    }

    @Test
    fun `system file picker matches`() {
        assertTrue(PhotoContextMatcher.matches("com.android.documentsui"))
        assertTrue(PhotoContextMatcher.matches("com.google.android.documentsui"))
    }

    @Test
    fun `file managers match`() {
        assertTrue(PhotoContextMatcher.matches("com.miui.filemanager"))
        assertTrue(PhotoContextMatcher.matches("com.sec.android.app.myfiles"))
    }

    @Test
    fun `wechat prefix matches only with viewer-like class`() {
        assertTrue(PhotoContextMatcher.matches("com.tencent.mm", "com.tencent.mm.ui.album.AlbumUI"))
        assertTrue(PhotoContextMatcher.matches("com.tencent.mm", "ChatPhotoPickerActivity"))
        // A plain chat window must not trigger.
        assertFalse(PhotoContextMatcher.matches("com.tencent.mm", "com.tencent.mm.ui.chat.ChatActivity"))
    }

    @Test
    fun `unrelated packages never match`() {
        assertFalse(PhotoContextMatcher.matches("com.example.launcher"))
        assertFalse(PhotoContextMatcher.matches("me.pipi.easyshare"))
        assertFalse(PhotoContextMatcher.matches(null))
        assertFalse(PhotoContextMatcher.matches(""))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(PhotoContextMatcher.matches("Com.MIUI.Gallery"))
    }
}
