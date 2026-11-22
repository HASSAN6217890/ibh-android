package com.nafahatsy.ibh

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تقرير رنّات هاتف الجرس للخادم (التطبيق 1.1.0): POST /api/bell/ring بترويسة Authorization: Bell <رمز>، والجسم
 * {date:"YYYY-MM-DD", at:"HH:MM", ok:true|false}. الخادم يقبل التكرار (idempotent).
 * - يُسجَّل بعد كل رنّة فعلية (ok:true) أو موعد فات بأكثر من دقيقتين بعد تشغيل الجرس (ok:false)، من الشاشة أو من المنبّه.
 * - الطابور صغير ومحفوظ في تفضيلات الجرس الخاصة، والإرسال في خيط خلفي فلا يؤخر الرنين أبداً، ولا يحتاج الواجهة.
 * - كل بند يحمل بصمة قصيرة للرمز الذي سُجّل في عهده: إن تغيّر رمز الجرس (فرع آخر) لا يُرسل بند قديم برمز جديد.
 * - لا يُحذف بند إلا بقبول الخادم (2xx) أو رفض شكله (400/413/422)، أو بعد يومين. وما سوى ذلك (لا اتصال، 404 أو 401 قبل
 *   نشر الطريق، 5xx) يبقى بهدوء ويُعاد لاحقاً: مع كل دورة للشاشة (كل 5 ثوانٍ يُفحص، والمحاولة بعد مهلة) ومع كل منبّه.
 */
object BellReport {
    private const val KEY = "ring_q"
    private const val MAX_AGE = 2 * 24 * 3600_000L
    private const val MAX_ITEMS = 120
    private const val RETRY_MS = 2 * 60_000L

    private val lock = Any()
    private val sending = AtomicBoolean(false)
    @Volatile private var nextTry = 0L

    private fun fp(token: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        for (i in 0 until 6) sb.append(String.format(java.util.Locale.ROOT, "%02x", d[i].toInt() and 0xff))
        return sb.toString()
    }

    private fun token(c: Context): String? {
        val t = SecureStore(c).get(SecureStore.BELL)
        return if (t != null && BellActivity.TOKEN_RE.matcher(t).matches()) t else null
    }

    private fun load(c: Context): JSONArray = try { JSONArray(BellPlan.prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(c: Context, a: JSONArray) {
        val e = BellPlan.prefs(c).edit()
        if (a.length() == 0) e.remove(KEY) else e.putString(KEY, a.toString())
        e.apply()
    }

    /** يضيف تقرير موعد إلى الطابور ثم يحاول الإرسال في الخلفية. آمن من الخيط الرئيسي (لا شبكة هنا) */
    /** fallback: رنّ بالنغمة الأساسية لأن النغمة المختارة تعذّر تشغيلها (حقل اختياري في التقرير) */
    fun queue(ctx: Context, date: String, at: String, ok: Boolean, fallback: Boolean = false) {
        val c = ctx.applicationContext
        try {
            val tok = token(c) ?: return
            val k = fp(tok)
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val old = load(c)
                val out = JSONArray()
                for (i in 0 until old.length()) {
                    val o = old.optJSONObject(i) ?: continue
                    if (now - o.optLong("q") > MAX_AGE) continue
                    if (o.optString("date") == date && o.optString("at") == at && o.optString("k") == k) continue
                    out.put(o)
                }
                val o = JSONObject()
                o.put("date", date); o.put("at", at); o.put("ok", ok); o.put("q", now); o.put("k", k)
                if (fallback) o.put("fb", true)
                // حالة بطارية هاتف الجرس لحظة الرنّة (للويب لاحقاً: تنبيه «هاتف الجرس غير موصول بالشاحن»)
                try {
                    val bm = c.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (pct in 0..100) o.put("bat", pct)
                    o.put("chg", bm.isCharging)
                } catch (e: Exception) {}
                out.put(o)
                while (out.length() > MAX_ITEMS) out.remove(0)
                save(c, out)
            }
            nextTry = 0L
            flush(c)
        } catch (e: Exception) {}
    }

    /** بعد حفظ رمز جديد أو حذفه: تُحذف فوراً بنود الرموز الأخرى (لا تُرسل أبداً برمز غير رمزها) */
    fun purgeOthers(ctx: Context) {
        val c = ctx.applicationContext
        try {
            val tok = token(c)
            val k = if (tok != null) fp(tok) else null
            synchronized(lock) {
                val old = load(c)
                val out = JSONArray()
                if (k != null) for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("k") == k) out.put(o) }
                save(c, out)
            }
            nextTry = 0L
        } catch (e: Exception) {}
    }

    fun pending(c: Context): Boolean = BellPlan.prefs(c).contains(KEY)

    /** يرسل ما في الطابور في خيط خلفي. يُستدعى كثيراً (مع كل دورة للشاشة)، فلا يعمل إلا إن كان في الطابور شيء وحلّ موعد المحاولة */
    fun flush(ctx: Context) {
        val c = ctx.applicationContext
        if (!pending(c) || System.currentTimeMillis() < nextTry) return
        if (!sending.compareAndSet(false, true)) return
        Thread {
            var failed = false
            try { failed = !sendAll(c) } catch (e: Exception) { failed = true }
            finally {
                nextTry = if (failed) System.currentTimeMillis() + RETRY_MS else 0L
                sending.set(false)
            }
        }.start()
    }

    /** true إن أُرسل كل ما في الطابور؛ وfalse (فتُؤجَّل المحاولة دقيقتين) إن بقي شيء أو لم يُرسل شيء */
    private fun sendAll(c: Context): Boolean {
        val tok = token(c)
        val k = if (tok != null) fp(tok) else ""
        val now = System.currentTimeMillis()
        val snap = synchronized(lock) { load(c) }
        val drop = HashSet<String>()
        var allDone = true
        for (i in 0 until snap.length()) {
            val o = snap.optJSONObject(i) ?: continue
            val id = o.optString("k") + "|" + o.optString("date") + "|" + o.optString("at") + "|" + o.optLong("q")
            if (now - o.optLong("q") > MAX_AGE) { drop.add(id); continue }
            // بند من رمز سابق (فرع آخر أو رمز أُلغي): لا يُرسل برمز غيره، ويبقى حتى تنتهي مدته إن عاد رمزه
            if (tok == null || o.optString("k") != k) { allDone = false; continue }
            val code = post(c, tok, o)
            if (code in 200..299 || code == 400 || code == 413 || code == 422) drop.add(id)
            else { allDone = false; break }
        }
        if (drop.isNotEmpty()) synchronized(lock) {
            val cur = load(c)
            val out = JSONArray()
            for (i in 0 until cur.length()) {
                val o = cur.optJSONObject(i) ?: continue
                val id = o.optString("k") + "|" + o.optString("date") + "|" + o.optString("at") + "|" + o.optLong("q")
                if (!drop.contains(id)) out.put(o)
            }
            save(c, out)
        }
        return allDone
    }

    private fun post(c: Context, tok: String, o: JSONObject): Int = try {
        val body = JSONObject()
        body.put("date", o.optString("date")); body.put("at", o.optString("at")); body.put("ok", o.optBoolean("ok"))
        if (o.optBoolean("fb")) body.put("tone_fallback", true)
        if (o.has("bat")) body.put("battery", o.optInt("bat"))
        if (o.has("chg")) body.put("charging", o.optBoolean("chg"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val h = URL(Cfg.BELL_RING).openConnection() as HttpURLConnection
        h.connectTimeout = 10000; h.readTimeout = 10000
        h.requestMethod = "POST"
        h.doOutput = true
        h.setFixedLengthStreamingMode(bytes.size)
        h.setRequestProperty("Authorization", "Bell $tok")
        h.setRequestProperty("X-IBH-App", "1")
        h.setRequestProperty("Content-Type", "application/json")
        h.setRequestProperty("Accept", "application/json")
        h.setRequestProperty("User-Agent", "IBHApp/" + Cfg.versionName(c) + " (Android " + Build.VERSION.RELEASE + ")")
        h.outputStream.use { it.write(bytes) }
        val code = h.responseCode
        try { val st = if (code < 400) h.inputStream else h.errorStream; if (st != null) UiKit.readAll(st, 64 shl 10) } catch (e: Exception) {}
        h.disconnect()
        code
    } catch (e: Exception) { -1 }
}
