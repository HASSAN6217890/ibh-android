package com.nafahatsy.ibh

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * منطق جدول الجرس المشترك بين شاشة «هاتف الجرس» (BellActivity) والمنبّه الدقيق (BellAlarmReceiver):
 * آخر جدول محفوظ، والوقت بساعة الخادم ومنطقته، وسجل «ما رنّ اليوم» (فلا يرنّ الموعد مرتين إن أدركه المؤقت والمنبّه معاً)،
 * وجدولة الرنّة التالية بـ AlarmManager.setAlarmClock (apk-readiness.md، المرحلة ب البند 11: منبّهات دقيقة).
 */
object BellPlan {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("ibh_bell", Context.MODE_PRIVATE)

    fun feed(c: Context): JSONObject? {
        val s = prefs(c).getString("feed", null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    /**
     * فرق ساعة الخادم عن ساعة الهاتف. يُحسب من مرساة على الساعة الرتيبة (elapsedRealtime) منذ آخر تحميل ناجح، فلا يفسد إن
     * غُيّرت ساعة الهاتف بعده (ضبط تلقائي أو يدوي، والجدول من المحفوظ بلا إنترنت) — كان الفرق المحفوظ ثابتاً فيُخطئ الوقت بقدر
     * التغيير حتى التحميل التالي. بعد إعادة تشغيل الهاتف (عدّاد الإقلاع تغيّر) يُستعمل الفرق المحفوظ.
     */
    fun offset(c: Context): Long {
        val p = prefs(c)
        val el0 = p.getLong("srv_el", -1L)
        if (el0 >= 0 && p.getInt("srv_boot", -2) == bootCount(c)) {
            val el = android.os.SystemClock.elapsedRealtime()
            if (el >= el0) return p.getLong("srv_ms", 0L) + (el - el0) - System.currentTimeMillis()
        }
        return p.getLong("offset", 0L)
    }

    private fun bootCount(c: Context): Int = try {
        android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) { -1 }

    /** يحفظ ساعة الخادم عند منتصف الطلب: serverMs بالمللي، وmidWall/midEl منتصف الطلب بساعة الهاتف والساعة الرتيبة */
    fun saveClock(c: Context, serverMs: Long?, midWall: Long, midEl: Long) {
        val e = prefs(c).edit()
        if (serverMs == null) e.putLong("offset", 0L).remove("srv_el")
        else e.putLong("offset", serverMs - midWall).putLong("srv_ms", serverMs).putLong("srv_el", midEl).putInt("srv_boot", bootCount(c))
        e.apply()
    }

    private fun tz(feed: JSONObject?): TimeZone {
        val id = feed?.optString("tz") ?: ""
        return if (id.isNotEmpty()) TimeZone.getTimeZone(id) else TimeZone.getDefault()
    }

    /** التاريخ ودقيقة اليوم (بكسورها) الآن بتوقيت الخادم */
    fun now(feed: JSONObject?, offsetMs: Long): Pair<String, Double> {
        val c = Calendar.getInstance(tz(feed))
        c.timeInMillis = System.currentTimeMillis() + offsetMs
        val d = String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return Pair(d, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0)
    }

    fun day(feed: JSONObject?, date: String): JSONObject? {
        val f = feed ?: return null
        val w = f.optJSONArray("week")
        if (w != null) for (i in 0 until w.length()) { val d = w.optJSONObject(i); if (d != null && date == d.optString("date")) return d }
        return if (date == f.optString("date")) f else null
    }

    fun minuteOf(s: String?): Int? {
        if (s == null) return null
        val p = UiKit.splitOn(s, ':')
        if (p.size < 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        return h * 60 + m
    }

    fun hm(m: Int) = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    /** رنّات يوم (فارغة إن لم يكن يوم جرس): [دقيقة، النص] */
    fun rings(day: JSONObject?): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        if (day == null || !day.optBoolean("ring")) return out
        val rs = day.optJSONArray("rings") ?: JSONArray()
        for (i in 0 until rs.length()) {
            val r = rs.optJSONObject(i) ?: continue
            val m = minuteOf(r.optString("at")) ?: continue
            out.add(Pair(m, hm(m) + " · " + r.optString("label")))
        }
        return out
    }

    // ---- ما رنّ اليوم

    fun key(date: String, m: Int) = date + "|" + hm(m)

    fun wasRung(c: Context, key: String): Boolean = prefs(c).getStringSet("rung", null)?.contains(key) == true

    fun wasMissed(c: Context, key: String): Boolean = prefs(c).getStringSet("missed", null)?.contains(key) == true

    /** موعد فات ولم يرنّ (بعد تشغيل الجرس): لعرض «✗ فات» في قائمة اليوم */
    fun markMissed(c: Context, key: String) {
        val date = UiKit.splitOn(key, '|')[0]
        val keep = HashSet<String>()
        val old = prefs(c).getStringSet("missed", null)
        if (old != null) for (k in old) if (k.startsWith("$date|")) keep.add(k)
        keep.add(key)
        prefs(c).edit().putStringSet("missed", keep).apply()
    }

    fun markRung(c: Context, key: String) {
        val date = UiKit.splitOn(key, '|')[0]
        val keep = HashSet<String>()
        val old = prefs(c).getStringSet("rung", null)
        if (old != null) for (k in old) if (k.startsWith("$date|")) keep.add(k)
        keep.add(key)
        prefs(c).edit().putStringSet("rung", keep).apply()
    }

    // ---- بدء التشغيل: لا يُبلَّغ عن «فات» لموعد قبل أن يُشغَّل الجرس (الويب يعرضه «قبل تشغيل الجرس»)

    /** يحفظ لحظة تشغيل الجرس بزر «شغّل» (تاريخ|دقيقة بتوقيت الخادم). force=false: فقط إن لم تكن محفوظة (ترقية من 1.0.2) */
    fun markOnFrom(c: Context, date: String, minute: Double, force: Boolean) {
        if (!force && prefs(c).contains("on_from")) return
        prefs(c).edit().putString("on_from", date + "|" + minute.toInt()).apply()
    }

    /** هل الموعد بعد تشغيل الجرس، فيُعدّ فواته «لم يرنّ» يُبلَّغ عنه؟ */
    fun afterOn(c: Context, date: String, m: Int): Boolean {
        val s = prefs(c).getString("on_from", null) ?: return false
        val p = UiKit.splitOn(s, '|')
        if (p.size != 2) return false
        val m0 = p[1].toIntOrNull() ?: return false
        val cmp = date.compareTo(p[0])
        // الموعد في دقيقة التشغيل نفسها لا يُعدّ (ربما فات قبل الضغط بثوانٍ)
        return cmp > 0 || (cmp == 0 && m > m0)
    }

    /** عند التشغيل بالزر: مواعيد اليوم حتى الآن تُعلَّم «رنّت» بلا تقرير، فلا يرنّها المنبّه ولا يعدّها «فاتت» */
    fun markPastAsRung(c: Context, feed: JSONObject?, date: String, minute: Double) {
        for (r in rings(day(feed, date))) if (r.first <= minute) { val k = key(date, r.first); if (!wasRung(c, k)) markRung(c, k) }
    }

    // ---- آخر دقيقة فحصها المنبّه: موعد أُضيف إلى الجدول بعد فوات وقته لا يُعدّ «فات ولم يرنّ»

    fun setChecked(c: Context, date: String, minute: Double) {
        prefs(c).edit().putString("rx_last", date + "|" + minute.toInt()).apply()
    }

    /** هل وقع الموعد بعد آخر فحص (فكان في الجدول المحفوظ قبل أن يفوت)؟ بلا فحص سابق: نعم (afterOn يحرس) */
    fun afterChecked(c: Context, date: String, m: Int): Boolean {
        val s = prefs(c).getString("rx_last", null) ?: return true
        val p = UiKit.splitOn(s, '|')
        if (p.size != 2) return true
        val m0 = p[1].toIntOrNull() ?: return true
        val cmp = date.compareTo(p[0])
        return cmp > 0 || (cmp == 0 && m > m0)
    }

    // ---- المنبّه الدقيق

    /** الرنّة التالية بعد الآن في الجدول المحفوظ: [وقتها بساعة الهاتف، المفتاح] */
    fun next(feed: JSONObject?, offsetMs: Long): Pair<Long, String>? {
        val f = feed ?: return null
        val n = now(f, offsetMs)
        val dates = ArrayList<String>()
        val w = f.optJSONArray("week")
        if (w != null) for (i in 0 until w.length()) { val d = w.optJSONObject(i)?.optString("date"); if (d != null && d >= n.first && !dates.contains(d)) dates.add(d) }
        if (dates.isEmpty() && f.optString("date") >= n.first) dates.add(f.optString("date"))
        java.util.Collections.sort(dates)
        for (d in dates) {
            for (r in rings(day(f, d))) {
                if (d == n.first && r.first <= n.second) continue
                val p = UiKit.splitOn(d, '-')
                if (p.size != 3) continue
                val c = Calendar.getInstance(tz(f))
                c.clear()
                c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), r.first / 60, r.first % 60, 0)
                return Pair(c.timeInMillis - offsetMs, key(d, r.first))
            }
        }
        return null
    }

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, BellAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun cancel(c: Context) {
        try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c)) } catch (e: Exception) {}
    }

    /** يضبط منبّه الرنّة التالية (أو يلغيه إن كان الجرس متوقفاً أو لا رنّة قادمة في الجدول المحفوظ) */
    fun schedule(c: Context) {
        cancel(c)
        if (!prefs(c).getBoolean("on", false)) return
        val f = feed(c) ?: return
        val nx = next(f, offset(c)) ?: return
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val op = pending(c)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                val show = PendingIntent.getActivity(c, 2, Intent(c, BellActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(nx.first, show), op)
            } else {
                // بلا إذن المنبّهات الدقيقة (نادر: USE_EXACT_ALARM يُمنح تلقائياً من أندرويد 13): منبّه تقريبي، والمؤقت في الشاشة يبقى الأساس
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nx.first, op)
            }
        } catch (e: Exception) {}
    }
}

/**
 * يستيقظ في موعد الرنّة ولو كان المعالج نائماً أو قُتلت العملية: يرنّ إن لم يرنّ الموعد بعد ولم يفت بأكثر من دقيقتين،
 * ثم يضبط الرنّة التالية. لا يطلب الجدول من الشبكة؛ وتقرير الرنّة (BellReport) يُرسل في خيط خلفي لا ينتظره الرنين.
 */
class BellAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val p = BellPlan.prefs(c)
        if (!p.getBoolean("on", false)) return
        val pr = goAsync()
        val wl = try {
            val w = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibh:bell-alarm")
            w.acquire(20_000L); w
        } catch (e: Exception) { null }
        var delay = 300L
        val gap = try { BellTone.durationMs(c, BellTone.BELL, 2) + 400L } catch (e: Exception) { 2500L }
        try {
            val f = BellPlan.feed(c)
            val n = BellPlan.now(f, BellPlan.offset(c))
            var i = 0
            for (r in BellPlan.rings(BellPlan.day(f, n.first))) {
                if (r.first > n.second) continue
                val k = BellPlan.key(n.first, r.first)
                if (BellPlan.wasRung(c, k)) continue
                BellPlan.markRung(c, k)
                val hm = BellPlan.hm(r.first)
                if (n.second - r.first <= 2.0) {
                    val at = i * gap
                    val day = n.first
                    Handler(Looper.getMainLooper()).postDelayed({
                        val pl = BellTone.play(c, BellTone.BELL, 2)
                        BellReport.queue(c, day, hm, true, pl.fellBack)
                    }, at)
                    delay = at + gap
                    i++
                } else if (BellPlan.afterOn(c, n.first, r.first) && BellPlan.afterChecked(c, n.first, r.first)) {
                    BellPlan.markMissed(c, k)
                    BellReport.queue(c, n.first, hm, false)
                }
            }
            BellPlan.setChecked(c, n.first, n.second)
            BellPlan.schedule(c)
            // تقارير سابقة لم تُرسل (انقطع الإنترنت): محاولة جديدة مع كل منبّه، في خيط خلفي
            BellReport.flush(c)
        } catch (e: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { wl?.release() } catch (e: Exception) {}
            pr.finish()
        }, delay)
    }
}
