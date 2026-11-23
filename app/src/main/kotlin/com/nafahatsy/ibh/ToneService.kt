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
 * لماذا: كان المستقبِل (BroadcastReceiver) يشغّل النغمة بنفسه بعد goAsync. وبعد إنهاء البث تصير العملية «مخزّنة» بلا مكوّن
 * نشط، فيجمّدها النظام (مجمِّد التطبيقات المخزّنة في أندرويد 14+، وMagicOS أشد) أو يكتم صوت التطبيقات الخلفية، فيظهر الإشعار
 * بلا صوت. الخدمة الأمامية تُبقي العملية نشطة ومرئية للنظام طوال النغمة ثم تتوقف فوراً.
 *
 * - النوع shortService (أندرويد 14+): لا يحتاج إذناً خاصاً بنوعه (يكفي FOREGROUND_SERVICE العادي)، ومدته حتى 3 دقائق،
 *   ونحن نتوقف بعد النغمة (ثوانٍ) وبمهلة قصوى 60 ثانية؛ وonTimeout يوقفها إن تجاوزت.
 * - البدء من الخلفية مسموح هنا لأن المستقبِل يعمل بمنبّه دقيق (setAlarmClock / AllowWhileIdle) يضع التطبيق في القائمة
 *   المؤقتة المستثناة من قيود بدء الخدمات الأمامية. وإن رفض النظام البدء: يعود المستقبِل للتشغيل القديم ويسجّل ذلك (fgs_fail).
 * - تنبيه الدوام: إشعار التنبيه نفسه هو إشعار الخدمة (يظهر فوراً)، ويبقى بعد توقفها كإشعار عادي (STOP_FOREGROUND_DETACH).
 * - الجرس: إشعار هادئ «يرنّ الجرس» على قناة منخفضة يُزال بعد الرنين، ويُكتب تقرير كل رنّة كما كان.
 */
class ToneService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var until = 0L
    private var detach = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: KIND_ALERT
        val n = (if (kind == KIND_ALERT) alertNotification(intent) else null) ?: quietNotification(this)
        // startForeground أولاً، قبل أي عمل (المهلة 5 ثوانٍ وإلا انهارت العملية)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(if (kind == KIND_ALERT) RingSched.NOTIF_ID else ID_BELL, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(if (kind == KIND_ALERT) RingSched.NOTIF_ID else ID_BELL, n)
        } catch (e: Exception) {
            // لم تصر خدمة أمامية (نادر): تُشغَّل النغمة كما في 1.3.0 ويُسجَّل ذلك
            RingSched.prefs(this).edit().putLong("fgs_fail", System.currentTimeMillis()).apply()
            if (kind == KIND_ALERT) {
                try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RingSched.NOTIF_ID, n) } catch (x: Exception) {}
            }
        }
        if (kind == KIND_ALERT) detach = true
        val ms = try { if (kind == KIND_ALERT) playAlert() else playBell(intent) } catch (e: Exception) { 0L }
        val end = System.currentTimeMillis() + ms + 600L
        if (end > until) until = end
        hold(ms + 1500L)
        h.removeCallbacks(finish)
        h.postDelayed(finish, until - System.currentTimeMillis())
        h.removeCallbacks(hardStop)
        h.postDelayed(hardStop, HARD_MS)
        return START_NOT_STICKY
    }

    private fun alertNotification(i: Intent?): Notification? {
        if (i == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_NOTIF, Notification::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(EXTRA_NOTIF)
        } catch (e: Exception) { null }
    }

    private fun playAlert(): Long = BellTone.play(this, BellTone.ALERT, 1).ms

    /** رنّات الجرس المتزامنة تتوالى بقدر مدة النغمة، ولكل رنّة تقريرها */
    private fun playBell(i: Intent?): Long {
        val day = i?.getStringExtra(EXTRA_DATE) ?: return 0L
        val list = i.getStringArrayExtra(EXTRA_RINGS) ?: return 0L
        val gap = BellTone.durationMs(this, BellTone.BELL, 2) + 400L
        for (k in list.indices) {
            val hm = list[k]
            h.postDelayed({
                val pl = BellTone.play(this, BellTone.BELL, 2)
                BellReport.queue(this, day, hm, true, pl.fellBack)
            }, k * gap)
        }
        return (list.size - 1) * gap + BellTone.durationMs(this, BellTone.BELL, 2)
    }

    private fun hold(ms: Long) {
        try {
            if (wl == null) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibh:tone")
                wl!!.setReferenceCounted(false)
            }
            wl!!.acquire(if (ms > HARD_MS) HARD_MS else ms)
        } catch (e: Exception) {}
    }

    private val finish = Runnable {
        if (System.currentTimeMillis() + 50 < until) { reschedule(); return@Runnable }
        done()
    }

    private fun reschedule() { h.postDelayed(finish, until - System.currentTimeMillis()) }

    private val hardStop = Runnable { done() }

    @Suppress("DEPRECATION")
    private fun done() {
        h.removeCallbacksAndMessages(null)
        try {
            // إشعار التنبيه يبقى بعد الخدمة كإشعار عادي؛ وإشعار الجرس الهادئ يُزال
            if (Build.VERSION.SDK_INT >= 24) stopForeground(if (detach) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            else stopForeground(!detach)
        } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl!!.release() } catch (e: Exception) {}
        stopSelf()
    }

    // أندرويد 14: انتهت مدة shortService — يجب التوقف فوراً
    override fun onTimeout(startId: Int) { done() }
    override fun onTimeout(startId: Int, fgsType: Int) { done() }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { if (wl?.isHeld == true) wl!!.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val KIND_ALERT = "alert"
        const val KIND_BELL = "bell"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NOTIF = "notif"
        const val EXTRA_DATE = "date"
        const val EXTRA_RINGS = "rings"
        private const val ID_BELL = 7101
        private const val CH_BELL = "bell_ring"
        private const val HARD_MS = 60_000L

        @Suppress("DEPRECATION")
        fun quietNotification(c: Context): Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CH_BELL) == null) {
                    val ch = NotificationChannel(CH_BELL, c.getString(R.string.tone_svc_channel), NotificationManager.IMPORTANCE_LOW)
                    ch.setSound(null, null)
                    ch.enableVibration(false)
                    nm.createNotificationChannel(ch)
                }
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_BELL) else Notification.Builder(c)
            return b.setSmallIcon(R.drawable.ic_stat_ibh)
                .setContentTitle(c.getString(R.string.tone_svc_ringing))
                .setColor(Cfg.GREEN_800)
                .setOngoing(true)
                .build()
        }

        /** يبدأ الخدمة؛ false إن رفض النظام (المستدعي يعود للتشغيل داخل المستقبِل) */
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
