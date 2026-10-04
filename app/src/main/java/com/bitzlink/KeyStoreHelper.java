package com.bitzlink;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class KeyStoreHelper {

    private static final String TAG       = "MeshTrace";
    private static final String ALIAS     = "meshchat_master_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String PREFIX    = "enc:";
    private static final int    IV_LEN    = 12;
    private static final int    TAG_BITS  = 128;

    private final boolean available;
    private final SecretKey key;

    public KeyStoreHelper(Context ctx) {
        SecretKey k = null;
        boolean ok = false;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);

            KeyStore.Entry entry = ks.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                k = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            } else {
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT
                            | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build();
                kg.init(spec);
                k = kg.generateKey();
            }
            ok = (k != null);
        } catch (Exception e) {
            Log.w(TAG, "KeyStore unavailable: " + e.getMessage());
        }
        this.key = k;
        this.available = ok;
        Log.i(TAG, "KeyStoreHelper ready=" + available);
    }

    public boolean isAvailable() { return available; }

    public boolean isWrapped(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.length() == 0) return "";
        if (!available) return plaintext;
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] ct = c.doFinal(plaintext.getBytes("UTF-8"));
            byte[] iv = c.getIV();
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Encrypt failed: " + e.getMessage());
            return plaintext;
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.length() == 0) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        if (!available) {
            Log.w(TAG, "Wrapped value but Keystore unavailable");
            return "";
        }
        try {
            byte[] all = Base64.decode(
                    stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length < IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "Decrypt failed: " + e.getMessage());
            return "";
        }
    }
}