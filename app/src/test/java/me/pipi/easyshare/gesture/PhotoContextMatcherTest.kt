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
        // Missing class name must not trigger inside chat apps either.
        assertFalse(PhotoContextMatcher.matches("com.tencent.mm"))
    }

    @Test
    fun `qq photo preview matches`() {
        // Legacy QQ image pager / preview activities.
        assertTrue(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.mobileqq.activity.PhotoPreviewActivity"))
        assertTrue(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.mobileqq.activity.ImagePagerActivity"))
        // QQNT gallery.
        assertTrue(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.qqnt.gallery.QQGalleryActivity"))
        // QQ album multi-select.
        assertTrue(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.mobileqq.activity.PhotoListActivity"))
    }

    @Test
    fun `qq chat and profile windows do not match`() {
        assertFalse(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.mobileqq.activity.ChatActivity"))
        assertFalse(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.qqnt.profile.ProfileActivity"))
        assertFalse(PhotoContextMatcher.matches(
            "com.tencent.mobileqq", "com.tencent.mobileqq.setting.SettingMainActivity"))
    }

    @Test
    fun `tim photo preview matches`() {
        assertTrue(PhotoContextMatcher.matches(
            "com.tencent.tim", "com.tencent.tim.activity.PhotoPreviewActivity"))
        assertFalse(PhotoContextMatcher.matches(
            "com.tencent.tim", "com.tencent.tim.activity.MainActivity"))
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
