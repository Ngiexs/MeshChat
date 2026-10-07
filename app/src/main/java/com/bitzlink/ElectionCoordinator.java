package com.bitzlink;

import android.os.SystemClock;
import android.util.Log;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ElectionCoordinator {

    private static final String TAG = "Election";

    public static final long MIN_AGE_MS = 5000L;
    public static final long PRUNE_MS   = 300000L;

    public static class Candidate {
        public final String name;
        public final long   joinedAt;
        public final long   firstHeardAt;
        public volatile long lastSeen;

        public Candidate(String name, long joinedAt) {
            this.name         = name;
            this.joinedAt     = joinedAt;
            this.firstHeardAt = SystemClock.elapsedRealtime();
            this.lastSeen     = System.currentTimeMillis();
        }
    }

    private final Map<String, Candidate> candidates =
            new ConcurrentHashMap<String, Candidate>();

    public void register(Candidate c) {
        if (c == null || c.name == null) return;
        Candidate existing = candidates.get(c.name);
        if (existing != null) {
            existing.lastSeen = System.currentTimeMillis();
        } else {
            candidates.put(c.name, c);
        }
    }

    public void remove(String name) {
        if (name == null) return;
        candidates.remove(name);
    }

    public void pruneDead() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Candidate>> it =
                candidates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Candidate> e = it.next();
            long age = now - e.getValue().lastSeen;
            if (age > PRUNE_MS) it.remove();
        }
    }

    public String pickWinner() {
        long localNow = SystemClock.elapsedRealtime();

        Candidate best = null;
        for (Candidate c : candidates.values()) {
            long localAge = localNow - c.firstHeardAt;
            if (localAge < MIN_AGE_MS) continue;

            if (best == null) { best = c; continue; }
            if (c.joinedAt < best.joinedAt) { best = c; continue; }
            if (c.joinedAt > best.joinedAt) continue;
            if (c.name.compareTo(best.name) < 0) best = c;
        }

        if (best == null) return null;
        return best.name;
    }

    public int  size()  { return candidates.size(); }
    public void clear() { candidates.clear(); }
}