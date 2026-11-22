package com.nafahatsy.ibh

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * وضع «هاتف الجرس» (الدفعة 27): هاتف مخصص موصول بالشاحن ومكبّر الصوت، يقرأ جرس فرع واحد برمز الجرس
 * (GET /api/bell بترويسة Authorization: Bell bt_…، Bell::feed في extras.php) بلا حساب موظف ولا جلسة تنتهي، ويرنّ في مواعيده.
 * - القواعد نفسها في صفحة الجرس بالمنصة: لا يرنّ ما فات وقته بأكثر من دقيقتين، ويُحدَّث الجدول كل 5 دقائق وعند تغيّر اليوم.
 * - ساعة الخادم (server_time) ومنطقته (tz) تضبطان الوقت، فلا يخطئ الجرس إن كانت ساعة الهاتف غير مضبوطة.
 * - آخر جدول (7 أيام) يُحفظ على الهاتف، فيبقى الجرس يعمل إن انقطع الإنترنت.
 * - الشاشة تبقى مضاءة ما دام الجرس يعمل، ويُبقى المعالج يقظاً (WAKE_LOCK) إن أُطفئت الشاشة بالزر.
 */
class BellActivity : Activity() {

    private lateinit var store: SecureStore
    private val prefs by lazy { BellPlan.prefs(this) }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusT: TextView
    private lateinit var nextT: TextView
    private lateinit var nextBig: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var msgT: TextView
    private lateinit var toneT: TextView
    private var listSig = ""
    private val hideMsg = Runnable { msgT.visibility = View.GONE }
    private var heldStopAt = 0L
    private val holdStop = Runnable { if (on) { heldStopAt = System.currentTimeMillis(); stop(); say(getString(R.string.bell_stopped_msg)) } }
    private lateinit var logT: TextView
    private lateinit var toggle: Button
    private lateinit var tokenBox: LinearLayout
    private lateinit var tokenIn: EditText
    private lateinit var tokenLink: TextView

    private var feed: JSONObject? = null
    private var fetchedAt = 0L
    /** آخر تحميل ناجح للجدول (منفصل عن وقت آخر محاولة): لملاحظة «من آخر جدول محفوظ» */
    private var lastOkAt = 0L
    /** رفض الرمز (401) مرتين متتاليتين فقط يمسحه، فلا يضيع بخطأ عابر في الخادم */
    private var rejects = 0
    private var fetching = false
    private var on = false
    private var lastDay = ""
    private var lastMin = -1.0
    private val log = ArrayList<String>()
    private var cpu: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        volumeControlStream = AudioManager.STREAM_ALARM
        build()
        loadCache()
        refreshTokenUi()
        if (token() != null && prefs.getBoolean("on", false)) start(false) else render()
        if (token() != null) fetch()
        // إيماءة الرجوع على أندرويد 13+ (الرجوع التنبؤي لا يستدعي onBackPressed): نفس القاعدة، لا خروج والجرس يعمل
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                android.window.OnBackInvokedCallback { handleBack() })
        }
    }

    private fun token(): String? {
        val t = store.get(SecureStore.BELL)
        return if (t != null && TOKEN_RE.matcher(t).matches()) t else null
    }

    // ---------------------------------------------------------------- الواجهة

    private fun tv(size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setLineSpacing(0f, 1.25f)
        return t
    }

    private fun btn(text: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 16f
        if (primary) { b.setTextColor(Color.WHITE); b.background = UiKit.pill(Cfg.GREEN_800, Cfg.dp(this, 24)) }
        else { b.setTextColor(Cfg.GREEN_800); b.background = UiKit.outline(Cfg.GREEN_800, Cfg.dp(this, 24), Cfg.dp(this, 1), Cfg.CREAM) }
        b.setPadding(Cfg.dp(this, 24), 0, Cfg.dp(this, 24), 0)
        return b
    }

    private fun add(parent: LinearLayout, v: View, h: Int = -2, top: Int = 0) {
        val lp = LinearLayout.LayoutParams(-1, h)
        lp.topMargin = Cfg.dp(this, top)
        parent.addView(v, lp)
    }

    @Suppress("DEPRECATION")
    private fun build() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Cfg.CREAM)
        scroll.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val p = Cfg.dp(this, 20)
        col.setPadding(p, p, p, p)
        scroll.addView(col)

        // الرجوع في الأعلى (سهم)، ولا خروج والجرس يعمل
        val back = tv(16f, Cfg.GREEN_800, true)
        back.text = "→  " + getString(R.string.bell_back)
        back.minHeight = Cfg.dp(this, 48)
        back.gravity = Gravity.CENTER_VERTICAL
        back.setOnClickListener { handleBack() }
        add(col, back)

        val title = tv(22f, Cfg.GREEN_800, true)
        title.text = getString(R.string.bell_title)
        add(col, title, top = 4)
        statusT = tv(17f, Cfg.INK, true); add(col, statusT, top = 10)
        // الرنّة التالية كبيرة، وتحتها العدّ التنازلي
        nextBig = tv(48f, Cfg.GREEN_800, true)
        nextBig.setLineSpacing(0f, 1f)
        add(col, nextBig, top = 8)
        nextT = tv(16f, Cfg.MUTED, true); add(col, nextT, top = 0)

        toggle = btn(getString(R.string.bell_start), true)
        toggle.setOnClickListener {
            // رفع الإصبع بعد الإيقاف المطوّل يصل نقرةً: لا يُعاد تشغيل الجرس بها
            if (System.currentTimeMillis() - heldStopAt < 3000L) return@setOnClickListener
            if (on) say(getString(R.string.bell_hold_to_stop)) else start(true)
        }
        // الإيقاف بالضغط المطوّل ثانيتين، فلا يُطفأ الجرس بلمسة عابرة
        toggle.setOnTouchListener { _, e ->
            if (on) when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { ui.removeCallbacks(holdStop); ui.postDelayed(holdStop, 2000L); say(getString(R.string.bell_holding)) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(holdStop)
            }
            false
        }
        add(col, toggle, Cfg.dp(this, 52), 16)
        // رسالة داخل الشاشة بدل Toast الذي يغطي الأزرار
        msgT = tv(15f, Cfg.INK, true)
        msgT.background = UiKit.pill(0xFFF6EBDD.toInt(), Cfg.dp(this, 12))
        msgT.setPadding(Cfg.dp(this, 14), Cfg.dp(this, 10), Cfg.dp(this, 14), Cfg.dp(this, 10))
        msgT.visibility = View.GONE
        msgT.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        add(col, msgT, top = 10)
        // مع قارئ الشاشة (TalkBack) لا يتأتى الضغط المطوّل ثانيتين: الضغط المطوّل يطلب تأكيداً ثم يوقف الجرس
        toggle.setOnLongClickListener {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            if (!on || !am.isTouchExplorationEnabled) return@setOnLongClickListener false
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.bell_stop_confirm)
                .setPositiveButton(R.string.bell_stop) { _, _ -> if (on) { stop(); say(getString(R.string.bell_stopped_msg)) } }
                .setNegativeButton(R.string.later, null)
                .show()
            true
        }
        // النغمة: اسمها، و«غيّر النغمة» و«جرّب» (رنّة كاملة كما سترنّ، تتوقف وحدها)
        toneT = tv(15f, Cfg.INK)
        add(col, toneT, top = 14)
        val trow = LinearLayout(this)
        trow.orientation = LinearLayout.HORIZONTAL
        val change = btn(getString(R.string.tone_change), false)
        change.setOnClickListener { TonePick.show(this, BellTone.BELL) { renderTone() } }
        val test = btn(getString(R.string.tone_try), false)
        test.setOnClickListener {
            val pl = BellTone.play(this, BellTone.BELL, 2)
            if (pl.fellBack) say(getString(R.string.tone_failed_bell))
            checkVolume()
        }
        val tl1 = LinearLayout.LayoutParams(0, Cfg.dp(this, 48), 1f)
        val tl2 = LinearLayout.LayoutParams(0, Cfg.dp(this, 48), 1f); tl2.marginStart = Cfg.dp(this, 10)
        trow.addView(change, tl1); trow.addView(test, tl2)
        add(col, trow, top = 6)

        val hint = tv(14f, Cfg.MUTED)
        hint.text = getString(R.string.bell_hint)
        add(col, hint, top = 14)

        val lh = tv(16f, Cfg.INK, true); lh.text = getString(R.string.bell_today); add(col, lh, top = 18)
        listBox = LinearLayout(this); listBox.orientation = LinearLayout.VERTICAL; add(col, listBox, top = 4)
        logT = tv(13f, Cfg.MUTED); add(col, logT, top = 12)

        // الرمز
        tokenLink = tv(14f, Cfg.GREEN_800)
        tokenLink.text = getString(R.string.bell_change_token)
        tokenLink.setPadding(0, Cfg.dp(this, 16), 0, Cfg.dp(this, 8))
        tokenLink.setOnClickListener { tokenBox.visibility = View.VISIBLE; tokenLink.visibility = View.GONE }
        add(col, tokenLink, top = 8)

        tokenBox = LinearLayout(this)
        tokenBox.orientation = LinearLayout.VERTICAL
        val th = tv(15f, Cfg.INK); th.text = getString(R.string.bell_token_help); add(tokenBox, th, top = 8)
        tokenIn = EditText(this)
        tokenIn.hint = "bt_…"
        tokenIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        tokenIn.textDirection = View.TEXT_DIRECTION_LTR
        tokenIn.isSingleLine = true
        add(tokenBox, tokenIn, top = 6)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val paste = btn(getString(R.string.paste), false)
        paste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) tokenIn.setText(c.getItemAt(0).coerceToText(this).toString().trim())
        }
        val save = btn(getString(R.string.bell_save_token), true)
        save.setOnClickListener { saveToken(tokenIn.text.toString()) }
        val l1 = LinearLayout.LayoutParams(0, Cfg.dp(this, 48), 1f)
        val l2 = LinearLayout.LayoutParams(0, Cfg.dp(this, 48), 1f); l2.marginStart = Cfg.dp(this, 10)
        row.addView(save, l1); row.addView(paste, l2)
        add(tokenBox, row, top = 10)
        val forget = tv(14f, Cfg.ORANGE)
        forget.text = getString(R.string.bell_forget)
        forget.setPadding(0, Cfg.dp(this, 14), 0, Cfg.dp(this, 6))
        forget.setOnClickListener { stop(); store.put(SecureStore.BELL, null); BellReport.purgeOthers(this); prefs.edit().remove("feed").apply(); feed = null; refreshTokenUi(); render() }
        add(tokenBox, forget)
        add(col, tokenBox)


        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            window.decorView.setBackgroundColor(Cfg.GREEN_800)
            scroll.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.setMargins(b.left, b.top, b.right, b.bottom); v.layoutParams = it }
                WindowInsets.CONSUMED
            }
        } else {
            window.statusBarColor = Cfg.GREEN_800
            window.navigationBarColor = Cfg.GREEN_800
        }
    }

    private fun renderTone() {
        toneT.text = getString(R.string.tone_current) + " " + BellTone.choiceName(this, BellTone.BELL)
    }

    override fun onResume() {
        super.onResume()
        renderTone()
        // تعذّر تشغيل النغمة المختارة في رنّة سابقة (من المنبّه والشاشة مغلقة): تنبيه صغير مرة واحدة
        if (BellTone.takeFailure(this, BellTone.BELL)) say(getString(R.string.tone_failed_bell))
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!TonePick.handle(this, BellTone.BELL, requestCode, resultCode, data) { renderTone() }) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun refreshTokenUi() {
        val has = token() != null
        tokenBox.visibility = if (has) View.GONE else View.VISIBLE
        tokenLink.visibility = if (has) View.VISIBLE else View.GONE
        toggle.isEnabled = has
        toggle.alpha = if (has) 1f else 0.5f
    }

    private fun saveToken(raw: String) {
        val t = raw.trim().lowercase(Locale.ROOT)
        if (!TOKEN_RE.matcher(t).matches()) { toast(getString(R.string.bell_bad_token)); return }
        store.put(SecureStore.BELL, t)
        BellReport.purgeOthers(this)
        if (store.get(SecureStore.BELL) == null) { toast(getString(R.string.bell_store_failed)); return }
        tokenIn.setText("")
        prefs.edit().remove("feed").apply(); feed = null
        refreshTokenUi()
        fetch()
    }

    // ---------------------------------------------------------------- التشغيل

    /** byUser: بزر «شغّل» (تُحفظ لحظة التشغيل لتقارير «فات»)، لا عند إعادة فتح الشاشة والجرس يعمل أصلاً */
    private fun start(byUser: Boolean) {
        if (token() == null) return
        on = true
        prefs.edit().putBoolean("on", true).apply()
        val t0 = now()
        BellPlan.markOnFrom(this, t0.first, t0.second, byUser)
        if (byUser) { BellPlan.markPastAsRung(this, feed, t0.first, t0.second); BellPlan.setChecked(this, t0.first, t0.second) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLock(true)
        acquireCpu()
        val n = now()
        lastDay = n.first; lastMin = n.second
        BellTone.play(this, BellTone.BELL, 1)
        checkVolume()
        ui.removeCallbacks(tick)
        ui.post(tick)
        BellPlan.schedule(this)
        render()
    }

    private fun stop() {
        on = false
        prefs.edit().putBoolean("on", false).apply()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLock(false)
        ui.removeCallbacks(tick)
        BellPlan.cancel(this)
        releaseCpu()
        render()
    }

    /** الظهور فوق شاشة القفل ما دام الجرس يعمل فقط (لا من الـ manifest) */
    @Suppress("DEPRECATION")
    private fun showOverLock(v: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(v)
        else if (v) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    private fun acquireCpu() {
        try {
            if (cpu == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibh:bell")
                cpu!!.setReferenceCounted(false)
            }
            cpu!!.acquire(14 * 3600_000L)
        } catch (e: Exception) {}
    }

    private fun releaseCpu() { try { if (cpu?.isHeld == true) cpu!!.release() } catch (e: Exception) {} }

    private fun checkVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) toast(getString(R.string.bell_muted))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            try { step() } catch (e: Exception) {}
            ui.postDelayed(this, 5000)
        }
    }

    private fun step() {
        val n = now()
        if (n.first != lastDay) { lastDay = n.first; lastMin = -1.0; fetch() }
        else if (System.currentTimeMillis() - fetchedAt > 5 * 60_000L) fetch()
        val due = ArrayList<Pair<Int, String>>()
        for (r in BellPlan.rings(today(n.first))) {
            if (r.first <= lastMin || r.first > n.second) continue
            val k = BellPlan.key(n.first, r.first)
            if (BellPlan.wasRung(this, k)) { addLog(r.second); continue } // رنّ بالمنبّه الدقيق (وهو أبلغ عنه)
            BellPlan.markRung(this, k)
            if (n.second - r.first <= 2.0) due.add(r)
            else {
                addLog(r.second + " · " + getString(R.string.bell_missed))
                if (BellPlan.afterOn(this, n.first, r.first)) { BellPlan.markMissed(this, k); BellReport.queue(this, n.first, BellPlan.hm(r.first), false) }
            }
        }
        lastMin = n.second
        val day = n.first
        // الرنّات المتزامنة تتوالى بقدر مدة النغمة المختارة، فلا تقطع إحداها الأخرى
        val gap = BellTone.durationMs(this, BellTone.BELL, 2) + 400L
        for (i in due.indices) {
            val r = due[i]
            ui.postDelayed({
                val pl = BellTone.play(this, BellTone.BELL, 2)
                addLog(r.second)
                if (pl.fellBack) say(getString(R.string.tone_failed_bell))
                BellReport.queue(this, day, BellPlan.hm(r.first), true, pl.fellBack)
            }, i * gap)
        }
        if (due.isNotEmpty()) BellPlan.schedule(this)
        // تقارير لم تُرسل بعد: يُفحص هنا كثيراً، ولا محاولة إلا إن حلّ موعدها (BellReport.flush)
        BellReport.flush(this)
        render()
    }

    // ---------------------------------------------------------------- الجدول

    private fun now(): Pair<String, Double> = BellPlan.now(feed, BellPlan.offset(this))
    private fun today(date: String): JSONObject? = BellPlan.day(feed, date)

    private fun addLog(s: String) {
        log.add(s)
        while (log.size > 6) log.removeAt(0)
        render()
    }

    private fun render() {
        val f = feed
        val n = now()
        val day = today(n.first)
        val unit = f?.optString("unit_name") ?: ""
        statusT.text = when {
            token() == null -> getString(R.string.bell_need_token)
            f == null -> getString(if (fetching) R.string.bell_loading else R.string.bell_no_data)
            day == null -> getString(R.string.bell_stale)
            !day.optBoolean("ring") -> getString(R.string.bell_no_ring_today) + (day.optString("off_reason").let { if (it.isNotEmpty() && it != "null") ": $it" else "" })
            on -> getString(R.string.bell_running) + " " + unit
            else -> getString(R.string.bell_stopped) + " " + unit
        }
        // قائمة اليوم: ✓ رنّ فعلاً، ✗ فات بعد تشغيل الجرس، ◀ التالي، و«—» لما مضى قبل التشغيل أو والجرس متوقف
        var nextM = -1
        val rows = ArrayList<Pair<Int, String>>()
        val sig = StringBuilder()
        for (r in BellPlan.rings(day)) {
            val k = BellPlan.key(n.first, r.first)
            val st = when {
                r.first > n.second -> if (nextM < 0) { nextM = r.first; 3 } else 4
                BellPlan.wasMissed(this, k) -> 2
                BellPlan.wasRung(this, k) && BellPlan.afterOn(this, n.first, r.first) -> 1
                else -> 0
            }
            rows.add(Pair(st, r.second)); sig.append(st).append(r.second).append(';')
        }
        if (sig.toString() != listSig) { listSig = sig.toString(); drawList(rows) }
        if (nextM >= 0) {
            nextBig.text = RingSched.hm12(BellPlan.hm(nextM))
            val left = Math.ceil(nextM - n.second).toInt()
            nextT.text = getString(R.string.bell_next) + " " + inWords(left)
            nextBig.alpha = if (on) 1f else 0.4f
            nextBig.visibility = View.VISIBLE; nextT.visibility = View.VISIBLE
        } else { nextBig.visibility = View.GONE; nextT.visibility = View.GONE }
        val stale = if (f != null && System.currentTimeMillis() - lastOkAt > 10 * 60_000L)
            (if (lastOkAt > 0) getString(R.string.bell_offline_cache_at, SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(java.util.Date(lastOkAt))) else getString(R.string.bell_offline_cache)) else ""
        val lg = StringBuilder()
        for (i in log.indices.reversed()) { if (lg.isNotEmpty()) lg.append('\n'); lg.append(log[i]) }
        logT.text = (if (stale.isNotEmpty()) stale + "\n" else "") + lg.toString()
        toggle.text = getString(if (on) R.string.bell_stop else R.string.bell_start)
    }

    /** «بعد 12 دقيقة»، «بعد ساعة و5 دقائق» بأرقام 0–9 */
    private fun inWords(min: Int): String {
        if (min <= 1) return getString(R.string.bell_in_now)
        val h = min / 60; val m = min % 60
        val hs = if (h == 0) "" else if (h == 1) getString(R.string.bell_hour) else if (h == 2) getString(R.string.bell_2hours)
            else h.toString() + " " + getString(if (h <= 10) R.string.bell_hours else R.string.bell_hour)
        val ms = if (m == 0) "" else if (m == 1) getString(R.string.bell_minute) else if (m == 2) getString(R.string.bell_2minutes)
            else m.toString() + " " + getString(if (m <= 10) R.string.bell_minutes_pl else R.string.bell_minute)
        return getString(R.string.bell_in) + " " + (if (hs.isNotEmpty() && ms.isNotEmpty()) "$hs و$ms" else hs + ms)
    }

    private fun drawList(rows: List<Pair<Int, String>>) {
        listBox.removeAllViews()
        if (rows.isEmpty()) { val t = tv(15f, Cfg.MUTED); t.text = "—"; listBox.addView(t); return }
        for (r in rows) {
            val t = tv(16f, Cfg.INK, r.first == 3)
            t.minHeight = Cfg.dp(this, 48)
            t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val (mark, color) = when (r.first) {
                1 -> Pair("✓ " + getString(R.string.bell_st_rung), Cfg.GREEN_800)
                2 -> Pair("✗ " + getString(R.string.bell_st_missed), 0xFFB23A22.toInt())
                3 -> Pair("◀ " + getString(R.string.bell_st_next), Cfg.GREEN_800)
                4 -> Pair("", Cfg.INK)
                else -> Pair("—", Cfg.MUTED)
            }
            t.setTextColor(color)
            t.text = if (mark.isEmpty()) r.second else r.second + "   " + mark
            listBox.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun loadCache() {
        val s = prefs.getString("feed", null) ?: return
        try { feed = JSONObject(s); lastOkAt = prefs.getLong("ok_at", 0L) } catch (e: Exception) { feed = null }
    }

    private fun fetch() {
        val tok = token() ?: return
        if (fetching) return
        fetching = true
        render()
        val ua = "IBHApp/" + Cfg.versionName(this) + " (Android " + Build.VERSION.RELEASE + ")"
        Thread {
            var code = 0
            var body: String? = null
            val sent = System.currentTimeMillis()
            val sentEl = android.os.SystemClock.elapsedRealtime()
            try {
                val c = URL(Cfg.BELL_FEED + "?days=7").openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 20000
                c.setRequestProperty("Authorization", "Bell $tok")
                c.setRequestProperty("X-IBH-App", "1")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", ua)
                code = c.responseCode
                val st = if (code < 400) c.inputStream else c.errorStream
                body = if (st != null) String(UiKit.readAll(st, 2 shl 20), Charsets.UTF_8) else null
                c.disconnect()
            } catch (e: Exception) { code = -1 }
            val got = System.currentTimeMillis()
            val gotEl = android.os.SystemClock.elapsedRealtime()
            runOnUiThread { fetched(code, body, (sent + got) / 2, (sentEl + gotEl) / 2) }
        }.start()
    }

    private fun fetched(code: Int, body: String?, mid: Long, midEl: Long) {
        fetching = false
        if (isFinishing) return
        if (code == 200 && body != null) {
            try {
                val j = JSONObject(body)
                val d = j.optJSONObject("data")
                if (j.optBoolean("ok") && d != null) {
                    val st = d.optString("server_time")
                    val t = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(st)?.time } catch (e: Exception) { null }
                    BellPlan.saveClock(this, t, mid, midEl)
                    feed = d
                    fetchedAt = System.currentTimeMillis()
                    lastOkAt = fetchedAt
                    rejects = 0
                    prefs.edit().putString("feed", d.toString()).putLong("ok_at", lastOkAt).apply()
                    if (on) { acquireCpu(); BellPlan.schedule(this) }
                }
            } catch (e: Exception) {}
        } else if (code == 401 && ++rejects < 2) {
            // أول رفض: لا يُمسح الرمز، ويُعاد الطلب بعد دقيقة
            fetchedAt = System.currentTimeMillis() - 4 * 60_000L
            toast(getString(R.string.bell_token_rejected_once))
        } else if (code == 401) {
            rejects = 0
            stop()
            store.put(SecureStore.BELL, null)
            BellReport.purgeOthers(this)
            prefs.edit().remove("feed").apply(); feed = null
            refreshTokenUi()
            toast(getString(R.string.bell_token_rejected))
        } else if (code == 403) {
            toast(getString(R.string.bell_token_other_unit))
        } else {
            // لا اتصال أو خطأ مؤقت: يبقى آخر جدول محفوظ، ويُعاد الطلب بعد دقيقة
            fetchedAt = System.currentTimeMillis() - 4 * 60_000L
        }
        render()
    }

    private fun toast(s: String) = say(s)

    /** رسالة داخل الشاشة تختفي بعد 6 ثوانٍ */
    private fun say(s: String) {
        msgT.text = s
        msgT.visibility = View.VISIBLE
        ui.removeCallbacks(hideMsg)
        ui.postDelayed(hideMsg, 6000L)
    }

    private fun handleBack() {
        // الجرس يعمل: تبقى الشاشة في المقدّمة، ولا خروج إلا بـ «أوقف الجرس»
        if (on) { toast(getString(R.string.bell_stop_first)); return }
        finish()
    }

    @Deprecated("Activity API")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        releaseCpu()
        super.onDestroy()
    }

    companion object {
        val TOKEN_RE: java.util.regex.Pattern = java.util.regex.Pattern.compile("^bt_[a-f0-9]{40}$")
    }
}
