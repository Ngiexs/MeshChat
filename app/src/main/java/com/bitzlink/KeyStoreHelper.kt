package com.bitzlink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreHelper(ctx: Context) {

    private val available: Boolean
    private val key: SecretKey?

    init {
        var k: SecretKey? = null
        var ok = false
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val entry = ks.getEntry(ALIAS, null)
            k = if (entry is KeyStore.SecretKeyEntry) entry.secretKey
            else {
                val kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
                kg.init(spec)
                kg.generateKey()
            }
            ok = k != null
        } catch (e: Exception) {
            Log.w(TAG, "KeyStore unavailable: ${e.message}")
        }
        this.key = k
        this.available = ok
        Log.i(TAG, "KeyStoreHelper ready=$available")
    }

    fun isAvailable(): Boolean = available

    fun isWrapped(s: String?): Boolean = s != null && s.startsWith(PREFIX)

    fun encrypt(plaintext: String?): String {
        if (plaintext.isNullOrEmpty()) return ""
        if (!available || key == null) return plaintext
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = c.iv
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Encrypt failed: ${e.message}")
            plaintext
        }
    }

    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        if (!available || key == null) {
            Log.w(TAG, "Wrapped value but Keystore unavailable")
            return ""
        }
        return try {
            val all = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (all.size < IV_LEN) return ""
            val iv = all.copyOfRange(0, IV_LEN)
            val ct = all.copyOfRange(IV_LEN, all.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed: ${e.message}")
            ""
        }
    }

    companion object {
        private const val TAG = "MeshTrace"
        private const val ALIAS = "meshchat_master_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val PREFIX = "enc:"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}