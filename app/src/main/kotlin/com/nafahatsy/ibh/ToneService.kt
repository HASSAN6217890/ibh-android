package com.nafahatsy.ibh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * خدمة أمامية قصيرة تشغّل النغمة من المنبّه والتطبيق في الخلفية أو مغلق والشاشة مقفلة (التطبيق 1.3.1).
 *
 * لماذا: كان المستقبِل يشغّل النغمة بنفسه بعد goAsync، ثم تصير العملية «مخزّنة» بلا مكوّن نشط فيجمّدها النظام (مجمِّد
 * التطبيقات المخزّنة في أندرويد 14+، وMagicOS أشد) أو يكتم صوتها، فيظهر الإشعار بلا صوت. الخدمة الأمامية تُبقي العملية
 * نشطة طوال النغمة ثم تتوقف فوراً.
 *
 * - النوع shortService (أندرويد 14+): بلا إذن نوع خاص (يكفي FOREGROUND_SERVICE)، ونتوقف بعد النغمة وبمهلة قصوى 60 ثانية،
 *   وonTimeout يوقفها. البدء من الخلفية مسموح لأن المستقبِل يعمل بمنبّه setAlarmClock (أو AllowWhileIdle) يضع التطبيق في
 *   القائمة المؤقتة المستثناة؛ وإن رفض النظام فالمستقبِل يشغّل النغمة بنفسه كما في 1.3.0 ويسجّل fgs_fail.
 * - إشعار الخدمة واحد دائماً (ID_TONE، قناة هادئة منخفضة)، مستقل عن إشعار تنبيه الدوام الذي ينشره RingSched إشعاراً عادياً
 *   (فيبقى قابلاً للضغط والإزالة وينتهي بعد 30 دقيقة). لا تبديل لمعرّف إشعار الخدمة بين الطلبات، وعند التوقف يُزال ويُلغى صراحة،
 *   فلا يعلق «يرنّ الجرس الآن» مهما تزامن تنبيه دوام ورنّة جرس.
 * - رنّات الجرس التي لم تُشغَّل قبل توقف مبكر (مهلة) يُكتب لها تقرير ok:false فلا تضيع.
 *
 * خطة الفحص اليدوي (هاتف أندرويد 14+، ويفضَّل Honor/MagicOS):
 *  1) نغمة مدمجة للتنبيهات، أغلق التطبيق واقفل الشاشة: عند التنبيه إشعار + نغمة، ثم يختفي «تنبيه الدوام…» الهادئ ويبقى التنبيه.
 *  2) هاتف جرس بحساب موظف: تنبيه دوام ورنّة جرس في الدقيقة نفسها والشاشة مطفأة: يرنّ الاثنان، ولا يبقى إشعار «يرنّ الجرس الآن».
 *  3) وضع صامت/عدم إزعاج: التنبيه بلا نغمة (اهتزاز القناة فقط)؛ الجرس يرنّ (مسار المنبّه).
 *  4) سجل الرنّات على الخادم: كل رنّة ok:true، أو ok:false إن أوقفت المهلة ما لم يُشغَّل.
 */
class ToneService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var until = 0L
    private var foreground = false
    private var bellShown = false
    /** رنّات الجرس المجدولة ولم تُشغَّل بعد: [التاريخ، الوقت] */
    private val pendingBell = ArrayList<Array<String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: KIND_ALERT
        val bell = kind == KIND_BELL
        // startForeground لكل طلب (شرط startForegroundService)، دائماً بالمعرّف نفسه؛ يتبدّل نصه إلى «يرنّ الجرس» إن انضم جرس
        val wasFg = foreground
        if (bell) bellShown = true
        try {
            val n = quietNotification(this, bellShown)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_TONE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(ID_TONE, n)
            foreground = true
        } catch (e: Exception) {
            RingSched.prefs(this).edit().putLong("fgs_fail", System.currentTimeMillis()).apply()
            if (!wasFg) {
                // لم تصر خدمة أمامية: النغمة مباشرة كما في 1.3.0، والخدمة تتوقف فوراً (لا تبقى خدمة غير أمامية بعد المهلة)
                playDirect(intent, bell)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val ms = try { if (bell) playBell(intent) else BellTone.play(this, BellTone.ALERT, 1).ms } catch (e: Exception) { 0L }
        val end = System.currentTimeMillis() + ms + 600L
        if (end > until) until = end
        hold(until - System.currentTimeMillis() + 1500L)
        h.removeCallbacks(finish)
        h.postDelayed(finish, until - System.currentTimeMillis())
        if (!wasFg) h.postDelayed(hardStop, HARD_MS)
        return START_NOT_STICKY
    }

    /** بلا خدمة أمامية: كما في 1.3.0، على معالج مستقل (لا يلغيه onDestroy) */
    private fun playDirect(i: Intent?, bell: Boolean) {
        if (!bell) { BellTone.play(this, BellTone.ALERT, 1); return }
        val day = i?.getStringExtra(EXTRA_DATE) ?: return
        val list = i.getStringArrayExtra(EXTRA_RINGS) ?: return
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        val gap = BellTone.durationMs(app, BellTone.BELL, 2) + 400L
        for (k in list.indices) {
            val hm = list[k]
            main.postDelayed({ val pl = BellTone.play(app, BellTone.BELL, 2); BellReport.queue(app, day, hm, true, pl.fellBack) }, k * gap)
        }
    }

    /** رنّات الجرس المتزامنة تتوالى بقدر مدة النغمة (وبعد ما سبقها في الخدمة)، ولكل رنّة تقريرها */
    private fun playBell(i: Intent?): Long {
        val day = i?.getStringExtra(EXTRA_DATE) ?: return 0L
        val list = i.getStringArrayExtra(EXTRA_RINGS) ?: return 0L
        if (list.isEmpty()) return 0L
        val one = BellTone.durationMs(this, BellTone.BELL, 2)
        val gap = one + 400L
        // إن كانت رنّة سابقة ما زالت تعمل في الخدمة: تبدأ هذه بعدها
        val now = System.currentTimeMillis()
        val start0 = if (pendingBell.isNotEmpty() && until > now) until - now else 0L
        for (k in list.indices) {
            val item = arrayOf(day, list[k])
            pendingBell.add(item)
            h.postDelayed({
                if (pendingBell.remove(item)) {
                    val pl = BellTone.play(this, BellTone.BELL, 2)
                    BellReport.queue(this, item[0], item[1], true, pl.fellBack)
                }
            }, start0 + k * gap)
        }
        return start0 + (list.size - 1) * gap + one
    }

    private fun hold(ms: Long) {
        try {
            if (wl == null) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibh:tone")
                wl!!.setReferenceCounted(false)
            }
            wl!!.acquire(if (ms > HARD_MS) HARD_MS else if (ms < 1000L) 1000L else ms)
        } catch (e: Exception) {}
    }

    private val finish: Runnable = object : Runnable {
        override fun run() {
            val left = until - System.currentTimeMillis()
            if (left > 50) h.postDelayed(this, left) else done()
        }
    }

    private val hardStop = Runnable { done() }

    @Suppress("DEPRECATION")
    private fun done() {
        h.removeCallbacksAndMessages(null)
        // رنّات عُلّمت «رنّت» في المستقبِل ولم تُشغَّل (مهلة أو توقف مبكر): تقرير «لم يرنّ»
        for (it in pendingBell) BellReport.queue(this, it[0], it[1], false)
        pendingBell.clear()
        try { if (foreground) stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        foreground = false
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_TONE) } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl!!.release() } catch (e: Exception) {}
        stopSelf()
    }

    // أندرويد 14 و15: انتهت مدة shortService — يجب التوقف فوراً
    override fun onTimeout(startId: Int) { done() }
    override fun onTimeout(startId: Int, fgsType: Int) { done() }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        for (it in pendingBell) BellReport.queue(this, it[0], it[1], false)
        pendingBell.clear()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_TONE) } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl!!.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val KIND_ALERT = "alert"
        const val KIND_BELL = "bell"
        const val EXTRA_KIND = "kind"
        const val EXTRA_DATE = "date"
        const val EXTRA_RINGS = "rings"
        private const val ID_TONE = 7101
        private const val CH_TONE = "bell_ring"
        private const val HARD_MS = 60_000L

        @Suppress("DEPRECATION")
        private fun quietNotification(c: Context, bell: Boolean): Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CH_TONE) == null) {
                    val ch = NotificationChannel(CH_TONE, c.getString(R.string.tone_svc_channel), NotificationManager.IMPORTANCE_LOW)
                    ch.setSound(null, null)
                    ch.enableVibration(false)
                    nm.createNotificationChannel(ch)
                }
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_TONE) else Notification.Builder(c).setPriority(Notification.PRIORITY_LOW)
            return b
                .setSmallIcon(R.drawable.ic_stat_ibh)
                .setContentTitle(c.getString(if (bell) R.string.tone_svc_ringing else R.string.tone_svc_alert))
                .setColor(Cfg.GREEN_800)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** يبدأ الخدمة؛ false إن رفض النظام (المستدعي يشغّل النغمة داخل المستقبِل) */
        fun start(c: Context, i: Intent): Boolean = try {
            i.setClass(c, ToneService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            true
        } catch (e: Exception) {
            RingSched.prefs(c).edit().putLong("fgs_fail", System.currentTimeMillis()).apply()
            false
        }
    }
}
