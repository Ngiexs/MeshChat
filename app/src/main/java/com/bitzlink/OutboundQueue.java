package com.bitzlink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class OutboundQueue {

    private static final int MAX = 100;

    private final Deque<String> q = new ArrayDeque<String>();

    public synchronized void enqueue(String line) {
        if (line == null || line.length() == 0) return;
        q.addLast(line);
        while (q.size() > MAX) q.removeFirst();
    }

    public synchronized List<String> drain() {
        List<String> out = new ArrayList<String>(q);
        q.clear();
        return out;
    }

    public synchronized int size() {
        return q.size();
    }

    public synchronized void clear() {
        q.clear();
    }
}