package com.bitzlink

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class Heartbeat(private val myName: String,
                private val cb: Callbacks?) {

    interface Callbacks {
        fun onHostDead()
        fun onHostAlive()
    }

    private val lastSeen = ConcurrentHashMap<String, Long>()

    @Volatile private var currentHost: String? = null
    @Volatile private var hostIsDead = false
    @Volatile private var trackingStartedAt = 0L

    fun setCurrentHost(hostName: String?) {
        currentHost = hostName
        trackingStartedAt = System.currentTimeMillis()
        hostIsDead = false
        lastSeen.clear()
        Log.i(TAG, "setCurrentHost=$hostName")
    }

    fun getCurrentHost(): String? = currentHost
    fun isHostDead(): Boolean = hostIsDead

    fun ageMs(nodeName: String?): Long {
        if (nodeName == null) return Long.MAX_VALUE
        val t = lastSeen[nodeName]
        if (t != null) return System.currentTimeMillis() - t
        if (trackingStartedAt > 0 && nodeName == currentHost)
            return System.currentTimeMillis() - trackingStartedAt
        return Long.MAX_VALUE
    }

    fun recordSeen(nodeName: String?) {
        if (nodeName == null) return
        lastSeen[nodeName] = System.currentTimeMillis()
    }

    fun forceDead() {
        val h = currentHost ?: return
        if (hostIsDead) return
        hostIsDead = true
        Log.w(TAG, "forceDead for $h")
        cb?.onHostDead()
    }

    fun tick() {
        val host = currentHost ?: return
        val now = System.currentTimeMillis()
        if (trackingStartedAt > 0 &&
            now - trackingStartedAt < GRACE_PERIOD_MS) return

        val lastHeard = lastSeen[host] ?: trackingStartedAt
        if (lastHeard <= 0) return

        val age = now - lastHeard
        val dead = age > (INTERVAL_MS * MISS_LIMIT)

        Log.i(TAG, "tick host=$host ageMs=$age dead=$dead wasDead=$hostIsDead")

        if (dead && !hostIsDead) {
            hostIsDead = true
            Log.w(TAG, "host declared dead: $host (silent ${age}ms)")
            cb?.onHostDead()
        } else if (!dead && hostIsDead) {
            hostIsDead = false
            Log.i(TAG, "host revived: $host")
            cb?.onHostAlive()
        }
    }

    fun clear() {
        lastSeen.clear()
        hostIsDead = false
        currentHost = null
        trackingStartedAt = 0
    }

    companion object {
        private const val TAG = "Heartbeat"
        const val INTERVAL_MS = 10000L
        const val MISS_LIMIT = 6L
        private const val GRACE_PERIOD_MS = 45000L
    }
}