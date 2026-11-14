package me.pipi.easyshare.gesture

/**
 * Heuristic matcher for "the user is viewing an image or picking files".
 * Matches the foreground app's package name against a table of common
 * gallery / file-manager / chat apps. Pure logic, unit-testable; extend the
 * table as real devices surface more packages.
 */
object PhotoContextMatcher {
    /** Packages whose window events indicate image browsing or file picking. */
    private val KNOWN_PACKAGES = setOf(
        // System gallery apps
        "com.miui.gallery",             // Xiaomi
        "com.android.gallery3d",        // AOSP / legacy
        "com.oppo.gallery3d",           // OPPO
        "com.coloros.gallery3d",        // OPPO ColorOS
        "com.oneplus.gallery",          // OnePlus/OxygenOS
        "com.sec.android.gallery3d",    // Samsung
        "com.google.android.apps.photos", // Google Photos
        "com.alensw.PicFolder",         // QuickPic
        // System file pickers (covers open/multi-select of files & images)
        "com.android.documentsui",      // AOSP DocumentsUI / SAF picker
        "com.google.android.documentsui",
        // File managers
        "com.miui.filemanager",         // Xiaomi 文件管理
        "com.coloros.filemanager",      // OPPO
        "com.oneplus.filemanager",
        "com.sec.android.app.myfiles",  // Samsung
        "com.google.android.apps.nbu.files", // Files by Google
    )

    /**
     * Package prefixes treated as watchable (catches variants). These are
     * chat apps whose main windows must NOT trigger, so a viewer-like class
     * name is always required.
     */
    private val KNOWN_PREFIXES = listOf(
        "com.tencent.mm",       // WeChat image viewer / album picker
        "com.tencent.mobileqq", // QQ (incl. QQNT; image preview/gallery pages)
        "com.tencent.qqlite",   // QQ 轻聊版
        "com.tencent.tim",      // TIM
    )

    /** Class-name hints for image-viewer / picker-style screens. */
    private val CLASS_HINTS = listOf(
        "multiselect", "multichoice", "picker", "album", "photopage",
        "photo", "gallery", "preview", "imagepager", "imagebrowse", "image",
    )

    fun matches(packageName: String?, className: String? = null): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (pkg in KNOWN_PACKAGES) return true
        if (KNOWN_PREFIXES.any { pkg.startsWith(it) }) {
            // Chat apps: never trigger on their main/chat windows — a
            // viewer-like class name is mandatory (blank counts as no).
            if (className.isNullOrBlank()) return false
            return classNameLooksLikeViewer(className)
        }
        return false
    }

    private fun classNameLooksLikeViewer(className: String): Boolean {
        val lower = className.lowercase()
        return CLASS_HINTS.any { it in lower }
    }
}
