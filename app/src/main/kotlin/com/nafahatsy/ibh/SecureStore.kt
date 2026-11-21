package com.nafahatsy.ibh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * حفظ مشفّر لقيم قصيرة (رمز الجلسة ورمز الجرس): مفتاح AES-256-GCM يولَّد داخل Android Keystore ولا يخرج منه،
 * والنص المشفّر مع IV في تفضيلات خاصة بالتطبيق. إن تعطّل Keystore على جهاز ما (يحدث في بعض الأجهزة القديمة)
 * لا يُحفظ شيء نصاً صريحاً أبداً: تبقى الجلسة في الذاكرة فقط، ويدخل المستخدم من جديد بعد إغلاق التطبيق.
 */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ibh_secure", Context.MODE_PRIVATE)

    fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = c.iv
            val blob = ByteArray(1 + iv.size + enc.size)
            blob[0] = iv.size.toByte()
            System.arraycopy(iv, 0, blob, 1, iv.size)
            System.arraycopy(enc, 0, blob, 1 + iv.size, enc.size)
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
        }
    }

    fun get(name: String): String? {
        val s = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val ivLen = blob[0].toInt()
            if (ivLen < 12 || ivLen > 16 || blob.size <= 1 + ivLen) throw IllegalStateException()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen), Charsets.UTF_8)
        } catch (e: Exception) {
            prefs.edit().remove(name).apply()
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val k = ks.getKey(ALIAS, null)
        if (k is SecretKey) return k
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    companion object {
        private const val ALIAS = "ibh_store_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        const val SESSION = "session"
        const val BELL = "bell"
    }
}
