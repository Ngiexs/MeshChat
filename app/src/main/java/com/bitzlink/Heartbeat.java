package com.bitzlink;

import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Heartbeat {

    private static final String TAG = "Heartbeat";

    public static final long INTERVAL_MS = 10000;
    // Reduced from 15 (150s) to 6 (60s) so failover is testable.
    public static final long MISS_LIMIT  = 6;

    public interface Callbacks {
        void onHostDead();
        void onHostAlive();
    }

    private final Map<String, Long> lastSeen =
            new ConcurrentHashMap<String, Long>();

    private final String myName;
    private final Callbacks cb;

    private volatile String  currentHost       = null;
    private volatile boolean hostIsDead        = false;
    private volatile long    trackingStartedAt = 0;

    private static final long GRACE_PERIOD_MS = 45000;

    public Heartbeat(String myName, Callbacks cb) {
        this.myName = myName;
        this.cb = cb;
    }

    public void setCurrentHost(String hostName) {
        this.currentHost       = hostName;
        this.trackingStartedAt = System.currentTimeMillis();
        this.hostIsDead        = false;
        this.lastSeen.clear();
        Log.i(TAG, "setCurrentHost=" + hostName);
    }

    public String  getCurrentHost() { return currentHost; }
    public boolean isHostDead()     { return hostIsDead; }

    public long ageMs(String nodeName) {
        if (nodeName == null) return Long.MAX_VALUE;
        Long t = lastSeen.get(nodeName);
        if (t != null) {
            return System.currentTimeMillis() - t.longValue();
        }
        if (trackingStartedAt > 0 && nodeName.equals(currentHost)) {
            return System.currentTimeMillis() - trackingStartedAt;
        }
        return Long.MAX_VALUE;
    }

    public void recordSeen(String nodeName) {
        if (nodeName == null) return;
        lastSeen.put(nodeName, System.currentTimeMillis());
    }

    public void forceDead() {
        if (currentHost == null) return;
        if (hostIsDead) return;
        hostIsDead = true;
        Log.w(TAG, "forceDead for " + currentHost);
        if (cb != null) cb.onHostDead();
    }

    public void tick() {
        if (currentHost == null) return;
        long now = System.currentTimeMillis();
        if (trackingStartedAt > 0
                && (now - trackingStartedAt) < GRACE_PERIOD_MS) {
            // Still inside the startup grace window.
            return;
        }

        // CRITICAL FIX: if we have never heard from the host at all
        // (lastSeen has no entry), treat trackingStartedAt as the
        // moment we last heard from it. Otherwise a client that
        // connected to a dead hidden service — socket says connected,
        // nothing ever arrives — can never declare the host dead.
        Long t = lastSeen.get(currentHost);
        long lastHeard = (t == null)
                ? trackingStartedAt
                : t.longValue();
        if (lastHeard <= 0) return;

        long age = now - lastHeard;
        boolean dead = age > (INTERVAL_MS * MISS_LIMIT);

        Log.i(TAG, "tick host=" + currentHost
                + " ageMs=" + age
                + " dead=" + dead
                + " wasDead=" + hostIsDead);

        if (dead && !hostIsDead) {
            hostIsDead = true;
            Log.w(TAG, "host declared dead: " + currentHost
                    + " (silent " + age + "ms)");
            if (cb != null) cb.onHostDead();
        } else if (!dead && hostIsDead) {
            hostIsDead = false;
            Log.i(TAG, "host revived: " + currentHost);
            if (cb != null) cb.onHostAlive();
        }
    }

    public void clear() {
        lastSeen.clear();
        hostIsDead        = false;
        currentHost       = null;
        trackingStartedAt = 0;
    }
}