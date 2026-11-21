package com.nafahatsy.ibh

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** حفظ ملف في مجلد «التنزيلات» العام: عبر MediaStore من أندرويد 10، وملفاً مباشراً (بإذن الكتابة) في 7–9 */
object FileSaver {
    private val MIME_RE = java.util.regex.Pattern.compile("^[a-zA-Z0-9.+-]{1,60}/[a-zA-Z0-9.+-]{1,100}$")

    fun cleanMime(m: String?): String {
        val s = UiKit.splitOn(m ?: "", ';')[0].trim().lowercase(java.util.Locale.ROOT)
        return if (MIME_RE.matcher(s).matches()) s else "application/octet-stream"
    }

    /**
     * الأنواع المسموح حفظها من المنصة وحدها (الامتداد ← النوع). كل ما سواها يُرفض، ومنه ‎.apk وأي حزمة تثبيت.
     * docx لملفات مكتب الطباعة، و‎.enc وgz للنسخ الاحتياطية المشفّرة (db-…sql.gz.enc في backup.php).
     */
    private val TYPES: Map<String, String> = run {
        val m = HashMap<String, String>()
        m["pdf"] = "application/pdf"; m["csv"] = "text/csv"; m["txt"] = "text/plain"; m["json"] = "application/json"
        m["jpg"] = "image/jpeg"; m["jpeg"] = "image/jpeg"; m["png"] = "image/png"; m["webp"] = "image/webp"; m["gif"] = "image/gif"
        m["xlsx"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        m["docx"] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        m["zip"] = "application/zip"; m["gz"] = "application/gzip"; m["enc"] = "application/octet-stream"
        m
    }
    private val BY_MIME: Map<String, String> = run {
        val m = HashMap<String, String>()
        m["application/pdf"] = "pdf"; m["text/csv"] = "csv"; m["text/plain"] = "txt"; m["application/json"] = "json"
        m["image/jpeg"] = "jpg"; m["image/png"] = "png"; m["image/webp"] = "webp"; m["image/gif"] = "gif"
        m["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"] = "xlsx"
        m["application/vnd.openxmlformats-officedocument.wordprocessingml.document"] = "docx"
        m["application/zip"] = "zip"; m["application/gzip"] = "gz"
        m
    }

    /** الاسم الآمن والنوع الموحّد إن كان النوع مسموحاً، وإلا null. النوع يُؤخذ من الامتداد لا مما أرسلته الصفحة */
    fun allowed(name: String?, mime: String?): Pair<String, String>? {
        var n = cleanName(name)
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0) n.substring(dot + 1).lowercase(java.util.Locale.ROOT) else ""
        if (ext == "apk" || ext == "apks" || ext == "xapk" || cleanMime(mime) == "application/vnd.android.package-archive") return null
        val byExt = TYPES[ext]
        if (byExt != null) return Pair(n, byExt)
        if (dot > 0) return null
        val e2 = BY_MIME[cleanMime(mime)] ?: return null
        n = "$n.$e2"
        return Pair(n, TYPES[e2]!!)
    }

    /** اسم آمن: بلا مسارات ولا محارف محظورة، وبطول معقول */
    fun cleanName(n: String?): String {
        val sb = StringBuilder()
        for (ch in (n ?: "")) {
            if (ch.code < 32 || "/\\:*?\"<>|".indexOf(ch) >= 0) sb.append('_') else sb.append(ch)
        }
        var s = sb.toString().trim()
        while (s.startsWith(".")) s = s.substring(1)
        if (s.isEmpty()) s = "ملف"
        if (s.length > 120) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
            s = s.substring(0, 120 - ext.length) + ext
        }
        return s
    }

    /** يحتاج إذن الكتابة؟ (أندرويد 9 فما دون فقط) */
    fun needsLegacyPermission() = Build.VERSION.SDK_INT < 29

    /** يعيد رابط content:// للملف المحفوظ (أو null إن تعذّر الحصول عليه)، ويرمي استثناءً إن فشل الحفظ */
    fun save(c: Context, bytes: ByteArray, name: String, mime: String, done: (Uri?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = c.contentResolver
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            v.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("insert")
            try {
                val os = cr.openOutputStream(uri) ?: throw IllegalStateException("open")
                try { os.write(bytes) } finally { os.close() }
                val fin = ContentValues()
                fin.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, fin, null, null)
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (x: Exception) {}
                throw e
            }
            done(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var f = File(dir, name)
            if (f.exists()) {
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var i = 1
                while (f.exists() && i < 1000) { f = File(dir, "$base ($i)$ext"); i++ }
            }
            val os = FileOutputStream(f)
            try { os.write(bytes) } finally { os.close() }
            MediaScannerConnection.scanFile(c.applicationContext, arrayOf(f.absolutePath), arrayOf(mime)) { _, uri -> done(uri) }
        }
    }
}
