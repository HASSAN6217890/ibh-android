package com.nafahatsy.ibh

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * أصوات الجرس وتنبيهات الدوام (التطبيق 1.3.0).
 * - خمس نغمات مدمجة تُركَّب في الذاكرة (PCM) بلا ملفات: لكل منها طابع مختلف، بصعود ناعم وخفوت طبيعي فلا تكون حادة.
 * - أو نغمة من الجوال: من منتقي نغمات النظام (منبّه/رنين/إشعار) أو أي ملف صوتي (إذن قراءة دائم من منتقي الملفات).
 * - الجرس على مسار المنبّه (USAGE_ALARM) فيرنّ ولو كان الهاتف صامتاً، كما كان. وتنبيهات الدوام على مسار الإشعارات
 *   (USAGE_NOTIFICATION_EVENT) فتتبع الصامت و«عدم الإزعاج».
 * - المدة: النغمة المدمجة تُكرَّر بعدد الرنّات (times) كما كان؛ ونغمة الجوال تُقطع بعد 3 ثوانٍ لكل رنّة (6 ثوانٍ للرنّة العادية).
 * - إن تعذّر تشغيل نغمة الجوال (حُذف الملف أو زال الإذن): تُشغَّل النغمة الأساسية، ويُسجَّل ذلك (failed_at) لتنبيه صغير
 *   في الشاشة، ويُعاد للمستدعي fellBack لتقرير الرنّة.
 * الاختيار محفوظ في تفضيلات كل خدمة: «tone» بصيغة b:<معرّف> | r:<رابط منتقي النظام> | f:<رابط ملف> | s: (نغمة إشعارات الهاتف،
 * للتنبيهات فقط، يشغّلها النظام من قناة الإشعار)، و«tone_name» اسمها المعروض.
 */
object BellTone {
    const val BELL = 0
    const val ALERT = 1
    private const val RATE = 22050
    private const val CUSTOM_MS_PER_RING = 3000L

    /** معرّفات النغمات المدمجة بالترتيب المعروض، وأسماؤها في strings.xml */
    val BUILTIN = arrayOf("classic", "chime", "marimba", "bowl", "melody")
    val BUILTIN_NAMES = intArrayOf(R.string.tone_classic, R.string.tone_chime, R.string.tone_marimba, R.string.tone_bowl, R.string.tone_melody)

    class Played(val ms: Long, val fellBack: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private val cache = HashMap<String, ShortArray>()

    fun prefs(c: Context, target: Int): SharedPreferences = if (target == BELL) BellPlan.prefs(c) else RingSched.prefs(c)

    fun choice(c: Context, target: Int): String {
        val v = prefs(c, target).getString("tone", null)
        return v ?: if (target == BELL) "b:classic" else "s:"
    }

    fun choiceName(c: Context, target: Int): String {
        val v = choice(c, target)
        if (v.startsWith("b:")) { val i = BUILTIN.indexOf(v.substring(2)); return c.getString(BUILTIN_NAMES[if (i < 0) 0 else i]) }
        if (v.startsWith("s:")) return c.getString(R.string.tone_system)
        return prefs(c, target).getString("tone_name", null)?.takeIf { it.isNotBlank() } ?: c.getString(R.string.tone_phone)
    }

    /** يحفظ الاختيار؛ ويُفرج عن إذن الملف السابق إن لم يعد مستعملاً في الخدمة الأخرى */
    fun setChoice(c: Context, target: Int, value: String, name: String?) {
        val old = choice(c, target)
        prefs(c, target).edit().putString("tone", value).putString("tone_name", name ?: "").remove("failed_at").apply()
        if (old.startsWith("f:") && old != value && old != choice(c, 1 - target)) {
            try { c.contentResolver.releasePersistableUriPermission(Uri.parse(old.substring(2)), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        }
    }

    /** آخر مرة تعذّر فيها تشغيل النغمة المختارة (0 = لا) ويُمسح بعد قراءته */
    fun takeFailure(c: Context, target: Int): Boolean {
        val p = prefs(c, target)
        if (!p.contains("failed_at")) return false
        p.edit().remove("failed_at").apply()
        return true
    }

    // ---------------------------------------------------------------- التشغيل

    /** المدة المتوقعة (للمؤقتات في المستقبِل والشاشة) */
    fun durationMs(c: Context, target: Int, times: Int): Long {
        val v = choice(c, target)
        val n = if (times < 1) 1 else times
        return if (v.startsWith("b:")) pcm(v.substring(2), n).size * 1000L / RATE else CUSTOM_MS_PER_RING * n
    }

    /** يشغّل نغمة الخدمة. لا يرمي استثناء أبداً. «s:» لا يشغّل شيئاً (النظام يتولاه من قناة الإشعار) */
    fun play(c: Context, target: Int, times: Int): Played {
        val v = choice(c, target)
        val n = if (times < 1) 1 else times
        if (v.startsWith("s:")) return Played(0L, false)
        if (v.startsWith("b:")) return Played(playPcm(v.substring(2), n, target), false)
        if (playUri(c, v, n, target)) return Played(CUSTOM_MS_PER_RING * n, false)
        // تعذّرت نغمة الجوال: النغمة الأساسية بدلها، ويُسجَّل للتنبيه
        prefs(c, target).edit().putLong("failed_at", System.currentTimeMillis()).apply()
        return Played(playPcm("classic", n, target), true)
    }

    /** معاينة اختيار لم يُحفظ بعد («جرّب» في نافذة الاختيار): مرة واحدة، تتوقف وحدها */
    fun preview(c: Context, target: Int, value: String): Boolean {
        stop()
        if (value.startsWith("b:")) { playPcm(value.substring(2), 1, target); return true }
        if (value.startsWith("s:")) {
            return try {
                val r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) ?: return false
                r.audioAttributes = attrs(target); r.play()
                main.postDelayed({ try { r.stop() } catch (e: Exception) {} }, CUSTOM_MS_PER_RING)
                true
            } catch (e: Exception) { false }
        }
        return playUri(c, value, 1, target)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        player = null
        try { track?.stop() } catch (e: Exception) {}
        try { track?.release() } catch (e: Exception) {}
        track = null
    }

    private fun attrs(target: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (target == BELL) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun playUri(c: Context, v: String, times: Int, target: Int): Boolean {
        val uri = try { Uri.parse(v.substring(2)) } catch (e: Exception) { return false }
        // ملف من منتقي الملفات: لا يُشغَّل إلا بإذنه الدائم (إن زال الإذن فالنغمة الأساسية)
        if (v.startsWith("f:")) {
            var held = false
            try { for (pp in c.contentResolver.persistedUriPermissions) if (pp.uri == uri && pp.isReadPermission) held = true } catch (e: Exception) {}
            if (!held) return false
        }
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs(target))
            mp.setDataSource(c.applicationContext, uri)
            mp.isLooping = true
            mp.prepare()
            stop()
            player = mp
            mp.setOnErrorListener { p, _, _ -> try { p.release() } catch (e: Exception) {}; if (player === p) player = null; true }
            mp.start()
            val limit = CUSTOM_MS_PER_RING * times
            // خفوت في آخر 600 مللي ثم إيقاف
            main.postDelayed({ fade(mp, 600L) }, limit - 600L)
            true
        } catch (e: Exception) {
            if (v.startsWith("r:")) playRingtone(c, uri, times, target) else false
        }
    }

    /** نغمات النظام التي لا يقرؤها التطبيق مباشرة (نغمة مخصّصة في ذاكرة الهاتف): عبر Ringtone الذي يشغّلها النظام عند الحاجة */
    private fun playRingtone(c: Context, uri: Uri, times: Int, target: Int): Boolean = try {
        val r = RingtoneManager.getRingtone(c, uri)
        if (r == null) false else {
            r.audioAttributes = attrs(target)
            r.play()
            main.postDelayed({ try { r.stop() } catch (e: Exception) {} }, CUSTOM_MS_PER_RING * times)
            true
        }
    } catch (e: Exception) { false }

    private fun fade(mp: MediaPlayer, ms: Long) {
        val steps = 6
        for (i in 1..steps) main.postDelayed({
            try {
                val vol = 1f - i.toFloat() / steps
                mp.setVolume(vol, vol)
                if (i == steps) { mp.stop(); mp.release(); if (player === mp) player = null }
            } catch (e: Exception) {}
        }, ms * i / steps)
    }

    private fun playPcm(id: String, times: Int, target: Int): Long {
        return try {
            val data = pcm(id, times)
            val fmt = AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack(attrs(target), fmt, data.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(data, 0, data.size)
            t.play()
            val ms = data.size * 1000L / RATE
            // كل مسار يُحرَّر بعد انتهائه (رنّات متتالية لا تقطع بعضها)
            Thread { try { Thread.sleep(ms + 300) } catch (e: InterruptedException) {}; try { t.release() } catch (e: Exception) {} }.start()
            ms
        } catch (e: Exception) { 0L }
    }

    // ---------------------------------------------------------------- تركيب النغمات

    /** نغمة واحدة: تردد، وبدء ومدة بالثواني، وسرعة الخفوت، ومركّبات [نسبة التردد، الشدة]... */
    private fun note(buf: FloatArray, start: Double, f: Double, dur: Double, decay: Double, parts: DoubleArray, attack: Double = 0.008) {
        val s0 = (start * RATE).toInt()
        val len = (dur * RATE).toInt()
        for (i in 0 until len) {
            val k = s0 + i
            if (k >= buf.size) break
            val t = i.toDouble() / RATE
            val env = (if (t < attack) t / attack else 1.0) * Math.exp(-t * decay) * (if (len - i < 220) (len - i) / 220.0 else 1.0)
            var v = 0.0
            var j = 0
            while (j + 1 < parts.size) {
                // المركّبات العليا تخفت أسرع، فيبقى الصوت دافئاً لا معدنياً حاداً
                v += Math.sin(2.0 * Math.PI * f * parts[j] * t) * parts[j + 1] * Math.exp(-t * decay * (parts[j] - 1.0) * 0.6)
                j += 2
            }
            buf[k] += (v * env).toFloat()
        }
    }

    /** نمط النغمة الواحد [المدة بالثواني، الرسم] */
    private fun pattern(id: String): Pair<Double, (FloatArray, Double) -> Unit> = when (id) {
        // نغمتان هادئتان: «دِن – دون» (مي ثم دو)
        "chime" -> Pair(1.55, { b: FloatArray, o: Double ->
            note(b, o, 659.25, 1.1, 3.2, doubleArrayOf(1.0, 1.0, 2.0, 0.3, 3.0, 0.1))
            note(b, o + 0.45, 523.25, 1.1, 3.2, doubleArrayOf(1.0, 1.0, 2.0, 0.3, 3.0, 0.1)) })
        // ثلاث نغمات خشبية صاعدة (دو مي صول)
        "marimba" -> Pair(0.85, { b: FloatArray, o: Double ->
            val p = doubleArrayOf(1.0, 1.0, 4.0, 0.18, 9.9, 0.04)
            note(b, o, 523.25, 0.5, 9.0, p, 0.004); note(b, o + 0.16, 659.25, 0.5, 9.0, p, 0.004); note(b, o + 0.32, 783.99, 0.55, 8.0, p, 0.004) })
        // رنين عميق كالوعاء الرنّان: نغمتان متقاربتان تتموّجان وتخفتان ببطء
        "bowl" -> Pair(2.3, { b: FloatArray, o: Double ->
            note(b, o, 392.0, 2.3, 1.7, doubleArrayOf(1.0, 0.7, 2.71, 0.22), 0.015)
            note(b, o, 393.4, 2.3, 1.7, doubleArrayOf(1.0, 0.5), 0.015) })
        // لحن الأجراس: أربع نغمات (مي دو ري صول)
        "melody" -> Pair(2.25, { b: FloatArray, o: Double ->
            val p = doubleArrayOf(1.0, 1.0, 2.0, 0.28, 3.0, 0.08)
            note(b, o, 659.25, 1.0, 3.8, p); note(b, o + 0.4, 523.25, 1.0, 3.8, p)
            note(b, o + 0.8, 587.33, 1.0, 3.8, p); note(b, o + 1.2, 392.0, 1.05, 3.0, p) })
        // النغمة الأساسية (كما كانت): 880 هرتز قصيرة
        else -> Pair(0.6, { b: FloatArray, o: Double -> note(b, o, 880.0, 0.6, 9.0, doubleArrayOf(1.0, 1.0), 0.02) })
    }

    @Synchronized
    private fun pcm(id: String, times: Int): ShortArray {
        val key = "$id/$times"
        cache[key]?.let { return it }
        val pt = pattern(id)
        // النغمة الأساسية تتكرر كل 0.7 ث كما كانت؛ والبقية بعد فاصل 0.3 ث
        val step = if (id == "classic" || BUILTIN.indexOf(id) < 0) 0.7 else pt.first + 0.3
        val total = step * (times - 1) + pt.first
        val buf = FloatArray((total * RATE).toInt() + 1)
        for (k in 0 until times) pt.second(buf, k * step)
        var peak = 0f
        for (x in buf) { val a = if (x < 0) -x else x; if (a > peak) peak = a }
        val g = if (peak > 0f) 0.8f / peak else 0f
        val out = ShortArray(buf.size)
        for (i in buf.indices) out[i] = (buf[i] * g * Short.MAX_VALUE).toInt().toShort()
        if (cache.size > 12) cache.clear()
        cache[key] = out
        return out
    }
}
