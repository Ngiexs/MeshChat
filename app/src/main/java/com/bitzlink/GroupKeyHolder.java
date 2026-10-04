package com.bitzlink;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class GroupKeyHolder {

    public static final int KEY_BYTES = 32;

    private static final SecureRandom RNG = new SecureRandom();

    private volatile SecretKey key;

    public GroupKeyHolder(SecretKey initial) {
        this.key = initial;
    }

    public SecretKey get()    { return key; }
    public boolean   hasKey() { return key != null; }
    public void      set(SecretKey k) { this.key = k; }

    public void setFromBase64(String b64) {
        if (b64 == null || b64.length() == 0) return;
        try {
            byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
            if (raw.length != KEY_BYTES) return;
            this.key = new SecretKeySpec(raw, "AES");
        } catch (Exception e) { }
    }

    public String toBase64() {
        SecretKey k = key;
        if (k == null) return null;
        return Base64.encodeToString(k.getEncoded(), Base64.NO_WRAP);
    }

    public static SecretKey generate() {
        byte[] raw = new byte[KEY_BYTES];
        RNG.nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }
}