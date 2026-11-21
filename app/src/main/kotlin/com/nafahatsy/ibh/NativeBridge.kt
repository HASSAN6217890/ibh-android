package com.nafahatsy.ibh

import android.util.Base64
import android.webkit.JavascriptInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * الجسر الأصلي window.__ibhN. لا تستدعيه المنصة مباشرة: hook.js يبني فوقه window.IBHNative.
 * - كل دالة تغيّر شيئاً تشترط أول وسيط سرّاً عشوائياً يولَّد عند كل تشغيل (secret) ويُكتب داخل دالة hook.js وحدها.
 * - وكل دالة ترفض العمل إن لم تكن الصفحة الحالية من نطاق المعهد (التنقل خارجه ممنوع أصلاً، وهذا حارس ثانٍ).
 * يُستدعى من خيط الجسر (لا الخيط الرئيسي)، فكل عمل على الواجهة يُرسل إلى الخيط الرئيسي.
 */
class NativeBridge(private val a: MainActivity) {

    /** 32 بايتاً عشوائياً بالست عشرية، جديد لكل نافذة (كل تشغيل) */
    val secret: String = run {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder()
        for (x in b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x.toInt() and 0xff))
        sb.toString()
    }
    private val secretBytes = secret.toByteArray(Charsets.US_ASCII)

    private fun ok(s: String?): Boolean =
        a.originOk && s != null && MessageDigest.isEqual(secretBytes, s.toByteArray(Charsets.US_ASCII))

    /**
     * saveFile(secret, base64, name, mime). المنصة تستدعي IBHNative.saveFile(b64, name, mime) (saveBlob في app.js) فيضيف hook.js السر.
     * يعيد true إن قُبل الملف للحفظ (يكتمل في الخلفية ثم تظهر نافذة «حُفظ الملف»)، وfalse إن رُفض: نوع غير مسموح، أو حجم فوق 25 م.ب، أو سر خاطئ.
     */
    @JavascriptInterface
    fun saveFile(s: String?, b64: String?, name: String?, mime: String?): Boolean {
        if (!ok(s) || b64 == null) return false
        if (b64.length.toLong() > Cfg.MAX_SAVE_BYTES * 4 / 3 + 8) { a.toastOnUi(a.getString(R.string.save_too_large)); return false }
        val t = FileSaver.allowed(name, mime)
        if (t == null) { a.toastOnUi(a.getString(R.string.save_type_blocked)); return false }
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Exception) { return false }
        if (bytes.size.toLong() > Cfg.MAX_SAVE_BYTES) { a.toastOnUi(a.getString(R.string.save_too_large)); return false }
        a.runOnUiThread { a.saveDownload(bytes, t.first, t.second) }
        return true
    }

    /** الطباعة الأصلية (نافذة الطباعة في أندرويد، ومنها «حفظ بصيغة PDF») */
    @JavascriptInterface
    fun print(s: String?, title: String?) {
        if (!ok(s)) return
        a.runOnUiThread { a.printPage(title ?: "") }
    }

    /** إبقاء الشاشة مضاءة ما دام قفل wakeLock فعّالاً في الصفحة (صفحة الجرس) */
    @JavascriptInterface
    fun keepScreenOn(s: String?, on: Boolean) {
        if (!ok(s)) return
        a.runOnUiThread { a.setKeepOn(on) }
    }

    /** رمز الجلسة كُتب في sessionStorage («ibh_t»): يُحفظ مشفّراً */
    @JavascriptInterface
    fun tokenSet(s: String?, t: String?) {
        if (!ok(s) || t == null || !TOKEN_RE.matcher(t).matches()) return
        a.store.put(SecureStore.SESSION, t)
    }

    /** الخروج أو انتهاء الجلسة أو صفحة بلا جلسة: يُمسح الرمز المحفوظ */
    @JavascriptInterface
    fun tokenClear(s: String?) {
        if (!ok(s)) return
        a.store.put(SecureStore.SESSION, null)
    }

    /** فتح وضع «هاتف الجرس» (لزر تضيفه المنصة لاحقاً في شاشة الدخول أو صفحة الجرس) */
    @JavascriptInterface
    fun openBell(s: String?) {
        if (!ok(s)) return
        a.runOnUiThread { a.openBell() }
    }

    /**
     * setBellToken(secret, token): بعد «أضف جهاز جرس» على هاتف الجرس نفسه، تسلّم المنصة الرمز مباشرة بدل نسخه ولصقه.
     * الرمز قراءة لجرس فرع واحد فقط (bt_ ثم 40 حرفاً)، فيُحفظ مشفّراً ويُفتح وضع «هاتف الجرس». يعيد false إن كان الشكل خاطئاً.
     */
    @JavascriptInterface
    fun setBellToken(s: String?, t: String?): Boolean {
        if (!ok(s) || t == null) return false
        val v = t.trim().lowercase(java.util.Locale.ROOT)
        if (!BellActivity.TOKEN_RE.matcher(v).matches()) return false
        a.store.put(SecureStore.BELL, v)
        a.runOnUiThread { a.openBell() }
        return true
    }

    /** للقراءة فقط: لا يغيّر شيئاً، فلا يشترط السر */
    @JavascriptInterface
    fun appVersion(): String = Cfg.versionName(a)

    companion object {
        val TOKEN_RE: java.util.regex.Pattern = java.util.regex.Pattern.compile("^[a-f0-9]{64}$")
    }
}
