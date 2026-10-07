package com.bitzlink;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;

public class MeshServer {

    private static final String TAG   = "MeshServer";
    private static final String TRACE = "MeshTrace";

    public static final int MAX_CLIENTS = 30;
    public static final int MAX_BACKUPS = 5;

    // Doubles as host keepalive: every tick broadcasts a PONG so
    // clients can refresh Heartbeat.lastSeen even when idle.
    private static final long CANDIDATE_REBROADCAST_MS = 15000L;
    private static final int  MSGID_MAP_CAP            = 500;
    private static final long TYPING_MIN_INTERVAL_MS   = 500L;

    public interface Listener {
        void onMessage(PeerState.ChatMessage m);
        void onStatus(String status);
        void onTyping(String who);
        void onBackupsChanged(List<String> backups);
        void onRoster(int clients, int maxClients, int backups, int maxBackups);
        void onNames(List<String> names);
    }

    private final String myName;
    private final GroupKeyHolder keyHolder;
    private final int listenPort;
    private final Listener listener;
    private final Replica replica;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private final Map<String, PrintWriter> clients =
            new ConcurrentHashMap<String, PrintWriter>();

    private final Map<String, String> backups =
            new ConcurrentHashMap<String, String>();

    private final Map<String, String> msgSenders =
            new ConcurrentHashMap<String, String>();

    private final Map<String, Long> lastTypingAt =
            new ConcurrentHashMap<String, Long>();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ServerSocket serverSocket;

    public MeshServer(String myName, GroupKeyHolder keyHolder, int listenPort,
                      Listener listener, Replica replica) {
        this.myName     = myName;
        this.keyHolder  = keyHolder;
        this.listenPort = listenPort;
        this.listener   = listener;
        this.replica    = replica;
    }

    public void start() {
        pool.submit(new Runnable() {
            public void run() { acceptLoop(); }
        });
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() { rebroadcastCandidates(); }
        }, CANDIDATE_REBROADCAST_MS, CANDIDATE_REBROADCAST_MS,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); }
        catch (Exception e) { }
        for (PrintWriter w : clients.values()) {
            try { w.close(); } catch (Exception e) { }
        }
        clients.clear();
        backups.clear();
        msgSenders.clear();
        lastTypingAt.clear();
        pool.shutdownNow();
        scheduler.shutdownNow();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(listenPort);
            Log.i(TRACE, "MeshServer: bound successfully on " + listenPort);
            if (listener != null)
                listener.onStatus("Host listening on " + listenPort);
            while (running.get()) {
                final Socket s = serverSocket.accept();
                pool.submit(new Runnable() {
                    public void run() { handleClient(s); }
                });
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshServer acceptLoop stopped: " + e.getMessage());
        }
    }

    private void handleClient(Socket s) {
        BufferedReader in  = null;
        PrintWriter    out = null;
        String  clientName = null;
        boolean accepted   = false;
        try {
            s.setTcpNoDelay(true);
            in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out = new PrintWriter(s.getOutputStream(), true);

            String line;
            while (running.get() && (line = in.readLine()) != null) {
                try {
                    String[] p = Protocol.unpack(line);
                    if (p.length < 1) continue;
                    String type = p[0];

                    if (Protocol.HELLO.equals(type)) {
                        if (p.length >= 2) {
                            synchronized (out) {
                                String gkB64 = keyHolder.toBase64();
                                if (gkB64 != null && gkB64.length() > 0) {
                                    out.println(Protocol.pack(
                                            Protocol.GROUPKEY, gkB64));
                                }

                                String proposed = p[1];
                                boolean full;
                                synchronized (clients) {
                                    full = clients.size() >= MAX_CLIENTS
                                            && !clients.containsKey(proposed);
                                    if (!full) {
                                        clients.put(proposed, out);
                                        accepted   = true;
                                        clientName = proposed;
                                    }
                                }
                                if (full) {
                                    out.println(Protocol.pack(Protocol.FULL,
                                            clients.size() + "/" + MAX_CLIENTS));
                                    return;
                                }

                                out.println(Protocol.pack(Protocol.ROSTER,
                                        clients.size() + "|" + MAX_CLIENTS + "|"
                                        + backups.size() + "|" + MAX_BACKUPS));
                                Log.i(TRACE, "MeshServer: client HELLO from "
                                        + clientName + " (" + clients.size()
                                        + "/" + MAX_CLIENTS + ")");
                                if (listener != null) {
                                    listener.onStatus(clientName + " joined ("
                                            + clients.size() + "/"
                                            + MAX_CLIENTS + ")");
                                }
                                out.println("PONG|" + myName);
                                replayTo(out);
                            }
                            broadcastRoster();
                        }

                    } else if (Protocol.BACKUP_JOIN.equals(type)) {
                        if (p.length >= 2) {
                            String backupName = p[1];
                            int rank = -1;
                            boolean backupFull;
                            long joinedAt = System.currentTimeMillis();
                            synchronized (backups) {
                                backupFull = backups.size() >= MAX_BACKUPS
                                        && !backups.containsKey(backupName);
                                if (!backupFull) {
                                    if (!backups.containsKey(backupName)) {
                                        rank = backups.size();
                                        backups.put(backupName,
                                                "" + joinedAt);
                                    } else {
                                        joinedAt = Long.parseLong(
                                                backups.get(backupName));
                                        rank = new ArrayList<String>(
                                                backups.keySet())
                                                .indexOf(backupName);
                                    }
                                }
                            }
                            if (backupFull) {
                                safeWrite(out, Protocol.pack(
                                        Protocol.BACKUP_FULL,
                                        backups.size() + "/" + MAX_BACKUPS));
                            } else {
                                safeWrite(out, Protocol.pack(
                                        Protocol.BACKUP_RANK,
                                        rank + "|" + joinedAt));
                                for (Map.Entry<String, String> e
                                        : backups.entrySet()) {
                                    safeWrite(out, Protocol.pack(
                                            Protocol.CANDIDATE,
                                            e.getKey() + "|" + e.getValue()));
                                }
                                broadcastExcept(backupName,
                                        Protocol.pack(Protocol.CANDIDATE,
                                                backupName + "|" + joinedAt));
                                if (listener != null) {
                                    listener.onStatus(backupName
                                            + " is backup #" + rank);
                                    listener.onBackupsChanged(
                                            new ArrayList<String>(
                                                    backups.keySet()));
                                }
                                broadcastRoster();
                            }
                        }

                    } else if (Protocol.CANDIDATE.equals(type)) {
                        broadcastExcept(clientName, line);

                    } else if (Protocol.MSG.equals(type)) {
                        String msgId  = null;
                        String cipher = null;
                        if (p.length >= 3) {
                            msgId  = p[1];
                            cipher = p[2];
                        } else if (p.length == 2) {
                            cipher = p[1];
                        }

                        if (msgId != null && replica.seen(msgId)) {
                            safeWrite(out, Protocol.pack(
                                    Protocol.ACK, msgId, "_host"));
                            return;
                        }

                        replica.append(line);

                        if (msgId != null) {
                            msgSenders.put(msgId, clientName);
                            trimMsgSenders();
                            safeWrite(out, Protocol.pack(
                                    Protocol.ACK, msgId, "_host"));
                        }

                        broadcastExcept(clientName, line);

                        if (cipher != null) {
                            SecretKey k = keyHolder.get();
                            if (k == null) {
                                Log.w(TRACE,
                                        "MeshServer: MSG before key ready");
                            } else {
                                try {
                                    String plain =
                                            CryptoUtils.decrypt(cipher, k);
                                    String[] parts = plain.split("\u0001", -1);
                                    PeerState.ChatMessage cm = null;
                                    if (parts.length >= 7) {
                                        long ts;
                                        try { ts = Long.parseLong(parts[2]); }
                                        catch (NumberFormatException e) {
                                            ts = System.currentTimeMillis();
                                        }
                                        long ttl;
                                        try { ttl = Long.parseLong(parts[6]); }
                                        catch (NumberFormatException e) {
                                            ttl = 0L;
                                        }
                                        long exp = ttl > 0 ? ts + ttl : 0L;
                                        String rs = parts[3].isEmpty()
                                                ? null : parts[3];
                                        String rb = parts[4].isEmpty()
                                                ? null : parts[4];
                                        cm = new PeerState.ChatMessage(
                                                parts[1], parts[5],
                                                false, ts, rs, rb, exp);
                                    } else if (parts.length == 6) {
                                        long ts;
                                        try { ts = Long.parseLong(parts[2]); }
                                        catch (NumberFormatException e) {
                                            ts = System.currentTimeMillis();
                                        }
                                        String rs = parts[3].isEmpty()
                                                ? null : parts[3];
                                        String rb = parts[4].isEmpty()
                                                ? null : parts[4];
                                        cm = new PeerState.ChatMessage(
                                                parts[1], parts[5],
                                                false, ts, rs, rb, 0L);
                                    } else if (parts.length == 5) {
                                        long ts;
                                        try { ts = Long.parseLong(parts[2]); }
                                        catch (NumberFormatException e) {
                                            ts = System.currentTimeMillis();
                                        }
                                        long ttl;
                                        try { ttl = Long.parseLong(parts[4]); }
                                        catch (NumberFormatException e) {
                                            ttl = 0L;
                                        }
                                        long exp = ttl > 0 ? ts + ttl : 0L;
                                        cm = new PeerState.ChatMessage(
                                                parts[1], parts[3],
                                                false, ts, null, null, exp);
                                    } else if (parts.length >= 4) {
                                        long ts;
                                        try { ts = Long.parseLong(parts[2]); }
                                        catch (NumberFormatException e) {
                                            ts = System.currentTimeMillis();
                                        }
                                        cm = new PeerState.ChatMessage(
                                                parts[1], parts[3],
                                                false, ts);
                                    } else if (parts.length >= 3) {
                                        cm = new PeerState.ChatMessage(
                                                parts[1], parts[2], false);
                                    }
                                    if (cm != null && listener != null) {
                                        listener.onMessage(cm);
                                    }
                                } catch (Exception e) { }
                            }
                        }

                    } else if (Protocol.ACK.equals(type)) {
                        if (p.length >= 3) {
                            String ackedMsgId = p[1];
                            String acker      = p[2];
                            String origSender = msgSenders.get(ackedMsgId);
                            if (origSender != null
                                    && !origSender.equals(clientName)) {
                                PrintWriter origOut = clients.get(origSender);
                                if (origOut != null) {
                                    safeWrite(origOut, Protocol.pack(
                                            Protocol.ACK,
                                            ackedMsgId, acker));
                                }
                            }
                        }

                    } else if (Protocol.TYPING.equals(type)) {
                        long now = System.currentTimeMillis();
                        Long prev = lastTypingAt.get(clientName);
                        if (prev != null
                                && now - prev.longValue()
                                   < TYPING_MIN_INTERVAL_MS) {
                            continue;
                        }
                        lastTypingAt.put(clientName, Long.valueOf(now));

                        broadcastExcept(clientName, line);
                        if (p.length >= 2 && p[1].length() > 0
                                && listener != null) {
                            listener.onTyping(p[1]);
                        }

                    } else if (Protocol.PING.equals(type)) {
                        safeWrite(out, "PONG|" + myName);

                    } else if (Protocol.CLAIM.equals(type)) {
                        if (p.length >= 3) {
                            running.set(false);
                            try { serverSocket.close(); }
                            catch (Exception e) { }
                            return;
                        }
                    }
                } catch (Exception inner) {
                    Log.w(TRACE, "MeshServer line parse failed: "
                            + inner.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshServer client session ended");
        } finally {
            if (accepted && clientName != null) {
                PrintWriter current = clients.get(clientName);
                if (current == out) {
                    clients.remove(clientName);
                    boolean wasBackup = backups.remove(clientName) != null;
                    lastTypingAt.remove(clientName);

                    Iterator<Map.Entry<String, String>> it =
                            msgSenders.entrySet().iterator();
                    while (it.hasNext()) {
                        if (clientName.equals(it.next().getValue())) {
                            it.remove();
                        }
                    }

                    if (listener != null) {
                        listener.onStatus(clientName + " left ("
                                + clients.size() + "/" + MAX_CLIENTS + ")");
                        if (wasBackup) {
                            listener.onBackupsChanged(
                                    new ArrayList<String>(backups.keySet()));
                        }
                    }
                    broadcastRoster();
                }
            }
            try { if (s != null) s.close(); } catch (Exception e) { }
        }
    }

    private void trimMsgSenders() {
        if (msgSenders.size() <= MSGID_MAP_CAP) return;
        Iterator<String> it = msgSenders.keySet().iterator();
        int toRemove = msgSenders.size() / 2;
        int removed = 0;
        while (it.hasNext() && removed < toRemove) {
            it.next();
            it.remove();
            removed++;
        }
    }

    private void replayTo(PrintWriter out) {
        List<String> hist = replica.snapshot();
        out.println(Protocol.pack(Protocol.REPLAY_BEGIN, "" + hist.size()));
        for (String h : hist) out.println(h);
    }

    private void rebroadcastCandidates() {
        if (!running.get()) return;
        for (Map.Entry<String, String> e : backups.entrySet()) {
            broadcastAll(Protocol.pack(Protocol.CANDIDATE,
                    e.getKey() + "|" + e.getValue()));
        }
        // Keepalive to every client: refreshes their Heartbeat.lastSeen
        // for the host, so a host with zero backups isn't falsely
        // declared dead when the app is idle.
        broadcastAll(Protocol.pack(Protocol.PONG, myName));
    }

    private void broadcastRoster() {
        int c = clients.size();
        int b = backups.size();
        String roster = c + "|" + MAX_CLIENTS + "|" + b + "|" + MAX_BACKUPS;
        broadcastAll(Protocol.pack(Protocol.ROSTER, roster));
        if (listener != null) {
            listener.onRoster(c, MAX_CLIENTS, b, MAX_BACKUPS);
        }
        List<String> names = new ArrayList<String>();
        if (myName != null) names.add(myName);
        for (String n : clients.keySet()) {
            if (!n.equals(myName)) names.add(n);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(names.get(i));
        }
        broadcastAll(Protocol.pack(Protocol.NAMES, sb.toString()));
        if (listener != null) listener.onNames(names);
    }

    private void broadcastExcept(final String skipName, final String line) {
        for (final Map.Entry<String, PrintWriter> e : clients.entrySet()) {
            if (e.getKey().equals(skipName)) continue;
            final PrintWriter w = e.getValue();
            pool.submit(new Runnable() {
                public void run() { safeWrite(w, line); }
            });
        }
    }

    private void broadcastAll(final String line) {
        for (final PrintWriter w : clients.values()) {
            pool.submit(new Runnable() {
                public void run() { safeWrite(w, line); }
            });
        }
    }

    public void sendFromHost(final String wireLine) {
        replica.append(wireLine);
        broadcastAll(wireLine);
    }

    public void announceLeaderGone(final String oldHost) {
        String line = Protocol.pack(Protocol.LEADER_GONE, oldHost);
        broadcastAll(line);
    }

    public List<String> getBackups() {
        return new ArrayList<String>(backups.keySet());
    }

    public int  getClientCount()   { return clients.size(); }
    public int  getBackupCount()   { return backups.size(); }
    public boolean isFull()        { return clients.size() >= MAX_CLIENTS; }
    public boolean areBackupsFull(){ return backups.size() >= MAX_BACKUPS; }

    private void safeWrite(PrintWriter w, String line) {
        if (w == null || line == null) return;
        try {
            w.println(line);
            if (w.checkError()) Log.w(TRACE, "MeshServer write error");
        } catch (Exception e) { }
    }
}