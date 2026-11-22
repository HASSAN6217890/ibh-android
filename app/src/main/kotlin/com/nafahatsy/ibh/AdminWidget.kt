package com.nafahatsy.ibh

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * أداة الشاشة الرئيسية للإدارة (التطبيق 1.1.0): ملخص اليوم من GET /api/widget/admin بجلسة التطبيق نفسها
 * (Authorization: Bearer <رمز الجلسة من SecureStore> + X-IBH-App: 1، كما يرسل call0 في app.js).
 * - العدّادات والتذكيرات تُرسم كما يرسلها الخادم (الأسماء منه)، والضغط على أيٍّ منها يفتح التطبيق على مساره.
 * - الضغط على الرأس يحدّث. والتحديث الدوري كل 30 دقيقة (updatePeriodMillis).
 * - الشبكة خارج الخيط الرئيسي: goAsync وخيط خلفي بمهلة (لماذا لا JobService: انظر WidgetData.refresh).
 * - آخر رد سليم يُحفظ في تفضيلات خاصة بالتطبيق؛ والخروج أو 401 أو 403 يمسحه فلا تبقى أرقام بعد زوال الصلاحية.
 * - الرمز لا يمر في أي Intent ولا يُكتب في سجل.
 */
class AdminWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        // الأداة مُصدَّرة (يرسل لها النظام)، فقد يرسل لها تطبيق آخر APPWIDGET_UPDATE: لا طلب شبكة أكثر من مرة في الدقيقة
        if (System.currentTimeMillis() - WidgetData.prefs(c).getLong("try_at", 0L) < 60_000L) { WidgetData.renderAll(c); return }
        WidgetData.refresh(c, goAsync())
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle?) {
        // تغيّر الحجم: يُعاد الرسم من المحفوظ بلا شبكة (الحجم الأكبر يُظهر التذكيرات)
        WidgetData.render(c, m, id)
    }

    override fun onDisabled(c: Context) {
        // أُزيلت آخر نسخة من الأداة: لا يبقى ملخص محفوظ بلا حاجة
        WidgetData.prefs(c).edit().clear().apply()
    }
}

/** ضغطة الرأس «حدّث الآن»: غير مُصدَّر، فلا يطلقه إلا PendingIntent من التطبيق نفسه */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (WidgetData.busy.get()) return
        WidgetData.showLoading(c)
        WidgetData.refresh(c, goAsync())
    }
}

object WidgetData {
    const val S_OK = "ok"
    const val S_LOGIN = "login"
    const val S_FINISH = "finish"
    const val S_ADMIN = "admin"
    const val S_STALE = "stale"

    val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("ibh_widget", Context.MODE_PRIVATE)

    private fun ids(c: Context): IntArray = try {
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, AdminWidget::class.java))
    } catch (e: Exception) { IntArray(0) }

    fun hasWidgets(c: Context) = ids(c).isNotEmpty()

    // ---------------------------------------------------------------- التحديث

    /**
     * goAsync مع خيط خلفي بدل JobService: الرد صغير (أقل من 2 ك.ب) وطلب واحد، والمطلوب أن تظهر نتيجة ضغطة «حدّث» فوراً؛
     * وJobScheduler قد يؤجل المهمة دقائق (وضع السكون وفئات الاستعداد) ويحتاج خدمة مسجّلة. حدّ goAsync نحو 10 ثوانٍ،
     * فمهلة الاتصال والقراءة قصيرة، ومؤقت حارس يُنهي البث عند 9 ثوانٍ مهما حدث؛ وإن تأخر الرد بعده يُكمل الخيط ويحدّث الأداة
     * ما دامت العملية حية، وإلا تبقى «غير محدَّث» بالمحفوظ حتى الضغطة التالية أو الدورة التالية.
     */
    fun refresh(ctx: Context, pr: BroadcastReceiver.PendingResult?) {
        val c = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) { pr?.finish(); return }
        val done = AtomicBoolean(false)
        val fin = Runnable { if (done.compareAndSet(false, true)) try { pr?.finish() } catch (e: Exception) {} }
        main.postDelayed(fin, 9000L)
        Thread {
            var again = false
            try { again = fetch(c) } catch (e: Exception) { mark(c, S_STALE) }
            finally {
                busy.set(false)
                loading = false
                try { renderAll(c) } catch (e: Exception) {}
                main.post(fin)
            }
            // تغيّر الحساب أثناء الطلب: طلب واحد جديد بالرمز الحالي (والأداة تعرض «جارٍ التحديث…» حتى يصل)
            if (again) refresh(c, null)
        }.start()
    }

    /** بعد العمل في التطبيق (تثبيت حصة، إغلاق مهمة…): تحديث هادئ إن كانت الأداة مضافة ولم تُحدَّث منذ دقيقة */
    fun poke(c: Context) {
        if (!hasWidgets(c)) return
        if (System.currentTimeMillis() - prefs(c).getLong("try_at", 0L) < 60_000L) return
        refresh(c, null)
    }

    /** دخول جديد (تغيّر رمز الجلسة): يُمسح ملخص الحساب السابق ويُطلب ملخص الحساب الجديد */
    fun sessionChanged(c: Context) {
        prefs(c).edit().remove("state").remove("data").remove("ok_at").putLong("try_at", 0L).apply()
        if (hasWidgets(c)) renderAll(c)
        if (hasWidgets(c)) refresh(c, null)
    }

    /** الخروج أو انتهاء الجلسة: لا أرقام بعده */
    fun loggedOut(c: Context) {
        prefs(c).edit().remove("data").remove("ok_at").putString("state", S_LOGIN).apply()
        if (hasWidgets(c)) renderAll(c)
    }

    private fun mark(c: Context, state: String, clear: Boolean = false) {
        val e = prefs(c).edit().putString("state", state)
        if (clear) e.remove("data").remove("ok_at")
        e.apply()
    }

    /** يعيد true إن تغيّر رمز الجلسة أثناء الطلب فيجب طلب جديد */
    private fun fetch(c: Context): Boolean {
        prefs(c).edit().putLong("try_at", System.currentTimeMillis()).apply()
        val tok = SecureStore(c).get(SecureStore.SESSION)
        if (tok == null || !NativeBridge.TOKEN_RE.matcher(tok).matches()) { mark(c, S_LOGIN, true); return false }
        var code: Int
        var body: String? = null
        try {
            val h = URL(Cfg.WIDGET_FEED).openConnection() as HttpURLConnection
            h.connectTimeout = 5000; h.readTimeout = 6000
            h.useCaches = false
            h.setRequestProperty("Authorization", "Bearer $tok")
            h.setRequestProperty("X-IBH-App", "1")
            // طلب الأداة لا يمدّد الجلسة ولا يغيّر «آخر نشاط» على الخادم (الدفعة 30)
            h.setRequestProperty("X-IBH-Widget", "1")
            h.setRequestProperty("Accept", "application/json")
            h.setRequestProperty("User-Agent", "IBHApp/" + Cfg.versionName(c) + " (Android " + Build.VERSION.RELEASE + "; widget)")
            code = h.responseCode
            val st = if (code < 400) h.inputStream else h.errorStream
            body = if (st != null) String(UiKit.readAll(st, 256 shl 10), Charsets.UTF_8) else null
            h.disconnect()
        } catch (e: Exception) { code = -1 }
        // تغيّر الحساب أثناء الطلب (خروج أو دخول آخر): لا يُكتب ملخص الحساب السابق
        if (tok != SecureStore(c).get(SecureStore.SESSION)) {
            prefs(c).edit().remove("state").remove("data").remove("ok_at").apply()
            return true
        }

        when {
            code == 200 && body != null -> {
                val d = try { val j = JSONObject(body); if (j.optBoolean("ok")) j.optJSONObject("data") else null } catch (e: Exception) { null }
                if (d != null && d.optJSONArray("items") != null) {
                    prefs(c).edit().putString("data", d.toString()).putLong("ok_at", System.currentTimeMillis()).putString("state", S_OK).apply()
                } else mark(c, S_STALE)
            }
            code == 401 -> mark(c, S_LOGIN, true)
            code == 403 -> {
                // 403 لأسباب الحساب نفسه (تغيير الكلمة، التحقق بخطوتين، جلسة الاستعادة) غير «ليس من الإدارة»
                val err = try { JSONObject(body ?: "").optString("error") } catch (e: Exception) { "" }
                mark(c, if (err == "must_change" || err == "twofa_overdue" || err == "recovery_session") S_FINISH else S_ADMIN, true)
            }
            else -> mark(c, S_STALE) // لا اتصال، أو 404 قبل نشر الطريق، أو 5xx: يبقى المحفوظ ويظهر «غير محدَّث»
        }
        return false
    }

    // ---------------------------------------------------------------- الرسم

    fun renderAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for (id in ids(c)) try { render(c, m, id) } catch (e: Exception) {}
    }

    /** ضغطة ↻: «جارٍ التحديث…» في الرأس حتى يصل الرد (رسم كامل، لأن أندرويد 12+ يرسم تخطيطاً لكل حجم) */
    @Volatile private var loading = false

    fun showLoading(c: Context) {
        loading = true
        renderAll(c)
    }

    private fun openIntent(c: Context, req: Int, route: String?): PendingIntent {
        val i = Intent(c, MainActivity::class.java)
        i.action = MainActivity.ACTION_OPEN
        if (route != null && Cfg.routeOk(route)) i.putExtra(MainActivity.EXTRA_ROUTE, route)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun refreshIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 90, Intent(c, WidgetRefreshReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun clip(s: String, n: Int): String { val t = s.trim(); return if (t.length > n) t.substring(0, n - 1) + "…" else t }

    private fun num(n: Int): String = if (n > 99) "99+" else n.toString()

    private const val RED = 0xFFB23A22.toInt()
    private const val AMBER = 0xFF9A5F06.toInt()

    /** شدة العدّاد: من الخادم إن أرسل level، وإلا بالمفتاح (الأجراس الفائتة والمهام المتأخرة أحمر، وما ينتظر اليوم كهرماني) */
    private fun level(it: JSONObject): Int {
        val l = it.optString("level")
        if (l == "red") return 2
        if (l == "amber") return 1
        val k = it.optString("key")
        return if (k == "bell" || k == "tasks") 2 else 1
    }

    private fun levelColor(lv: Int) = if (lv == 2) RED else AMBER

    fun render(c: Context, m: AppWidgetManager, id: Int) {
        val o = try { m.getAppWidgetOptions(id) } catch (e: Exception) { null }
        if (Build.VERSION.SDK_INT >= 31) {
            // أندرويد 12+: تخطيط لكل حجم، والمشغّل يختار ما يناسب عند تغيير الحجم بلا طلب جديد
            val map = HashMap<android.util.SizeF, RemoteViews>()
            for (s in SIZES) map[android.util.SizeF(s[0].toFloat(), s[1].toFloat())] = build(c, s[0], s[1])
            m.updateAppWidget(id, RemoteViews(map))
            return
        }
        var w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        var h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (w <= 0) w = 250
        if (h <= 0) h = 110
        m.updateAppWidget(id, build(c, w, h))
    }

    /** الأحجام المرسومة لأندرويد 12+ (عرض×ارتفاع بالـ dp): 2×2 قصير وعادي وطويل، و4×2 و4×3 و4×4 */
    private val SIZES = arrayOf(intArrayOf(110, 100), intArrayOf(110, 170), intArrayOf(110, 250),
        intArrayOf(250, 100), intArrayOf(250, 180), intArrayOf(250, 280))

    private fun build(c: Context, w: Int, h: Int): RemoteViews {
        val p = prefs(c)
        // بلا حالة بعد (أُضيفت الأداة للتو والطلب الأول جارٍ): «جارٍ التحديث…»
        val state = p.getString("state", null) ?: ""
        val data = try { p.getString("data", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
        val narrow = w < 200

        val rv = RemoteViews(c.packageName, R.layout.widget_admin)
        rv.setOnClickPendingIntent(R.id.w_head, openIntent(c, 98, "#day"))
        rv.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(c))
        rv.removeAllViews(R.id.w_grid)
        rv.removeAllViews(R.id.w_rem)
        for (v in intArrayOf(R.id.w_grid, R.id.w_rem, R.id.w_msgrow, R.id.w_hero, R.id.w_badge, R.id.w_time)) rv.setViewVisibility(v, View.GONE)

        val scope = data?.optString("scope")?.takeIf { it.isNotBlank() && it != "null" }
            ?: data?.optString("day_label")?.takeIf { it.isNotBlank() && it != "null" }
            ?: c.getString(R.string.w_title)
        rv.setTextViewText(R.id.w_scope, clip(scope, 40))
        val asOf = data?.optString("as_of")?.takeIf { it.length in 4..5 } ?: ""

        val blocker = when (state) {
            "" -> R.string.w_loading
            S_LOGIN -> R.string.w_login
            S_FINISH -> R.string.w_finish
            S_ADMIN -> R.string.w_admin_only
            else -> if (data == null) (if (state == S_STALE) R.string.w_stale_empty else R.string.w_login) else 0
        }
        if (blocker != 0) {
            // سطر واحد مع الشعار، لا بطاقة فارغة كبيرة
            rv.setTextViewText(R.id.w_scope, c.getString(R.string.w_title))
            if (loading) time(rv, c.getString(R.string.w_loading), Cfg.MUTED)
            else if (state == S_STALE) time(rv, c.getString(R.string.w_stale), RED)
            msg(rv, c.getString(blocker), Cfg.GREEN_800, true,
                if (state == S_STALE) refreshIntent(c) else openIntent(c, 99, null))
            return rv
        }

        val d = data!!
        if (loading) time(rv, c.getString(R.string.w_loading), Cfg.MUTED)
        else if (state == S_STALE) time(rv, c.getString(R.string.w_stale) + (if (asOf.isNotEmpty() && !narrow) " · $asOf" else ""), RED)
        else if (!narrow && asOf.isNotEmpty()) time(rv, c.getString(R.string.w_updated) + " " + asOf, Cfg.MUTED)

        val items = d.optJSONArray("items") ?: JSONArray()
        val rems = d.optJSONArray("reminders") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        var anyN = false
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("label").isBlank()) continue
            list.add(it)
            if (it.optInt("n", 0) > 0) anyN = true
            if (list.size >= 8) break
        }
        val remList = ArrayList<JSONObject>()
        for (i in 0 until rems.length()) { val r = rems.optJSONObject(i) ?: continue; if (r.optString("text").isNotBlank()) remList.add(r) }

        if (!anyN && remList.isEmpty()) {
            msg(rv, "✓  " + c.getString(R.string.w_all_clear), Cfg.GREEN_800, false, openIntent(c, 99, "#day"))
            return rv
        }

        // 2×2 قصير (أقل من 130dp): سطر واحد بأهم عدد بدل المربعات
        if (narrow && h < 130) {
            var best: JSONObject? = null
            var others = 0
            for (it in list) {
                val n = it.optInt("n", 0)
                if (n <= 0) continue
                others++
                val b = best
                if (b == null || level(it) > level(b) || (level(it) == level(b) && n > b.optInt("n", 0))) best = it
            }
            val b = best
            if (b != null) {
                val txt = num(b.optInt("n", 0)) + " " + clip(b.optString("label"), 24) + (if (others > 1) " · " + c.getString(R.string.w_more, (others - 1).toString()) else "")
                rv.setTextViewText(R.id.w_hero, txt)
                rv.setTextColor(R.id.w_hero, levelColor(level(b)))
                rv.setOnClickPendingIntent(R.id.w_hero, openIntent(c, 100, b.optString("route")))
                rv.setViewVisibility(R.id.w_hero, View.VISIBLE)
            }
            if (remList.isNotEmpty()) badge(rv, c, remList)
            if (b == null) remLines(rv, c, remList, 1)
            return rv
        }

        // المربعات بارتفاع ثابت: 48dp في 2×2، و56dp في 4×2، و64dp في 4×3 فأكبر
        val tile = if (narrow) R.layout.widget_item_s else if (h >= 170) R.layout.widget_item_l else R.layout.widget_item_m
        val tileH = if (narrow) 48 else if (h >= 170) 64 else 56
        val perRow = if (narrow) 2 else 4
        var row: RemoteViews? = null
        var inRow = 0
        var rows = 0
        for (i in list.indices) {
            val it = list[i]
            if (row == null) { row = RemoteViews(c.packageName, R.layout.widget_row); inRow = 0 }
            val n = it.optInt("n", 0)
            val label = clip(it.optString("label"), 26)
            val cell = RemoteViews(c.packageName, tile)
            cell.setTextViewText(R.id.w_n, num(n))
            cell.setTextColor(R.id.w_n, if (n > 0) levelColor(level(it)) else Cfg.GREEN_800)
            cell.setTextViewText(R.id.w_label, if (n > 0) label else "✓ $label")
            cell.setContentDescription(R.id.w_item, label + ": " + n)
            cell.setOnClickPendingIntent(R.id.w_item, openIntent(c, 100 + i, it.optString("route")))
            row.addView(R.id.w_row, cell)
            inRow++
            if (inRow >= perRow || i == list.size - 1) { rv.addView(R.id.w_grid, row); row = null; rows++ }
        }
        if (rows > 0) rv.setViewVisibility(R.id.w_grid, View.VISIBLE)

        // التذكيرات في المساحة الباقية: سطر 28dp (+2) لكل تذكير، وإن لم تتسع لها كلها فآخر سطر «+N تذكيرات أخرى ›»
        if (remList.isNotEmpty()) {
            val left = h - 20 - 32 - rows * (tileH + 4) - 4
            var lines = left / 30
            if (!narrow && lines < 1) lines = 1
            val cap = if (narrow) 3 else if (h >= 250) 5 else 4
            if (lines > cap) lines = cap
            if (lines <= 0) badge(rv, c, remList) else remLines(rv, c, remList, lines)
        }
        return rv
    }

    private fun time(rv: RemoteViews, s: String, color: Int) {
        rv.setTextViewText(R.id.w_time, s)
        rv.setTextColor(R.id.w_time, color)
        rv.setViewVisibility(R.id.w_time, View.VISIBLE)
    }

    private fun msg(rv: RemoteViews, s: String, color: Int, logo: Boolean, tap: PendingIntent) {
        rv.setTextViewText(R.id.w_msg, s)
        rv.setTextColor(R.id.w_msg, color)
        rv.setViewVisibility(R.id.w_logo, if (logo) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.w_msgrow, View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.w_msgrow, tap)
    }

    /** شارة حمراء بعدد التذكيرات في الرأس، تفتح مسار أولها */
    private fun badge(rv: RemoteViews, c: Context, rems: List<JSONObject>) {
        rv.setTextViewText(R.id.w_badge, num(rems.size))
        rv.setContentDescription(R.id.w_badge, c.getString(R.string.w_badge_cd, rems.size.toString()))
        rv.setOnClickPendingIntent(R.id.w_badge, openIntent(c, 200, rems[0].optString("route")))
        rv.setViewVisibility(R.id.w_badge, View.VISIBLE)
    }

    private fun remLines(rv: RemoteViews, c: Context, rems: List<JSONObject>, lines: Int) {
        if (rems.isEmpty() || lines <= 0) return
        val truncated = rems.size > lines
        val show = if (truncated) lines - 1 else rems.size
        for (i in 0 until show) {
            val r = rems[i]
            val line = RemoteViews(c.packageName, R.layout.widget_rem)
            line.setTextViewText(R.id.w_rem_t, clip(r.optString("text"), 90))
            line.setTextColor(R.id.w_dot, AMBER)
            line.setOnClickPendingIntent(R.id.w_rem_row, openIntent(c, 201 + i, r.optString("route")))
            rv.addView(R.id.w_rem, line)
        }
        if (truncated) {
            val line = RemoteViews(c.packageName, R.layout.widget_rem)
            line.setTextViewText(R.id.w_rem_t, c.getString(R.string.w_more_rem, (rems.size - show).toString()) + " ›")
            line.setTextColor(R.id.w_rem_t, Cfg.GREEN_800)
            line.setViewVisibility(R.id.w_dot, View.INVISIBLE)
            line.setOnClickPendingIntent(R.id.w_rem_row, openIntent(c, 209, "#day"))
            rv.addView(R.id.w_rem, line)
        }
        rv.setViewVisibility(R.id.w_rem, View.VISIBLE)
    }
}
