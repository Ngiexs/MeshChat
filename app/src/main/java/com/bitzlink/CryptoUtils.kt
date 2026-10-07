package com.bitzlink

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val RNG = SecureRandom()

    @JvmStatic @Throws(Exception::class)
    fun deriveKey(passphrase: String, saltString: String): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(),
            saltString.toByteArray(Charsets.UTF_8),
            AppConfig.PBKDF2_ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    @JvmStatic @Throws(Exception::class)
    fun deriveKeyB64(passphrase: String, saltB64: String): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(),
            Base64.decode(saltB64, Base64.NO_WRAP),
            AppConfig.PBKDF2_ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    @JvmStatic @Throws(Exception::class)
    fun encrypt(plaintext: String, key: SecretKey): String {
        val iv = ByteArray(IV_LEN).also { RNG.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = iv + ct
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    @JvmStatic @Throws(Exception::class)
    fun decrypt(b64: String, key: SecretKey): String {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}