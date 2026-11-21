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

    fun showLoading(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for (id in ids(c)) try {
            val rv = RemoteViews(c.packageName, R.layout.widget_admin)
            rv.setTextViewText(R.id.w_time, c.getString(R.string.w_loading))
            rv.setTextColor(R.id.w_time, Cfg.MUTED)
            m.partiallyUpdateAppWidget(id, rv)
        } catch (e: Exception) {}
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

    fun render(c: Context, m: AppWidgetManager, id: Int) {
        val p = prefs(c)
        // بلا حالة بعد (أُضيفت الأداة للتو والطلب الأول جارٍ): «جارٍ التحديث…»
        val state = p.getString("state", null) ?: ""
        val data = try { p.getString("data", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
        val o = try { m.getAppWidgetOptions(id) } catch (e: Exception) { null }
        val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0

        val rv = RemoteViews(c.packageName, R.layout.widget_admin)
        rv.setOnClickPendingIntent(R.id.w_head, refreshIntent(c))
        rv.removeAllViews(R.id.w_grid)
        rv.removeAllViews(R.id.w_rem)
        rv.setViewVisibility(R.id.w_grid, View.GONE)
        rv.setViewVisibility(R.id.w_rem, View.GONE)
        rv.setViewVisibility(R.id.w_msg, View.GONE)

        val scope = data?.optString("scope")?.takeIf { it.isNotBlank() && it != "null" }
            ?: data?.optString("day_label")?.takeIf { it.isNotBlank() && it != "null" }
            ?: c.getString(R.string.w_title)
        rv.setTextViewText(R.id.w_scope, clip(scope, 40))
        val asOf = data?.optString("as_of")?.takeIf { it.length in 4..5 } ?: ""

        // الحالات التي لا تُعرض فيها أرقام
        val blocker = when (state) {
            S_LOGIN -> R.string.w_login
            S_FINISH -> R.string.w_finish
            S_ADMIN -> R.string.w_admin_only
            "" -> R.string.w_loading
            else -> if (data == null) (if (state == S_STALE) R.string.w_stale_empty else R.string.w_login) else 0
        }
        if (blocker != 0) {
            rv.setTextViewText(R.id.w_scope, c.getString(R.string.w_title))
            rv.setTextViewText(R.id.w_time, if (state == S_STALE) c.getString(R.string.w_stale) else "")
            rv.setTextColor(R.id.w_time, Cfg.ORANGE)
            rv.setTextViewText(R.id.w_msg, c.getString(blocker))
            rv.setTextColor(R.id.w_msg, Cfg.GREEN_800)
            rv.setViewVisibility(R.id.w_msg, View.VISIBLE)
            // بلا اتصال ولا محفوظ: الضغط يعيد المحاولة؛ وغيره يفتح التطبيق (للدخول أو لإكماله)
            rv.setOnClickPendingIntent(R.id.w_msg, if (state == S_STALE) refreshIntent(c) else openIntent(c, 99, null))
            m.updateAppWidget(id, rv)
            return
        }

        val d = data!!
        if (state == S_STALE) {
            rv.setTextViewText(R.id.w_time, c.getString(R.string.w_stale) + (if (asOf.isNotEmpty()) " · $asOf" else ""))
            rv.setTextColor(R.id.w_time, Cfg.ORANGE)
        } else {
            rv.setTextViewText(R.id.w_time, if (asOf.isNotEmpty()) c.getString(R.string.w_updated) + " " + asOf else "")
            rv.setTextColor(R.id.w_time, Cfg.MUTED)
        }

        val items = d.optJSONArray("items") ?: JSONArray()
        val rems = d.optJSONArray("reminders") ?: JSONArray()
        var any = false
        val list = ArrayList<JSONObject>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("label").isBlank()) continue
            list.add(it)
            if (it.optInt("n", 0) > 0) any = true
            if (list.size >= 6) break
        }
        if (rems.length() > 0) any = true

        if (!any) {
            rv.setTextViewText(R.id.w_msg, c.getString(R.string.w_all_clear))
            rv.setTextColor(R.id.w_msg, Cfg.GREEN_800)
            rv.setViewVisibility(R.id.w_msg, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.w_msg, openIntent(c, 99, "#day"))
            m.updateAppWidget(id, rv)
            return
        }

        // العدّادات: صفّان من اثنين في الحجم 2×2، وصف واحد حين تتسع الأداة
        val perRow = if (w >= 250) (if (list.size < 4) list.size else 4) else 2
        var row: RemoteViews? = null
        var inRow = 0
        for (i in list.indices) {
            val it = list[i]
            if (row == null) { row = RemoteViews(c.packageName, R.layout.widget_row); inRow = 0 }
            val n = it.optInt("n", 0)
            val label = clip(it.optString("label"), 28)
            val cell = RemoteViews(c.packageName, R.layout.widget_item)
            cell.setTextViewText(R.id.w_n, num(n))
            cell.setTextColor(R.id.w_n, if (n > 0) Cfg.ORANGE else Cfg.GREEN_800)
            cell.setTextViewText(R.id.w_label, label)
            cell.setContentDescription(R.id.w_item, label + ": " + n)
            cell.setOnClickPendingIntent(R.id.w_item, openIntent(c, 100 + i, it.optString("route")))
            row.addView(R.id.w_row, cell)
            inRow++
            if (inRow >= perRow || i == list.size - 1) { rv.addView(R.id.w_grid, row); row = null }
        }
        rv.setViewVisibility(R.id.w_grid, if (list.isEmpty()) View.GONE else View.VISIBLE)

        // التذكيرات في الحجم الأكبر فقط (ارتفاع 190dp فأكثر)، حتى ثلاثة بحسب المساحة
        val roomFor = if (h >= 190) { val k = (h - 150) / 32; if (k > 3) 3 else if (k < 1) 1 else k } else 0
        var shown = 0
        for (i in 0 until rems.length()) {
            if (shown >= roomFor) break
            val r = rems.optJSONObject(i) ?: continue
            val t = r.optString("text")
            if (t.isBlank()) continue
            val line = RemoteViews(c.packageName, R.layout.widget_rem)
            line.setTextViewText(R.id.w_rem_t, "• " + clip(t, 90))
            line.setOnClickPendingIntent(R.id.w_rem_t, openIntent(c, 200 + i, r.optString("route")))
            rv.addView(R.id.w_rem, line)
            shown++
        }
        if (shown > 0) rv.setViewVisibility(R.id.w_rem, View.VISIBLE)
        if (list.isEmpty() && shown == 0) {
            // تذكيرات فقط والأداة صغيرة: سطر يدلّ عليها
            rv.setTextViewText(R.id.w_msg, c.getString(R.string.w_has_reminders))
            rv.setTextColor(R.id.w_msg, Cfg.ORANGE)
            rv.setViewVisibility(R.id.w_msg, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.w_msg, openIntent(c, 99, rems.optJSONObject(0)?.optString("route")))
        }
        m.updateAppWidget(id, rv)
    }
}
