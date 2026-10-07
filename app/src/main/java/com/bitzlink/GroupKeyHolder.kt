package com.bitzlink

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class GroupKeyHolder(initial: SecretKey?) {

    @Volatile private var key: SecretKey? = initial

    fun get(): SecretKey? = key
    fun hasKey(): Boolean = key != null
    fun set(k: SecretKey?) { key = k }

    fun setFromBase64(b64: String?) {
        if (b64.isNullOrEmpty()) return
        try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            if (raw.size != KEY_BYTES) return
            key = SecretKeySpec(raw, "AES")
        } catch (_: Exception) {}
    }

    fun toBase64(): String? =
        key?.let { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }

    companion object {
        const val KEY_BYTES = 32
        private val RNG = SecureRandom()

        @JvmStatic
        fun generate(): SecretKey {
            val raw = ByteArray(KEY_BYTES)
            RNG.nextBytes(raw)
            return SecretKeySpec(raw, "AES")
        }
    }
}