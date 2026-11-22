package com.nafahatsy.ibh

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * نافذة «اختر النغمة» المشتركة بين شاشة الجرس وإعدادات تنبيهات الدوام: النغمات المدمجة (كل اختيار يُسمَع فوراً)،
 * و«من نغمات الهاتف…» (منتقي نغمات النظام)، و«ملف صوتي من الهاتف…» (منتقي الملفات، المحفوظة على الهاتف فقط).
 * وللتنبيهات أولها «نغمة إشعارات الهاتف». الملف المختار يُنسخ في الخلفية إلى ذاكرة التطبيق (حتى 5 م.ب) مع «جارٍ الحفظ…»،
 * ثم يُشغَّل دائماً من النسخة؛ بلا أذونات دائمة. الشاشة تمرّر onActivityResult إلى handle.
 */
object TonePick {
    const val REQ_RINGTONE = 41
    const val REQ_FILE = 42

    fun show(a: Activity, target: Int, done: () -> Unit) {
        val values = ArrayList<String>()
        val names = ArrayList<String>()
        if (target == BellTone.ALERT) { values.add("s:"); names.add(a.getString(R.string.tone_system)) }
        for (i in BellTone.BUILTIN.indices) { values.add("b:" + BellTone.BUILTIN[i]); names.add(a.getString(BellTone.BUILTIN_NAMES[i])) }
        val cur = BellTone.choice(a, target)
        val custom = cur.startsWith("l:") || cur.startsWith("r:")
        val phoneIdx = values.size
        values.add("r:"); names.add(a.getString(R.string.tone_pick_system))
        values.add("f:"); names.add(a.getString(R.string.tone_pick_file))
        var sel = values.indexOf(cur)
        if (sel < 0 && custom) sel = phoneIdx
        if (custom) names[phoneIdx] = names[phoneIdx] + " · " + BellTone.choiceName(a, target)
        if (sel < 0) sel = 0
        var picked = sel
        AlertDialog.Builder(a)
            .setTitle(if (target == BellTone.BELL) R.string.tone_title_bell else R.string.tone_title_alert)
            .setSingleChoiceItems(names.toTypedArray(), sel) { d, which ->
                picked = which
                val v = values[which]
                if (v == "r:") { d.dismiss(); openSystemPicker(a, target) }
                else if (v == "f:") { d.dismiss(); openFilePicker(a) }
                else BellTone.preview(a, target, v)
            }
            .setPositiveButton(R.string.tone_save) { _, _ ->
                val v = values[picked]
                if (v.startsWith("b:") || v == "s:") { BellTone.setChoice(a, target, v, null); done() }
            }
            .setNegativeButton(R.string.tone_cancel, null)
            .setOnDismissListener { BellTone.stopPreview() }
            .show()
    }

    private fun openSystemPicker(a: Activity, target: Int) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, a.getString(if (target == BellTone.BELL) R.string.tone_title_bell else R.string.tone_title_alert))
        val cur = BellTone.choice(a, target)
        if (cur.startsWith("r:")) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur.substring(2)))
        try { a.startActivityForResult(i, REQ_RINGTONE) } catch (e: ActivityNotFoundException) { openFilePicker(a) }
    }

    private fun openFilePicker(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        // الملفات المحفوظة على الهاتف فقط، لا السحابية
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        try { a.startActivityForResult(i, REQ_FILE) } catch (e: ActivityNotFoundException) { error(a, R.string.no_picker) }
    }

    private fun error(a: Activity, msg: Int) {
        if (a.isFinishing) return
        AlertDialog.Builder(a).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    /** يعيد true إن كانت النتيجة لهذه النافذة */
    fun handle(a: Activity, target: Int, req: Int, res: Int, data: Intent?, done: () -> Unit): Boolean {
        if (req != REQ_RINGTONE && req != REQ_FILE) return false
        if (res != Activity.RESULT_OK || data == null) return true
        val u: Uri? = if (req == REQ_RINGTONE) {
            @Suppress("DEPRECATION")
            data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        } else data.data
        if (u == null) return true
        val fromSystem = req == REQ_RINGTONE
        val wait = AlertDialog.Builder(a).setMessage(R.string.tone_saving).setCancelable(false).show()
        val ui = Handler(Looper.getMainLooper())
        val app = a.applicationContext
        Thread {
            var result = 0 // 0 نُسخ، 1 كبير، 2 تعذّرت قراءته
            var name: String? = null
            var file: String? = null
            try {
                var size = -1L
                try {
                    app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            name = c.getString(0)
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                } catch (e: Exception) {}
                if (fromSystem) try { name = RingtoneManager.getRingtone(app, u)?.getTitle(app) ?: name } catch (e: Exception) {}
                if (size > BellTone.MAX_FILE) result = 1
                else {
                    val f = copy(app, u, target, name)
                    if (f == null) result = 2 else if (f == "") result = 1 else file = f
                }
            } catch (e: Exception) { result = 2 }
            ui.post {
                try { wait.dismiss() } catch (e: Exception) {}
                if (a.isFinishing) return@post
                val f = file
                when {
                    f != null -> { BellTone.setChoice(a, target, "l:$f", stripExt(name)); done(); BellTone.preview(a, target, "l:$f") }
                    result == 1 -> error(a, R.string.tone_too_large)
                    // نغمة نظام لا يقرؤها التطبيق (نغمة مخصّصة في ذاكرة الهاتف): تُحفظ رابطاً وتُشغَّل بمهلة ثانية، وإلا فالأساسية
                    fromSystem -> { BellTone.setChoice(a, target, "r:$u", name); done(); BellTone.preview(a, target, "r:$u") }
                    else -> error(a, R.string.tone_unreadable)
                }
            }
        }.start()
        return true
    }

    private fun stripExt(n: String?): String? {
        if (n == null) return null
        val i = n.lastIndexOf('.')
        return if (i > 0 && n.length - i <= 5) n.substring(0, i) else n
    }

    /** ينسخ إلى tones/<bell|alert>-<ختم>.<امتداد>. يعيد اسم الملف، أو "" إن تجاوز 5 م.ب، أو null إن تعذّرت القراءة */
    private fun copy(c: android.content.Context, u: Uri, target: Int, name: String?): String? {
        val dir = BellTone.toneDir(c)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var ext = ""
        val n = name ?: ""
        val dot = n.lastIndexOf('.')
        if (dot > 0 && n.length - dot in 2..5) ext = n.substring(dot + 1).lowercase(java.util.Locale.ROOT)
        if (ext.isEmpty()) ext = try { MimeTypeMap.getSingleton().getExtensionFromMimeType(c.contentResolver.getType(u)) ?: "" } catch (e: Exception) { "" }
        if (!EXT_RE.matcher(ext).matches()) ext = "audio"
        val base = if (target == BellTone.BELL) "bell" else "alert"
        val tmp = File(dir, "$base.tmp")
        try {
            val input = c.contentResolver.openInputStream(u) ?: return null
            var total = 0L
            var tooBig = false
            input.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > BellTone.MAX_FILE) { tooBig = true; break }
                        out.write(buf, 0, r)
                    }
                }
            }
            if (tooBig) { tmp.delete(); return "" }
            if (total == 0L) { tmp.delete(); return null }
            // اسم جديد في كل مرة (بختم زمني)، والقديم يحذفه setChoice
            val fname = base + "-" + System.currentTimeMillis() + "." + ext
            if (!tmp.renameTo(File(dir, fname))) { tmp.delete(); return null }
            return fname
        } catch (e: Exception) { tmp.delete(); return null }
    }

    private val EXT_RE: java.util.regex.Pattern = java.util.regex.Pattern.compile("^[a-z0-9]{1,5}$")
}
