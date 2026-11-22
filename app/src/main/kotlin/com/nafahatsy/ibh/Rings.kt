package com.nafahatsy.ibh

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تنبيهات الدوام الشخصية (التطبيق 1.2.0): بداية الحصص ونهايتها والاستراحة للمدرّسين والإداريين، والهاتف مقفل والتطبيق مغلق،
 * بلا وضع «هاتف الجرس».
 * - المصدر: GET /api/me/rings?days=7 بجلسة التطبيق (Bearer + X-IBH-App: 1). القائمة تُحفظ في تفضيلات خاصة (ibh_rings).
 * - يُطلب عند فتح التطبيق (كل 10 دقائق على الأكثر)، وبعد الدخول أو تغيّر الحساب، ومرة يومياً بمنبّه غير دقيق،
 *   وبعد إعادة تشغيل الهاتف أو تحديث التطبيق أو تغيير الوقت/المنطقة. إن لم تتغيّر البصمة (hash) لا يُعاد شيء.
 * - منبّه واحد للتنبيه التالي فقط بـ setAlarmClock (لا يحدّه وضع السكون؛ setExactAndAllowWhileIdle محدود بمرة كل 9 دقائق تقريباً
 *   فيتأخر تنبيه «قبل النهاية بدقيقة» المتبوع بتنبيه بعد دقيقة). وبلا إذن الدقة منبّه تقريبي. عند وقته: إشعار، ثم التالي.
 * - رموز الطلب (10–12) ومستقبِل منفصل، فلا يمسّ منبّهات هاتف الجرس (BellPlan: 1 و2 على BellAlarmReceiver).
 * - الخروج أو 401/403: تُمسح القائمة وتُلغى المنبّهات.
 */
object RingSched {
    const val CH_SOUND = "duty_sound"
    const val CH_VIBE = "duty_vibe"
    /** قناة صامتة تهتز، يشغّل التطبيق معها النغمة المختارة بنفسه (1.3.0) */
    const val CH_APP = "duty_app"
    private const val RC_NEXT = 10
    private const val RC_DAILY = 11
    private const val RC_OPEN = 12
    private const val RC_SHOW = 13
    private const val NOTIF_ID = 7001
    const val ACTION_FIRE = "com.nafahatsy.ibh.RING_FIRE"
    const val ACTION_DAILY = "com.nafahatsy.ibh.RING_DAILY"
    /** موعد فات بأكثر من 10 دقائق (الهاتف كان مطفأً) لا يُنبَّه به متأخراً؛ وما دونها يظهر متأخراً ولا يضيع */
    private const val LATE_MS = 10 * 60_000L

    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("ibh_rings", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- الإعدادات

    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun vibrateOnly(c: Context) = prefs(c).getBoolean("vibe", false)
    fun earlyEnd(c: Context) = prefs(c).getBoolean("early", true)

    fun setEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("on", v).apply()
        if (v) { scheduleDaily(c); scheduleNext(c); fetch(c, true, null) } else { cancelNext(c); cancelDaily(c) }
    }
    fun setVibrateOnly(c: Context, v: Boolean) { prefs(c).edit().putBoolean("vibe", v).apply() }
    /** لا يُمسح سجل ما نُبِّه به: البند الذي نُبِّه به لا يتكرر بتغيير التوقيت (fired) */
    fun setEarlyEnd(c: Context, v: Boolean) { prefs(c).edit().putBoolean("early", v).apply(); scheduleNext(c) }

    private fun itemKey(it: JSONObject) = it.optString("_date") + "|" + it.optString("at") + "|" + it.optString("kind")

    private fun wasFired(c: Context, it: JSONObject) = prefs(c).getStringSet("fired", null)?.contains(itemKey(it)) == true

    private fun markFired(c: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        val keep = HashSet<String>()
        val old = prefs(c).getStringSet("fired", null)
        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_MONTH, -1)
        val minDate = String.format(Locale.ROOT, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (old != null) for (k in old) if (UiKit.splitOn(k, '|')[0] >= minDate) keep.add(k)
        keep.addAll(keys)
        prefs(c).edit().putStringSet("fired", keep).apply()
    }

    fun notificationsAllowed(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false
        return try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled() } catch (e: Exception) { true }
    }

    // ---------------------------------------------------------------- الجلب

    /** هل في القائمة المحفوظة موعد قادم؟ (لطلب إذن الإشعارات أول مرة) */
    fun hasUpcoming(c: Context): Boolean = nextItem(c) != null

    fun onForeground(c: Context, done: ((Boolean) -> Unit)?) {
        if (!enabled(c)) return
        scheduleDaily(c)
        scheduleNext(c)
        if (System.currentTimeMillis() - prefs(c).getLong("try_at", 0L) < 10 * 60_000L) { done?.invoke(hasUpcoming(c)); return }
        fetch(c, false, done)
    }

    fun sessionChanged(c: Context) {
        clear(c)
        if (enabled(c)) { scheduleDaily(c); fetch(c, true, null) }
    }

    /** الخروج أو زوال الجلسة: لا قائمة ولا منبّهات */
    fun clear(c: Context) {
        cancelNext(c)
        cancelDaily(c)
        prefs(c).edit().remove("days").remove("tz").remove("hash").remove("last").remove("fired").remove("try_at").apply()
    }

    /**
     * الجلب في خيط خلفي. pr (من مستقبِل) يُنهى عند 9 ثوانٍ مهما حدث، كما في أداة الإدارة. done يُستدعى من الخيط الخلفي
     * بقيمة «في القائمة موعد قادم».
     */
    fun fetch(ctx: Context, force: Boolean, done: ((Boolean) -> Unit)?, pr: BroadcastReceiver.PendingResult? = null) {
        val c = ctx.applicationContext
        if (!enabled(c) || !busy.compareAndSet(false, true)) { pr?.finish(); return }
        val fin = AtomicBoolean(false)
        val end = Runnable { if (fin.compareAndSet(false, true)) try { pr?.finish() } catch (e: Exception) {} }
        if (pr != null) main.postDelayed(end, 9000L)
        Thread {
            try { load(c, force) } catch (e: Exception) {}
            finally {
                busy.set(false)
                main.post(end)
            }
            try { done?.invoke(hasUpcoming(c)) } catch (e: Exception) {}
        }.start()
    }

    private fun load(c: Context, force: Boolean) {
        val p = prefs(c)
        p.edit().putLong("try_at", System.currentTimeMillis()).apply()
        val tok = SecureStore(c).get(SecureStore.SESSION)
        if (tok == null || !NativeBridge.TOKEN_RE.matcher(tok).matches()) { clear(c); return }
        var code: Int
        var body: String? = null
        try {
            val h = URL(Cfg.RINGS_FEED + "?days=7").openConnection() as HttpURLConnection
            h.connectTimeout = 6000; h.readTimeout = 8000
            h.useCaches = false
            h.setRequestProperty("Authorization", "Bearer $tok")
            h.setRequestProperty("X-IBH-App", "1")
            h.setRequestProperty("Accept", "application/json")
            h.setRequestProperty("User-Agent", "IBHApp/" + Cfg.versionName(c) + " (Android " + Build.VERSION.RELEASE + "; rings)")
            code = h.responseCode
            val st = if (code < 400) h.inputStream else h.errorStream
            body = if (st != null) String(UiKit.readAll(st, 512 shl 10), Charsets.UTF_8) else null
            h.disconnect()
        } catch (e: Exception) { code = -1 }
        if (tok != SecureStore(c).get(SecureStore.SESSION)) return // تغيّر الحساب أثناء الطلب: يتولاه sessionChanged
        when {
            code == 200 && body != null -> {
                val d = try { val j = JSONObject(body); if (j.optBoolean("ok")) j.optJSONObject("data") else null } catch (e: Exception) { null }
                val days = d?.optJSONArray("days") ?: return
                val hash = d.optString("hash")
                if (!force && hash.isNotEmpty() && hash == p.getString("hash", null) && p.contains("days")) { scheduleNext(c); return }
                p.edit().putString("days", days.toString()).putString("tz", d.optString("tz")).putString("hash", hash).apply()
                scheduleNext(c)
            }
            code == 401 || code == 403 -> clear(c) // لا جلسة، أو حساب بلا دوام (ولي أمر…)
            else -> {} // لا اتصال، أو 404 قبل نشر الطريق، أو 5xx: تبقى القائمة المحفوظة ومنبّهها
        }
    }

    // ---------------------------------------------------------------- الحساب

    private fun tz(c: Context): TimeZone {
        val id = prefs(c).getString("tz", null) ?: ""
        return if (id.isNotEmpty()) TimeZone.getTimeZone(id) else TimeZone.getDefault()
    }

    private fun isEnd(kind: String) = kind == "lesson_end" || kind == "break_end"

    /** وقت التنبيه بساعة الهاتف (نهاية الحصة والاستراحة قبل دقيقة إن اختير ذلك) */
    private fun fireAt(c: Context, zone: TimeZone, date: String, at: String, kind: String, early: Boolean): Long? {
        val m = BellPlan.minuteOf(at) ?: return null
        val p = UiKit.splitOn(date, '-')
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val mo = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(y, mo - 1, d, m / 60, m % 60, 0)
        return cal.timeInMillis - (if (early && isEnd(kind)) 60_000L else 0L)
    }

    /** [وقت التنبيه، البند] مرتبة، من القائمة المحفوظة */
    private fun all(c: Context): List<Pair<Long, JSONObject>> {
        val out = ArrayList<Pair<Long, JSONObject>>()
        val s = prefs(c).getString("days", null) ?: return out
        val days = try { JSONArray(s) } catch (e: Exception) { return out }
        val zone = tz(c)
        val early = earlyEnd(c)
        for (i in 0 until days.length()) {
            val day = days.optJSONObject(i) ?: continue
            val date = day.optString("date")
            val items = day.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val it = items.optJSONObject(j) ?: continue
                if (it.optString("text").isBlank()) continue
                val t = fireAt(c, zone, date, it.optString("at"), it.optString("kind"), early) ?: continue
                it.put("_date", date)
                out.add(Pair(t, it))
            }
        }
        java.util.Collections.sort(out, object : java.util.Comparator<Pair<Long, JSONObject>> {
            override fun compare(a: Pair<Long, JSONObject>, b: Pair<Long, JSONObject>): Int = a.first.compareTo(b.first)
        })
        return out
    }

    /** التنبيه القادم بعد آخر ما نُبِّه به وبعد الآن */
    fun nextItem(c: Context): Pair<Long, JSONObject>? {
        val now = System.currentTimeMillis()
        val last = prefs(c).getLong("last", 0L)
        for (x in all(c)) if (x.first > now && x.first > last && !wasFired(c, x.second)) return x
        return null
    }

    // ---------------------------------------------------------------- المنبّهات

    private fun pi(c: Context, rc: Int, action: String): PendingIntent {
        val i = Intent(c, RingAlarmReceiver::class.java)
        i.action = action
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun cancelNext(c: Context) { try { am(c).cancel(pi(c, RC_NEXT, ACTION_FIRE)) } catch (e: Exception) {} }
    fun cancelDaily(c: Context) {
        try { am(c).cancel(pi(c, RC_DAILY, ACTION_DAILY)) } catch (e: Exception) {}
        prefs(c).edit().putBoolean("daily_set", false).apply()
    }

    /** منبّه التنبيه التالي وحده (أو إلغاؤه إن لا شيء قادم أو التنبيهات متوقفة) */
    fun scheduleNext(c: Context) {
        cancelNext(c)
        if (!enabled(c)) return
        val nx = nextItem(c) ?: return
        val op = pi(c, RC_NEXT, ACTION_FIRE)
        val a = am(c)
        try {
            if (Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()) {
                val show = PendingIntent.getActivity(c, RC_SHOW, Intent(c, AlertsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                a.setAlarmClock(AlarmManager.AlarmClockInfo(nx.first, show), op)
            } else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nx.first, op)
        } catch (e: Exception) {
            try { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nx.first, op) } catch (x: Exception) {}
        }
    }

    /** جلب يومي بمنبّه غير دقيق (لا يوقظ الهاتف خصيصاً له؛ يقع مع أول استيقاظ بعد موعده) */
    fun scheduleDaily(c: Context) {
        if (!enabled(c)) return
        if (prefs(c).getBoolean("daily_set", false) && prefs(c).getLong("daily_at", 0L) > System.currentTimeMillis() - 2 * AlarmManager.INTERVAL_DAY) return
        try {
            am(c).setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY, AlarmManager.INTERVAL_DAY, pi(c, RC_DAILY, ACTION_DAILY))
            prefs(c).edit().putBoolean("daily_set", true).putLong("daily_at", System.currentTimeMillis()).apply()
        } catch (e: Exception) {}
    }

    /** بعد إعادة التشغيل أو التحديث أو تغيير الوقت: المنبّهات كلها من جديد */
    fun resetAlarms(c: Context) {
        // رُجعت الساعة إلى الوراء: «آخر ما نُبِّه به» لا يبقى في المستقبل فيحجب ما بعده
        val now = System.currentTimeMillis()
        if (prefs(c).getLong("last", 0L) > now) prefs(c).edit().putLong("last", now).apply()
        prefs(c).edit().putBoolean("daily_set", false).apply()
        scheduleDaily(c)
        scheduleNext(c)
    }

    /** عند وقت التنبيه: إشعار لما حلّ (ما فات بأكثر من 10 دقائق يُتجاوز بصمت)، ثم منبّه التالي. يعيد مدة النغمة (مللي) */
    fun fire(c: Context): Long {
        if (!enabled(c)) return 0L
        val now = System.currentTimeMillis()
        val p = prefs(c)
        var last = p.getLong("last", 0L)
        var shown: JSONObject? = null
        var shownAt = 0L
        val keys = ArrayList<String>()
        for (x in all(c)) {
            if (x.first <= last) continue
            if (x.first > now + 30_000L) break
            if (wasFired(c, x.second)) { last = x.first; continue }
            if (x.first >= now - LATE_MS) { shown = x.second; shownAt = x.first }
            keys.add(itemKey(x.second))
            last = x.first
        }
        p.edit().putLong("last", last).apply()
        markFired(c, keys)
        val ms = if (shown != null) notify(c, shown, shownAt) else 0L
        scheduleNext(c)
        // القائمة توشك أن تنفد (لا قادم): جلب جديد
        if (nextItem(c) == null) fetch(c, false, null)
        return ms
    }

    // ---------------------------------------------------------------- الإشعار

    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_SOUND) == null) {
            val ch = NotificationChannel(CH_SOUND, c.getString(R.string.al_channel), NotificationManager.IMPORTANCE_HIGH)
            ch.description = c.getString(R.string.al_channel_desc)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_APP) == null) {
            val ch = NotificationChannel(CH_APP, c.getString(R.string.al_channel_app), NotificationManager.IMPORTANCE_HIGH)
            ch.description = c.getString(R.string.al_channel_desc)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CH_VIBE) == null) {
            val ch = NotificationChannel(CH_VIBE, c.getString(R.string.al_channel_vibe), NotificationManager.IMPORTANCE_HIGH)
            ch.description = c.getString(R.string.al_channel_desc)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    /** «10:30 ص» بأرقام عربية (0–9) */
    fun hm12(at: String): String {
        val m = BellPlan.minuteOf(at) ?: return at
        val h = m / 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return h12.toString() + ":" + String.format(Locale.ROOT, "%02d", m % 60) + (if (h < 12) " ص" else " م")
    }

    /**
     * نغمة التنبيه: «نغمة إشعارات الهاتف» يشغّلها النظام من القناة duty_sound كما في 1.2.0. وأي نغمة أخرى (مدمجة أو من
     * الجوال) يشغّلها التطبيق بنفسه مع إشعار على القناة الصامتة duty_app، بدل قناة جديدة لكل نغمة: صوت القناة لا يتغيّر بعد
     * إنشائها، والقنوات المحذوفة تبقى معدودة في إعدادات الهاتف وتضيع معها تخصيصات المستخدم، والنظام لا يقرأ ملفاً اختير
     * بمنتقي الملفات إلا بمنحه إذناً خاصاً — فتغيير النغمة هنا فوري ومضمون على كل الإصدارات.
     * والتطبيق يحترم الصامت و«عدم الإزعاج» بنفسه: لا يشغّل إلا والرنين عادي ولا مقاطعة مفعّلة، وعلى مسار الإشعارات.
     */
    private fun selfSoundAllowed(c: Context): Boolean {
        return try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return false
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = nm.getNotificationChannel(CH_APP)
                if (ch != null && ch.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
                // المستخدم أضاف صوتاً لهذه القناة من إعدادات الهاتف: يكفي صوت النظام، فلا يُشغَّل صوتان
                if (ch != null && ch.sound != null) return false
            }
            true
        } catch (e: Exception) { true }
    }

    @Suppress("DEPRECATION")
    private fun notify(c: Context, it: JSONObject, at: Long): Long {
        if (!notificationsAllowed(c)) return 0L
        ensureChannels(c)
        val vibe = vibrateOnly(c)
        val sysTone = BellTone.choice(c, BellTone.ALERT).startsWith("s:")
        val chId = if (vibe) CH_VIBE else if (sysTone) CH_SOUND else CH_APP
        val unit = it.optString("unit").let { u -> if (u == "null") "" else u.trim() }
        val time = hm12(it.optString("at"))
        val body = if (unit.isNotEmpty()) "$unit · $time" else time
        val open = Intent(c, MainActivity::class.java)
        open.action = MainActivity.ACTION_OPEN
        open.putExtra(MainActivity.EXTRA_ROUTE, "#day")
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(c, RC_OPEN, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, chId) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_ibh)
            .setContentTitle(it.optString("text").trim())
            .setContentText(body)
            .setColor(Cfg.GREEN_800)
            .setWhen(at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        // على شاشة القفل: «تنبيه دوام» والوقت فقط، بلا اسم الفرع ولا نص الحصة
        val pub = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, chId) else Notification.Builder(c)
        pub.setSmallIcon(R.drawable.ic_stat_ibh).setContentTitle(c.getString(R.string.al_public_title)).setContentText(time).setColor(Cfg.GREEN_800)
        b.setPublicVersion(pub.build())
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(30 * 60_000L)
        else {
            b.setPriority(Notification.PRIORITY_HIGH)
            b.setVibrate(longArrayOf(0, 250, 150, 250))
            if (!vibe && sysTone) b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, b.build()) } catch (e: Exception) { return 0L }
        if (vibe || sysTone || !selfSoundAllowed(c)) return 0L
        return BellTone.play(c, BellTone.ALERT, 1).ms
    }
}

/** منبّه التنبيه التالي والجلب اليومي: غير مُصدَّر */
class RingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            RingSched.ACTION_FIRE -> {
                // النغمة يشغّلها التطبيق: تبقى العملية حية حتى تنتهي (والشاشة مقفلة) بقفل معالج قصير
                val pr = goAsync()
                val wl = try {
                    val w = (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ibh:duty-alert")
                    w.acquire(12_000L); w
                } catch (e: Exception) { null }
                var ms = 0L
                try { ms = RingSched.fire(c) } catch (e: Exception) {}
                val wait = if (ms > 8000L) 8500L else ms + 500L
                Handler(Looper.getMainLooper()).postDelayed({
                    try { wl?.release() } catch (e: Exception) {}
                    try { pr.finish() } catch (e: Exception) {}
                }, wait)
            }
            RingSched.ACTION_DAILY -> RingSched.fetch(c, false, null, goAsync())
        }
    }
}

/**
 * أحداث النظام: إعادة تشغيل الهاتف، وتحديث التطبيق، وتغيير الوقت أو المنطقة. منبّهات AlarmManager تضيع بإعادة التشغيل،
 * فتُعاد هنا منبّهات تنبيهات الدوام ومنبّه هاتف الجرس (إن كان الجرس مشغَّلاً). مُصدَّر لأن النظام يرسلها، ويتحقق من الحدث.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != Intent.ACTION_TIME_CHANGED && a != Intent.ACTION_TIMEZONE_CHANGED) return
        try { BellPlan.schedule(c) } catch (e: Exception) {}
        try { RingSched.resetAlarms(c) } catch (e: Exception) {}
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { RingSched.fetch(c, false, null, goAsync()) } catch (e: Exception) {}
        }
    }
}
