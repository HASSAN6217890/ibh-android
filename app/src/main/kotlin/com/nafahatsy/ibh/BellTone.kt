package com.nafahatsy.ibh

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** صوت الجرس: نغمة 880 هرتز مرتين (كـ beep(2) في صفحة الجرس بالمنصة)، على مسار المنبّه فيرنّ ولو كان الهاتف صامتاً */
object BellTone {
    private const val RATE = 22050

    private fun pcm(times: Int): ShortArray {
        val step = (RATE * 0.7).toInt()
        val len = (RATE * 0.6).toInt()
        val total = step * (times - 1) + len
        val out = ShortArray(total)
        for (b in 0 until times) {
            val off = b * step
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                // صعود سريع ثم خفوت أسي (كمنحنى الصفحة: 0.02 ث صعوداً، وخفوت حتى 0.55 ث)
                val env = if (t < 0.02) t / 0.02 else Math.exp(-(t - 0.02) * 9.0)
                val v = Math.sin(2.0 * Math.PI * 880.0 * t) * env * 0.85
                out[off + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    fun play(times: Int) {
        try {
            val data = pcm(if (times < 1) 1 else times)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(attrs, fmt, data.size * 2, AudioTrack.MODE_STATIC, 0)
            track.write(data, 0, data.size)
            track.play()
            val ms = data.size * 1000L / RATE + 300
            Thread { try { Thread.sleep(ms) } catch (e: InterruptedException) {}; try { track.release() } catch (e: Exception) {} }.start()
        } catch (e: Exception) {}
    }
}
