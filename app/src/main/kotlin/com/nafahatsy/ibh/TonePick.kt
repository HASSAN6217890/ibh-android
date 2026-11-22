package com.nafahatsy.ibh

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast

/**
 * نافذة «اختر النغمة» المشتركة بين شاشة الجرس وإعدادات تنبيهات الدوام: النغمات المدمجة (وكل اختيار يُسمَع فوراً)،
 * و«من نغمات الجوال…» (منتقي نغمات النظام: منبّه ورنين وإشعار)، و«ملف صوتي من الجوال…» (منتقي الملفات، بإذن قراءة دائم).
 * وللتنبيهات أولها «نغمة إشعارات الهاتف». الشاشة تمرّر onActivityResult إلى handle.
 */
object TonePick {
    const val REQ_RINGTONE = 41
    const val REQ_FILE = 42

    /** done يُستدعى بعد حفظ اختيار جديد (لتحديث السطر في الشاشة) */
    fun show(a: Activity, target: Int, done: () -> Unit) {
        val values = ArrayList<String>()
        val names = ArrayList<String>()
        if (target == BellTone.ALERT) { values.add("s:"); names.add(a.getString(R.string.tone_system)) }
        for (i in BellTone.BUILTIN.indices) { values.add("b:" + BellTone.BUILTIN[i]); names.add(a.getString(BellTone.BUILTIN_NAMES[i])) }
        val cur = BellTone.choice(a, target)
        val phoneIdx = values.size
        values.add("r:"); names.add(a.getString(R.string.tone_pick_system) + if (cur.startsWith("r:")) " · " + BellTone.choiceName(a, target) else "")
        values.add("f:"); names.add(a.getString(R.string.tone_pick_file) + if (cur.startsWith("f:")) " · " + BellTone.choiceName(a, target) else "")
        var sel = values.indexOf(cur)
        if (sel < 0) sel = if (cur.startsWith("r:")) phoneIdx else if (cur.startsWith("f:")) phoneIdx + 1 else 0
        var picked = sel
        val dlg = AlertDialog.Builder(a)
            .setTitle(if (target == BellTone.BELL) R.string.tone_title_bell else R.string.tone_title_alert)
            .setSingleChoiceItems(names.toTypedArray(), sel) { d, which ->
                picked = which
                val v = values[which]
                if (v == "r:") { d.dismiss(); BellTone.stop(); openSystemPicker(a, target) }
                else if (v == "f:") { d.dismiss(); BellTone.stop(); openFilePicker(a) }
                else BellTone.preview(a, target, v)
            }
            .setPositiveButton(R.string.tone_save) { _, _ ->
                val v = values[picked]
                if (v.startsWith("b:") || v == "s:") { BellTone.setChoice(a, target, v, null); done() }
            }
            .setNegativeButton(R.string.tone_cancel, null)
            .setOnDismissListener { BellTone.stop() }
            .create()
        dlg.show()
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
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { a.startActivityForResult(i, REQ_FILE) } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    /** يعيد true إن كانت النتيجة لهذه النافذة (وحُفظ الاختيار إن وُجد) */
    fun handle(a: Activity, target: Int, req: Int, res: Int, data: Intent?, done: () -> Unit): Boolean {
        if (req != REQ_RINGTONE && req != REQ_FILE) return false
        if (res != Activity.RESULT_OK || data == null) return true
        if (req == REQ_RINGTONE) {
            @Suppress("DEPRECATION")
            val u = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return true
            val name = try { RingtoneManager.getRingtone(a, u)?.getTitle(a) } catch (e: Exception) { null }
            BellTone.setChoice(a, target, "r:$u", name)
        } else {
            val u = data.data ?: return true
            try { a.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {
                Toast.makeText(a, R.string.tone_file_denied, Toast.LENGTH_LONG).show(); return true
            }
            var name: String? = null
            try {
                a.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
            } catch (e: Exception) {}
            BellTone.setChoice(a, target, "f:$u", name)
        }
        done()
        BellTone.preview(a, target, BellTone.choice(a, target))
        return true
    }
}
