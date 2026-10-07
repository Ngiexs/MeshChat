package com.bitzlink

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

class ElectionCoordinator {

    class Candidate @JvmOverloads constructor(
        val name: String,
        val joinedAt: Long,
        @Volatile var lastSeen: Long = System.currentTimeMillis()
    ) {
        val firstHeardAt: Long = SystemClock.elapsedRealtime()
    }

    private val candidates = ConcurrentHashMap<String, Candidate>()

    fun register(c: Candidate?) {
        if (c == null || c.name.isEmpty()) return
        val existing = candidates[c.name]
        if (existing != null) existing.lastSeen = System.currentTimeMillis()
        else candidates[c.name] = c
    }

    fun remove(name: String?) {
        if (name == null) return
        candidates.remove(name)
    }

    fun pruneDead() {
        val now = System.currentTimeMillis()
        val it = candidates.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > PRUNE_MS) it.remove()
        }
    }

    fun pickWinner(): String? {
        val localNow = SystemClock.elapsedRealtime()
        var best: Candidate? = null
        for (c in candidates.values) {
            if (localNow - c.firstHeardAt < MIN_AGE_MS) continue
            if (best == null) { best = c; continue }
            val b = best
            if (c.joinedAt < b.joinedAt) { best = c; continue }
            if (c.joinedAt > b.joinedAt) continue
            if (c.name < b.name) best = c
        }
        return best?.name
    }

    fun size(): Int = candidates.size
    fun clear() { candidates.clear() }

    companion object {
        const val MIN_AGE_MS = 5000L
        const val PRUNE_MS = 300000L
    }
}