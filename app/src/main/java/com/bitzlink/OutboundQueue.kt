package com.bitzlink

import java.util.ArrayDeque

class OutboundQueue {
    private val q = ArrayDeque<String>()

    @Synchronized fun enqueue(line: String?) {
        if (line.isNullOrEmpty()) return
        q.addLast(line)
        while (q.size > MAX) q.removeFirst()
    }

    @Synchronized fun drain(): List<String> {
        val out = ArrayList<String>(q)
        q.clear()
        return out
    }

    @Synchronized fun size(): Int = q.size
    @Synchronized fun clear() { q.clear() }

    companion object { private const val MAX = 100 }
}