package com.bitzlink

object AppConfig {
    const val VERSION_LABEL = "0.4.3_hotFix-beta1"
    const val DEFAULT_PORT = 8888
    const val SOCKS_PORT = 9050

    const val TEST_PASSPHRASE = "bitzlink-2026-secret-do-not-share"
    const val TEST_SALT_B64 = "Yml0emxpbmsyMDI2c2FsdCE="

    const val PBKDF2_ITERATIONS = 200000
    const val GROUP_SALT_PREFIX = "bitzlink-group-v2:"

    const val TTL_OFF = 0L
    const val TTL_30S = 30_000L
    const val TTL_5M = 300_000L
    const val TTL_1H = 3_600_000L
    const val TTL_1D = 86_400_000L

    @JvmStatic
    fun ttlLabel(ttlMs: Long): String = when {
        ttlMs <= 0L -> "Off"
        ttlMs == TTL_30S -> "30 seconds"
        ttlMs == TTL_5M -> "5 minutes"
        ttlMs == TTL_1H -> "1 hour"
        ttlMs == TTL_1D -> "1 day"
        else -> "$ttlMs ms"
    }
}