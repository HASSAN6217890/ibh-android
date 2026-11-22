package com.nafahatsy.ibh

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * أصوات الجرس وتنبيهات الدوام (التطبيق 1.3.0).
 * - خمس نغمات مدمجة تُركَّب في الذاكرة (PCM) بلا ملفات: لكل منها طابع مختلف، بصعود ناعم وخفوت طبيعي فلا تكون حادة.
 * - أو نغمة من الهاتف: تُنسخ عند اختيارها إلى ذاكرة التطبيق الخاصة (filesDir/tones/bell.* وalert.*، حتى 5 م.ب) ويُشغَّل
 *   دائماً من النسخة المحلية، فلا يتوقف الرنين على ملف سحابي أو بطيء أو إذن يزول. ونغمة النظام التي تعذّر نسخها تُشغَّل
 *   بتحضير غير متزامن، وإن لم تجهز خلال ثانية فالنغمة الأساسية.
 * - الجرس على مسار المنبّه (USAGE_ALARM) فيرنّ ولو كان الهاتف صامتاً، كما كان. وتنبيهات الدوام على مسار الإشعارات
 *   (USAGE_NOTIFICATION_EVENT).
 * - المدة: النغمة المدمجة تُكرَّر بعدد الرنّات كما كان؛ ونغمة الهاتف تُقطع بعد 3 ثوانٍ لكل رنّة مع خفوت.
 * - ثلاث خانات تشغيل مستقلة (رنّة الجرس، تنبيه الدوام، المعاينة): المعاينة لا تقطع رنّة حقيقية، والإيقاف يخص خانته.
 * - إن تعذّر تشغيل نغمة الهاتف: النغمة الأساسية بدلها، ويُسجَّل (failed_at) لتنبيه صغير، ويُعاد fellBack لتقرير الرنّة.
 * الاختيار في تفضيلات كل خدمة: «tone» = b:<معرّف> | l:<اسم الملف المحلي> | r:<رابط نغمة نظام لم تُنسخ> | s: (نغمة إشعارات
 * الهاتف، للتنبيهات فقط، يشغّلها النظام من قناة الإشعار)، و«tone_name» اسمها المعروض.
 */
object BellTone {
    const val BELL = 0
    const val ALERT = 1
    /** خانة المعاينة («جرّب» ونافذة الاختيار) */
    private const val PREVIEW = 2
    private const val RATE = 22050
    private const val CUSTOM_MS_PER_RING = 3000L
    const val MAX_FILE = 5L * 1024 * 1024

    val BUILTIN = arrayOf("classic", "chime", "marimba", "bowl", "melody")
    val BUILTIN_NAMES = intArrayOf(R.string.tone_classic, R.string.tone_chime, R.string.tone_marimba, R.string.tone_bowl, R.string.tone_melody)

    class Played(val ms: Long, val fellBack: Boolean)

    /** معالج خاص بالنغمات: إيقاف خانة لا يمسّ مؤقتات غيرها ولا مؤقتات التطبيق */
    private val h = Handler(Looper.getMainLooper())
    private val players = arrayOfNulls<MediaPlayer>(3)
    private val ringtones = arrayOfNulls<Ringtone>(3)
    private val tracks = arrayOfNulls<AudioTrack>(3)
    /** رقم جيل لكل خانة: المؤقتات القديمة (خفوت، إيقاف، مهلة التحضير) لا تعمل بعد إيقاف الخانة أو تشغيل جديد فيها */
    private val gen = IntArray(3)
    private val cache = HashMap<String, ShortArray>()

    fun prefs(c: Context, target: Int): SharedPreferences = if (target == BELL) BellPlan.prefs(c) else RingSched.prefs(c)

    fun toneDir(c: Context): File = File(c.applicationContext.filesDir, "tones")

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

    /** يحفظ الاختيار؛ ويحذف نسخة الملف المحلية القديمة لهذه الخدمة إن تغيّرت */
    fun setChoice(c: Context, target: Int, value: String, name: String?) {
        val old = choice(c, target)
        prefs(c, target).edit().putString("tone", value).putString("tone_name", name ?: "").remove("failed_at").apply()
        if (old.startsWith("l:") && old != value) try { File(toneDir(c), old.substring(2)).delete() } catch (e: Exception) {}
    }

    fun takeFailure(c: Context, target: Int): Boolean {
        val p = prefs(c, target)
        if (!p.contains("failed_at")) return false
        p.edit().remove("failed_at").apply()
        return true
    }

    private fun markFailed(c: Context, target: Int) { prefs(c, target).edit().putLong("failed_at", System.currentTimeMillis()).apply() }

    // ---------------------------------------------------------------- التشغيل

    fun durationMs(c: Context, target: Int, times: Int): Long {
        val v = choice(c, target)
        val n = if (times < 1) 1 else times
        return if (v.startsWith("b:")) pcm(v.substring(2), n).size * 1000L / RATE else CUSTOM_MS_PER_RING * n
    }

    /** رنّة حقيقية للخدمة. لا يرمي استثناء، ولا يحجز الخيط أكثر من تحضير ملف محلي صغير. «s:» لا يشغّل شيئاً */
    fun play(c: Context, target: Int, times: Int): Played {
        val v = choice(c, target)
        val n = if (times < 1) 1 else times
        if (v.startsWith("s:")) return Played(0L, false)
        if (v.startsWith("b:")) return Played(playPcm(target, v.substring(2), n, target), false)
        if (v.startsWith("l:")) {
            if (playLocal(c, target, File(toneDir(c), v.substring(2)), n, target)) return Played(CUSTOM_MS_PER_RING * n, false)
        } else if (v.startsWith("r:")) {
            if (playRemote(c, target, v.substring(2), n, target)) return Played(CUSTOM_MS_PER_RING * n, false)
        }
        markFailed(c, target)
        return Played(playPcm(target, "classic", n, target), true)
    }

    /** معاينة (خانة مستقلة): رنّة واحدة تتوقف وحدها. يعيد false إن تعذّرت نغمة الهاتف */
    fun preview(c: Context, target: Int, value: String, times: Int = 1): Boolean {
        stopPreview()
        if (value.startsWith("b:")) { playPcm(PREVIEW, value.substring(2), times, target); return true }
        if (value.startsWith("s:")) {
            return try {
                val r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) ?: return false
                r.audioAttributes = attrs(target)
                ringtones[PREVIEW] = r
                r.play()
                val g = gen[PREVIEW]
                h.postDelayed({ if (gen[PREVIEW] == g) stopSlot(PREVIEW) }, CUSTOM_MS_PER_RING)
                true
            } catch (e: Exception) { false }
        }
        if (value.startsWith("l:")) return playLocal(c, PREVIEW, File(toneDir(c), value.substring(2)), times, target)
        if (value.startsWith("r:")) return playRemote(c, PREVIEW, value.substring(2), times, target)
        return false
    }

    fun stopPreview() = stopSlot(PREVIEW)

    private fun stopSlot(s: Int) {
        gen[s]++
        val p = players[s]; players[s] = null
        try { p?.stop() } catch (e: Exception) {}
        try { p?.release() } catch (e: Exception) {}
        val r = ringtones[s]; ringtones[s] = null
        try { r?.stop() } catch (e: Exception) {}
        val t = tracks[s]; tracks[s] = null
        try { t?.stop() } catch (e: Exception) {}
        try { t?.release() } catch (e: Exception) {}
    }

    private fun attrs(target: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (target == BELL) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** ملف في ذاكرة التطبيق (5 م.ب على الأكثر): تحضيره متزامن وسريع */
    private fun playLocal(c: Context, slot: Int, f: File, times: Int, target: Int): Boolean {
        if (!f.isFile || f.length() == 0L) return false
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs(target))
            mp.setDataSource(f.absolutePath)
            mp.isLooping = true
            mp.prepare()
            start(slot, mp, times)
            true
        } catch (e: Exception) { false }
    }

    /**
     * نغمة نظام لم تُنسخ: تحضير غير متزامن، فإن لم تجهز خلال ثانية (أو فشلت) تُشغَّل النغمة الأساسية بدلها ويُسجَّل الفشل.
     * يعيد false فوراً فقط إن تعذّر بدء التحضير أصلاً.
     */
    private fun playRemote(c: Context, slot: Int, s: String, times: Int, target: Int): Boolean {
        return try {
            val uri = Uri.parse(s)
            stopSlot(slot)
            val g = gen[slot]
            val mp = MediaPlayer()
            players[slot] = mp
            mp.setAudioAttributes(attrs(target))
            mp.setDataSource(c.applicationContext, uri)
            mp.isLooping = true
            val failed = Runnable {
                if (gen[slot] != g) return@Runnable
                stopSlot(slot)
                if (slot != PREVIEW) markFailed(c, target)
                playPcm(slot, "classic", times, target)
            }
            mp.setOnPreparedListener { p -> if (gen[slot] == g) { h.removeCallbacks(failed); start(slot, p, times, false) } }
            mp.setOnErrorListener { _, _, _ -> h.removeCallbacks(failed); h.post(failed); true }
            h.postDelayed(failed, 1000L)
            mp.prepareAsync()
            true
        } catch (e: Exception) { stopSlot(slot); false }
    }

    private fun start(slot: Int, mp: MediaPlayer, times: Int, fresh: Boolean = true) {
        if (fresh) stopSlot(slot)
        players[slot] = mp
        val g = gen[slot]
        mp.setOnErrorListener { p, _, _ -> if (players[slot] === p) stopSlot(slot); true }
        mp.start()
        val limit = CUSTOM_MS_PER_RING * times
        // خفوت في آخر 600 مللي ثم إيقاف
        for (i in 1..6) h.postDelayed({
            if (gen[slot] == g) try {
                val vol = 1f - i / 6f
                mp.setVolume(vol, vol)
                if (i == 6) stopSlot(slot)
            } catch (e: Exception) {}
        }, limit - 600L + 100L * i)
    }

    private fun playPcm(slot: Int, id: String, times: Int, target: Int): Long {
        return try {
            val data = pcm(id, times)
            val fmt = AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack(attrs(target), fmt, data.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(data, 0, data.size)
            val ms = data.size * 1000L / RATE
            if (slot == PREVIEW) {
                stopSlot(PREVIEW)
                tracks[PREVIEW] = t
                val g = gen[PREVIEW]
                t.play()
                h.postDelayed({ if (gen[PREVIEW] == g) stopSlot(PREVIEW) }, ms + 300)
            } else {
                // الرنّات الحقيقية لا تقطع بعضها: كل مسار يُحرَّر بعد انتهائه
                t.play()
                h.postDelayed({ try { t.release() } catch (e: Exception) {} }, ms + 300)
            }
            ms
        } catch (e: Exception) { 0L }
    }

    // ---------------------------------------------------------------- تركيب النغمات

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
