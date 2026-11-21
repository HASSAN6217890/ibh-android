package com.nafahatsy.ibh

import android.content.Context
import android.net.Uri

/** ثوابت التطبيق: النطاق الوحيد المسموح، وألوان الهوية، وحدود الجسر */
object Cfg {
    const val HOST = "ibh.nafahatsy.com"
    const val ORIGIN = "https://$HOST"
    const val HOME = "$ORIGIN/"
    /** صفحة إقلاع داخلية بلا شبكة على نطاق المعهد نفسه: يُعاد فيها رمز الجلسة إلى sessionStorage قبل فتح المنصة */
    const val BOOT_URL = "$ORIGIN/__ibh_app_boot__"
    const val BELL_FEED = "$ORIGIN/api/bell"

    const val GREEN_800 = 0xFF173E25.toInt()
    const val CREAM = 0xFFFFFDF8.toInt()
    const val INK = 0xFF18231D.toInt()
    const val MUTED = 0xFF5A655F.toInt()
    const val ORANGE = 0xFFE8602C.toInt()

    /** أقصى حجم ملف يقبله جسر الحفظ: 25 م.ب (النص base64 أكبر بالثلث، ويمر كله في ذاكرة WebView والتطبيق) */
    const val MAX_SAVE_BYTES = 25L * 1024 * 1024

    /** أقدم WebView يشغّل app.js (?. و??) */
    const val MIN_WEBVIEW_MAJOR = 80

    fun isOurs(url: String?): Boolean {
        if (url == null) return false
        val u = try { Uri.parse(url) } catch (e: Exception) { return false }
        return "https" == u.scheme && HOST == u.host && (u.port == -1 || u.port == 443)
    }

    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
}
