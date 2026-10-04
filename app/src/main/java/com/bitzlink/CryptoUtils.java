package com.bitzlink;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {

    private static final int IV_LEN   = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RNG = new SecureRandom();

    public static SecretKey deriveKey(String passphrase, String saltString)
            throws Exception {
        byte[] salt = saltString.getBytes("UTF-8");
        PBEKeySpec spec = new PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                AppConfig.PBKDF2_ITERATIONS,
                256);
        SecretKeyFactory f =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] raw = f.generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    public static SecretKey deriveKeyB64(String passphrase, String saltB64)
            throws Exception {
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        PBEKeySpec spec = new PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                AppConfig.PBKDF2_ITERATIONS,
                256);
        SecretKeyFactory f =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] raw = f.generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    public static String encrypt(String plaintext, SecretKey key)
            throws Exception {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext.getBytes("UTF-8"));

        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    public static String decrypt(String b64, SecretKey key) throws Exception {
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[all.length - IV_LEN];
        System.arraycopy(all, 0, iv, 0, IV_LEN);
        System.arraycopy(all, IV_LEN, ct, 0, ct.length);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, iv));
        return new String(c.doFinal(ct), "UTF-8");
    }
}