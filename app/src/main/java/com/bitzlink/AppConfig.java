package com.bitzlink;

public class AppConfig {

    public static final int DEFAULT_PORT = 8888;
    public static final int SOCKS_PORT   = 9050;

    public static final String TEST_PASSPHRASE =
            "bitzlink-2026-secret-do-not-share";
    public static final String TEST_SALT_B64 =
            "Yml0emxpbmsyMDI2c2FsdCE=";

    public static final int PBKDF2_ITERATIONS = 200000;
    public static final String GROUP_SALT_PREFIX = "bitzlink-group-v2:";

    // Ephemeral message TTL options, in milliseconds.
    public static final long TTL_OFF  = 0L;
    public static final long TTL_30S  = 30_000L;
    public static final long TTL_5M   = 300_000L;
    public static final long TTL_1H   = 3_600_000L;
    public static final long TTL_1D   = 86_400_000L;

    public static String ttlLabel(long ttlMs) {
        if (ttlMs <= 0) return "Off";
        if (ttlMs == TTL_30S) return "30 seconds";
        if (ttlMs == TTL_5M)  return "5 minutes";
        if (ttlMs == TTL_1H)  return "1 hour";
        if (ttlMs == TTL_1D)  return "1 day";
        return ttlMs + " ms";
    }
}