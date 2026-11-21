package com.nafahatsy.ibh

import android.content.Context
import android.graphics.drawable.GradientDrawable
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** مساعدات صغيرة للواجهة والقراءة، بلا مكتبات خارجية */
object UiKit {
    fun pill(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    fun outline(stroke: Int, radius: Int, width: Int, fill: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setStroke(width, stroke)
        g.cornerRadius = radius.toFloat()
        return g
    }

    /** تقسيم نص على محرف واحد (بديل split في مكتبة Kotlin، التي يحتاج بعضها invokedynamic) */
    fun splitOn(s: String, ch: Char): Array<String> {
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            val i = s.indexOf(ch, start)
            if (i < 0) { out.add(s.substring(start)); break }
            out.add(s.substring(start, i))
            start = i + 1
        }
        return out.toTypedArray()
    }

    fun readAll(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw java.io.IOException("too large")
            }
        } finally { input.close() }
        return out.toByteArray()
    }

    fun readAsset(c: Context, name: String): String = try {
        String(readAll(c.assets.open(name), 1 shl 20), Charsets.UTF_8)
    } catch (e: Exception) { "" }
}
