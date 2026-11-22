package com.nafahatsy.ibh

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * إعدادات «تنبيهات الدوام» (التطبيق 1.2.0): شاشة أصلية صغيرة من الاختصار (الضغط المطوّل على أيقونة التطبيق) أو من المنصة
 * (IBHNative.openAlerts). تشغيل/إيقاف، وصوت أو اهتزاز فقط، وتنبيه نهاية الحصة قبل دقيقة أو عندها. وإن كانت الإشعارات
 * ممنوعة: سطر يدلّ على إعدادات الهاتف بلا إلحاح.
 */
class AlertsActivity : Activity() {
    private lateinit var nextT: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var opts: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun tv(size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setLineSpacing(0f, 1.25f)
        return t
    }

    private fun add(parent: LinearLayout, v: View, top: Int = 0) {
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Cfg.dp(this, top)
        parent.addView(v, lp)
    }

    private fun radio(text: String, id: Int): RadioButton {
        val r = RadioButton(this)
        r.id = id
        r.text = text
        r.textSize = 16f
        r.setTextColor(Cfg.INK)
        r.minHeight = Cfg.dp(this, 48)
        if (Build.VERSION.SDK_INT >= 21) r.buttonTintList = android.content.res.ColorStateList.valueOf(Cfg.GREEN_800)
        return r
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

        val back = tv(16f, Cfg.GREEN_800, true)
        back.text = "→  " + getString(R.string.bell_back)
        back.minHeight = Cfg.dp(this, 48)
        back.gravity = android.view.Gravity.CENTER_VERTICAL
        back.setOnClickListener { finish() }
        add(col, back)

        val title = tv(22f, Cfg.GREEN_800, true)
        title.text = getString(R.string.al_title)
        add(col, title, 4)
        val intro = tv(14f, Cfg.MUTED)
        intro.text = getString(R.string.al_intro)
        add(col, intro, 6)

        val sw = Switch(this)
        sw.text = getString(R.string.al_on)
        sw.textSize = 17f
        sw.setTextColor(Cfg.INK)
        sw.minHeight = Cfg.dp(this, 52)
        sw.isChecked = RingSched.enabled(this)
        sw.setOnCheckedChangeListener { _, v -> RingSched.setEnabled(this, v); refresh() }
        add(col, sw, 16)

        opts = LinearLayout(this)
        opts.orientation = LinearLayout.VERTICAL
        add(col, opts)

        val h1 = tv(15f, Cfg.INK, true); h1.text = getString(R.string.al_how); add(opts, h1, 14)
        val g1 = RadioGroup(this)
        g1.addView(radio(getString(R.string.al_sound), 1))
        g1.addView(radio(getString(R.string.al_vibe), 2))
        g1.check(if (RingSched.vibrateOnly(this)) 2 else 1)
        g1.setOnCheckedChangeListener { _, id -> RingSched.setVibrateOnly(this, id == 2) }
        add(opts, g1, 2)

        val h2 = tv(15f, Cfg.INK, true); h2.text = getString(R.string.al_end); add(opts, h2, 14)
        val g2 = RadioGroup(this)
        g2.addView(radio(getString(R.string.al_end_early), 3))
        g2.addView(radio(getString(R.string.al_end_on_time), 4))
        g2.check(if (RingSched.earlyEnd(this)) 3 else 4)
        g2.setOnCheckedChangeListener { _, id -> RingSched.setEarlyEnd(this, id == 3); refresh() }
        add(opts, g2, 2)

        nextT = tv(15f, Cfg.GREEN_800, true)
        add(col, nextT, 18)

        permBox = LinearLayout(this)
        permBox.orientation = LinearLayout.VERTICAL
        val ph = tv(14f, Cfg.INK)
        ph.text = getString(R.string.al_perm_off)
        add(permBox, ph, 0)
        val pl = tv(15f, Cfg.GREEN_800, true)
        pl.text = getString(R.string.al_perm_open)
        pl.minHeight = Cfg.dp(this, 48)
        pl.gravity = android.view.Gravity.CENTER_VERTICAL
        pl.setOnClickListener { openNotifSettings() }
        add(permBox, pl, 4)
        add(col, permBox, 16)

        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            window.decorView.setBackgroundColor(Cfg.GREEN_800)
            scroll.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.setMargins(b.left, b.top, b.right, b.bottom); v.layoutParams = it }
                WindowInsets.CONSUMED
            }
        } else {
            window.statusBarColor = Cfg.GREEN_800
            window.navigationBarColor = Cfg.GREEN_800
        }
    }

    private fun refresh() {
        val on = RingSched.enabled(this)
        opts.alpha = if (on) 1f else 0.45f
        for (i in 0 until opts.childCount) setEnabledDeep(opts.getChildAt(i), on)
        val loggedIn = SecureStore(this).get(SecureStore.SESSION) != null
        val nx = RingSched.nextItem(this)
        nextT.text = when {
            !on -> getString(R.string.al_off_now)
            !loggedIn -> getString(R.string.al_need_login)
            nx == null -> getString(R.string.al_none)
            else -> getString(R.string.al_next) + " " + nx.second.optString("text").trim() + " · " +
                RingSched.hm12(nx.second.optString("at")) + dayWord(nx.second.optString("_date"))
        }
        permBox.visibility = if (on && !RingSched.notificationsAllowed(this)) View.VISIBLE else View.GONE
    }

    private fun dayWord(date: String): String {
        val c = java.util.Calendar.getInstance()
        val today = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        return if (date.isEmpty() || date == today) "" else " (" + date + ")"
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun openNotifSettings() {
        try {
            val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {}
    }
}
