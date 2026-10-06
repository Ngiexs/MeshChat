package com.bitzlink;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Replica {

    private static final String TAG = "Replica";
    private static final int MAX_MESSAGES = 1000;
    private static final int SEEN_CAP     = 3000;
    private static final String FILE_NAME = "replica.log";

    private final File file;
    private final Deque<String> messages = new ArrayDeque<String>();
    private final Set<String> seenIds    = new HashSet<String>();

    public Replica(File appFilesDir) {
        this(appFilesDir, "");
    }

    public Replica(File appFilesDir, String groupName) {
        String safe = GroupRegistry.sanitize(groupName);
        File target;
        if (safe.length() == 0) {
            target = new File(appFilesDir, FILE_NAME);
        } else {
            File dir = new File(appFilesDir,
                    "groups" + File.separator + safe);
            if (!dir.exists()) dir.mkdirs();
            target = new File(dir, FILE_NAME);
        }
        this.file = target;
        loadFromDisk();
    }

    public synchronized void append(String wireLine) {
        if (wireLine == null || wireLine.length() == 0) return;
        messages.addLast(wireLine);
        while (messages.size() > MAX_MESSAGES) messages.removeFirst();
        saveToDisk();
    }

    public synchronized boolean contains(String wireLine) {
        if (wireLine == null) return false;
        return messages.contains(wireLine);
    }

    public synchronized boolean seen(String msgId) {
        if (msgId == null || msgId.length() == 0) return false;
        if (seenIds.contains(msgId)) return true;
        seenIds.add(msgId);
        if (seenIds.size() > SEEN_CAP) {
            Iterator<String> it = seenIds.iterator();
            int toRemove = SEEN_CAP / 2;
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
        }
        return false;
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<String>(messages);
    }

    public synchronized int size() { return messages.size(); }

    public synchronized void clear() {
        messages.clear();
        seenIds.clear();
        saveToDisk();
    }

    public synchronized void removeById(String msgId) {
        if (msgId == null || msgId.length() == 0) return;
        boolean changed = false;
        Iterator<String> it = messages.iterator();
        while (it.hasNext()) {
            String line = it.next();
            String[] p = Protocol.unpack(line);
            if (p.length >= 3 && msgId.equals(p[1])) {
                it.remove();
                changed = true;
            }
        }
        if (changed) saveToDisk();
    }

    private void loadFromDisk() {
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0) {
                    messages.addLast(line);
                    if (messages.size() > MAX_MESSAGES) messages.removeFirst();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e.getMessage());
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { }
        }
    }

    private void saveToDisk() {
        FileWriter w = null;
        try {
            w = new FileWriter(file);
            for (String m : messages) {
                w.write(m);
                w.write("\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + e.getMessage());
        } finally {
            try { if (w != null) w.close(); } catch (Exception e) { }
        }
    }
}