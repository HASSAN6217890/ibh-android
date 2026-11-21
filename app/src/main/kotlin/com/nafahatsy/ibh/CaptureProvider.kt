package com.nafahatsy.ibh

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * مزوّد صغير لصورة الكاميرا (بديل FileProvider من غير مكتبات خارجية):
 * يكتب تطبيق الكاميرا الصورة في ملف داخل ذاكرة التطبيق المؤقتة (cache/capture)، ثم يقرؤها WebView للرفع.
 * غير مُصدَّر، ولا يصل إليه تطبيق آخر إلا بإذن مؤقت يُمنح للكاميرا مع طلب التصوير وحده.
 */
class CaptureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!NAME_RE.matcher(name).matches() || uri.pathSegments.size != 1) throw FileNotFoundException()
        return File(dir(ctx), name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            if (OpenableColumns.DISPLAY_NAME == cols[i]) row[i] = f.name
            else if (OpenableColumns.SIZE == cols[i]) row[i] = f.length()
        }
        c.addRow(row)
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.nafahatsy.ibh.capture"
        private val NAME_RE = java.util.regex.Pattern.compile("^cap_[0-9]{6,20}\\.jpg$")

        fun dir(c: Context): File { val d = File(c.cacheDir, "capture"); d.mkdirs(); return d }

        /** ملف جديد فارغ للتصوير، مع حذف صور الجلسات السابقة (أقدم من ساعة) */
        fun newCapture(c: Context): Pair<File, Uri> {
            val d = dir(c)
            val old = d.listFiles()
            if (old != null) for (f in old) if (System.currentTimeMillis() - f.lastModified() > 3600_000L) f.delete()
            val f = File(d, "cap_" + System.currentTimeMillis() + ".jpg")
            f.createNewFile()
            return Pair(f, Uri.parse("content://$AUTHORITY/" + f.name))
        }
    }
}
