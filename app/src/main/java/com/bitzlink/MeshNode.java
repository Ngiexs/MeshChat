package com.bitzlink;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;

public class MeshNode {

    private static final String TAG   = "MeshNode";
    private static final String TRACE = "MeshTrace";

    // 6 parallel attempts, 6s timeout each, 0.5s backoff between rounds.
    // Failing round costs ~6.5s instead of the old ~14s.
    private static final int  ATTEMPTS_PER_ROUND = 6;
    private static final int  ATTEMPT_TIMEOUT_MS = 6000;
    private static final long BACKOFF_MS         = 500L;

    public interface Listener {
        void onMessage(PeerState.ChatMessage m);
        void onStatus(String status);
        void onTyping(String who);
        void onRoster(int clients, int maxClients, int backups, int maxBackups);
        void onNames(List<String> names);
        void onAck(String msgId, String acker);
        void onClearHistory();
    }

    private final String myName;
    private final GroupKeyHolder keyHolder;
    private final Context ctx;
    private final Listener listener;
    private final Replica replica;
    private final Heartbeat heartbeat;
    private final ElectionCoordinator election;
    private final boolean wantBackup;

    private final String host;
    private final int port;

    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean running;
    private volatile boolean fatalError = false;
    private volatile int backupRank = -1;
    private Thread keepaliveThread;

    private final OutboundQueue pending = new OutboundQueue();

    public MeshNode(String myName, GroupKeyHolder keyHolder, Context ctx,
                    String host, int port,
                    Listener listener, Replica replica,
                    Heartbeat heartbeat, ElectionCoordinator election,
                    boolean wantBackup) {
        this.myName     = myName;
        this.keyHolder  = keyHolder;
        this.ctx        = ctx;
        this.host       = host;
        this.port       = port;
        this.listener   = listener;
        this.replica    = replica;
        this.heartbeat  = heartbeat;
        this.election   = election;
        this.wantBackup = wantBackup;
    }

    public void start() {
        running = true;
        if (listener != null)
            listener.onStatus("Connecting to " + shortHost(host) + "...");
        Thread t = new Thread(new Runnable() {
            public void run() { connectWithRetries(); }
        }, "mesh-node");
        t.setDaemon(true);
        t.start();
    }

    private void connectWithRetries() {
        int round = 0;
        while (running && !fatalError) {
            round++;
            if (listener != null)
                listener.onStatus("Connecting (round " + round + ")");
            Log.i(TRACE, "MeshNode: round " + round);

            Socket winner = raceConnections(round);
            if (winner == null) {
                if (!running || fatalError) return;
                if (listener != null)
                    listener.onStatus("Retry in " + (BACKOFF_MS) + "ms...");
                try { Thread.sleep(BACKOFF_MS); }
                catch (InterruptedException e) { return; }
                continue;
            }

            socket = winner;
            try {
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                out = new PrintWriter(socket.getOutputStream(), true);
                out.println(Protocol.pack(Protocol.HELLO, myName));
                if (wantBackup) {
                    out.println(Protocol.pack(Protocol.BACKUP_JOIN, myName));
                    if (election != null) {
                        long joinedAt = System.currentTimeMillis();
                        election.register(
                                new ElectionCoordinator.Candidate(
                                        myName, joinedAt));
                    }
                }
                out.flush();

                List<String> queued = pending.drain();
                for (String q : queued) out.println(q);
                if (!queued.isEmpty()) out.flush();

                if (listener != null) listener.onStatus("Connected");
                Log.i(TRACE, "MeshNode: socket connected round " + round);

                startKeepalive();
                readLoop();
            } catch (Exception e) {
                Log.w(TRACE, "MeshNode post-connect: " + e.getMessage());
            }
            closeSocket();
            if (!running || fatalError) return;
            if (listener != null) listener.onStatus("Reconnecting...");
        }
    }

    private Socket raceConnections(final int round) {
        int socksPort = TorManager.get(ctx).getSocksPort();
        if (socksPort <= 0) socksPort = TorManager.DEFAULT_SOCKS_PORT;

        final int fPort = socksPort;
        final ExecutorService racePool =
                Executors.newFixedThreadPool(ATTEMPTS_PER_ROUND);
        final List<Future<Socket>> futures = new ArrayList<Future<Socket>>();
        final AtomicBoolean claimed = new AtomicBoolean(false);

        for (int i = 0; i < ATTEMPTS_PER_ROUND; i++) {
            final int idx = i;
            futures.add(racePool.submit(new Callable<Socket>() {
                public Socket call() {
                    Socket s = null;
                    try {
                        Proxy socks = new Proxy(Proxy.Type.SOCKS,
                                new InetSocketAddress("127.0.0.1", fPort));
                        s = new Socket(socks);
                        s.setTcpNoDelay(true);
                        s.setKeepAlive(true);
                        s.connect(InetSocketAddress.createUnresolved(
                                host, port), ATTEMPT_TIMEOUT_MS);

                        if (!claimed.compareAndSet(false, true)) {
                            try { s.close(); } catch (Exception ignored) { }
                            return null;
                        }
                        return s;
                    } catch (Exception e) {
                        if (s != null) {
                            try { s.close(); } catch (Exception ignored) { }
                        }
                        return null;
                    }
                }
            }));
        }

        Socket winner = null;
        try {
            for (Future<Socket> f : futures) {
                try {
                    Socket s = f.get(ATTEMPT_TIMEOUT_MS + 1500,
                            TimeUnit.MILLISECONDS);
                    if (s != null && winner == null) winner = s;
                } catch (Exception e) { }
            }
        } finally {
            racePool.shutdownNow();
        }
        return winner;
    }

    private void readLoop() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String line;
            while (running && (line = in.readLine()) != null) {
                if (heartbeat != null) heartbeat.recordSeen(host);
                handle(line);
                if (fatalError) return;
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshNode readLoop ended: " + e.getMessage());
        }
    }

    private void startKeepalive() {
        if (keepaliveThread != null && keepaliveThread.isAlive()) return;
        keepaliveThread = new Thread(new Runnable() {
            public void run() {
                while (running && socket != null && !socket.isClosed()) {
                    try {
                        if (out != null) {
                            out.println(Protocol.pack(Protocol.PING, myName));
                            out.flush();
                        }
                    } catch (Exception e) { }
                    try { Thread.sleep(10000); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "mesh-node-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }

    private String shortHost(String h) {
        if (h == null) return "?";
        if (h.length() > 16) return h.substring(0, 12) + "...";
        return h;
    }

    public void sendPing() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (out != null)
                        out.println(Protocol.pack(Protocol.PING, myName));
                } catch (Exception e) { }
            }
        }).start();
    }

    private void handle(String line) {
        try {
            String[] p = Protocol.unpack(line);
            if (p.length < 1) return;
            String type = p[0];

            if (Protocol.GROUPKEY.equals(type)) {
                if (p.length >= 2 && p[1].length() > 0) {
                    keyHolder.setFromBase64(p[1]);
                    if (ctx != null) {
                        new SessionStore(ctx).setGroupKey(p[1]);
                    }
                    if (listener != null)
                        listener.onStatus("Group key received");
                }
                return;
            }

            if (Protocol.MSG.equals(type)) {
                if (p.length < 2) return;

                String msgId  = (p.length >= 3) ? p[1] : null;
                String cipher = (p.length >= 3) ? p[2] : p[1];

                if (msgId != null && replica.seen(msgId)) {
                    final String mid = msgId;
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                PrintWriter w = out;
                                if (w != null) {
                                    w.println(Protocol.pack(
                                            Protocol.ACK, mid, myName));
                                    w.flush();
                                }
                            } catch (Exception e) { }
                        }
                    }).start();
                    return;
                }
                if (!replica.contains(line)) replica.append(line);

                SecretKey k = keyHolder.get();
                if (k == null) {
                    Log.w(TRACE, "MeshNode: MSG before key, dropping");
                    return;
                }
                String plain = CryptoUtils.decrypt(cipher, k);
                String[] parts = plain.split("\u0001", -1);

                PeerState.ChatMessage cm = null;
                if (listener != null) {
                    if (parts.length >= 7) {
                        long ts;
                        try { ts = Long.parseLong(parts[2]); }
                        catch (NumberFormatException e) { ts = System.currentTimeMillis(); }
                        long ttl;
                        try { ttl = Long.parseLong(parts[6]); }
                        catch (NumberFormatException e) { ttl = 0L; }
                        long exp = ttl > 0 ? ts + ttl : 0L;
                        String rs = parts[3].isEmpty() ? null : parts[3];
                        String rb = parts[4].isEmpty() ? null : parts[4];
                        boolean mine = myName != null && myName.equals(parts[1]);
                        cm = new PeerState.ChatMessage(parts[1], parts[5],
                                mine, ts, rs, rb, exp);
                    } else if (parts.length == 6) {
                        long ts;
                        try { ts = Long.parseLong(parts[2]); }
                        catch (NumberFormatException e) { ts = System.currentTimeMillis(); }
                        String rs = parts[3].isEmpty() ? null : parts[3];
                        String rb = parts[4].isEmpty() ? null : parts[4];
                        boolean mine = myName != null && myName.equals(parts[1]);
                        cm = new PeerState.ChatMessage(parts[1], parts[5],
                                mine, ts, rs, rb, 0L);
                    } else if (parts.length == 5) {
                        long ts;
                        try { ts = Long.parseLong(parts[2]); }
                        catch (NumberFormatException e) { ts = System.currentTimeMillis(); }
                        long ttl;
                        try { ttl = Long.parseLong(parts[4]); }
                        catch (NumberFormatException e) { ttl = 0L; }
                        long exp = ttl > 0 ? ts + ttl : 0L;
                        boolean mine = myName != null && myName.equals(parts[1]);
                        cm = new PeerState.ChatMessage(parts[1], parts[3],
                                mine, ts, null, null, exp);
                    } else if (parts.length >= 4) {
                        long ts;
                        try { ts = Long.parseLong(parts[2]); }
                        catch (NumberFormatException e) { ts = System.currentTimeMillis(); }
                        boolean mine = myName != null && myName.equals(parts[1]);
                        cm = new PeerState.ChatMessage(parts[1], parts[3],
                                mine, ts);
                    } else if (parts.length >= 3) {
                        boolean mine = myName != null && myName.equals(parts[1]);
                        cm = new PeerState.ChatMessage(parts[1], parts[2], mine);
                    }
                    if (cm != null) listener.onMessage(cm);
                }

                if (msgId != null) {
                    final String mid = msgId;
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                PrintWriter w = out;
                                if (w != null) {
                                    w.println(Protocol.pack(
                                            Protocol.ACK, mid, myName));
                                    w.flush();
                                }
                            } catch (Exception e) { }
                        }
                    }).start();
                }

            } else if (Protocol.TYPING.equals(type)) {
                if (p.length >= 2 && p[1].length() > 0 && listener != null)
                    listener.onTyping(p[1]);

            } else if (Protocol.ACK.equals(type)) {
                if (listener == null) return;
                if (p.length >= 3) listener.onAck(p[1], p[2]);
                else if (p.length >= 2) listener.onAck(p[1], "_host");

            } else if (Protocol.PONG.equals(type)) {
                // Host keepalive; readLoop already recorded the host
                // as seen before dispatching to handle().
                return;

            } else if (Protocol.NAMES.equals(type)) {
                if (listener == null) return;
                if (p.length >= 2) {
                    List<String> names = new ArrayList<String>();
                    String csv = p[1];
                    if (csv.length() > 0) {
                        for (String s : csv.split(",")) {
                            if (s.length() > 0) names.add(s);
                        }
                    }
                    listener.onNames(names);
                }

            } else if (Protocol.LEADER_GONE.equals(type)) {
                if (p.length >= 2) handleLeaderGone(p[1]);

            } else if (Protocol.FULL.equals(type)) {
                String info = p.length >= 2 ? p[1] : "?";
                fatalError = true;
                running    = false;
                if (listener != null)
                    listener.onStatus("Group is full (" + info + ")");
                try { if (socket != null) socket.close(); }
                catch (Exception e) { }

            } else if (Protocol.BACKUP_FULL.equals(type)) {
                String info = p.length >= 2 ? p[1] : "?";
                if (listener != null)
                    listener.onStatus("Backup slots full (" + info + ")");

            } else if (Protocol.BACKUP_RANK.equals(type)) {
                if (p.length >= 3) {
                    try {
                        backupRank = Integer.parseInt(p[1]);
                        long joinedAt = Long.parseLong(p[2]);
                        if (election != null) {
                            election.register(
                                    new ElectionCoordinator.Candidate(
                                            myName, joinedAt));
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TRACE, "MeshNode: bad BACKUP_RANK payload");
                    }
                }

            } else if (Protocol.CANDIDATE.equals(type)) {
                if (p.length >= 3 && election != null) {
                    try {
                        long joinedAt = Long.parseLong(p[2]);
                        election.register(
                                new ElectionCoordinator.Candidate(
                                        p[1], joinedAt));
                    } catch (NumberFormatException e) { }
                }

            } else if (Protocol.REPLAY_BEGIN.equals(type)) {
                if (listener != null) listener.onClearHistory();

            } else if (Protocol.ROSTER.equals(type)) {
                if (p.length >= 5 && listener != null) {
                    try {
                        listener.onRoster(
                                Integer.parseInt(p[1]),
                                Integer.parseInt(p[2]),
                                Integer.parseInt(p[3]),
                                Integer.parseInt(p[4]));
                    } catch (NumberFormatException e) {
                        Log.w(TRACE, "MeshNode: bad ROSTER payload");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshNode handle failed: " + e.getMessage());
        }
    }

    private void handleLeaderGone(String oldHost) {
        if (heartbeat != null) heartbeat.forceDead();
        closeSocket();
    }

    public int     getBackupRank()   { return backupRank; }
    public boolean isFatalError()   { return fatalError; }

    public void sendChat(final String text, final String msgId) {
        sendChat(text, msgId, null, null, 0L);
    }

    public void sendChat(final String text, final String msgId,
                         final String replySender, final String replyBody) {
        sendChat(text, msgId, replySender, replyBody, 0L);
    }

    public void sendChat(final String text, final String msgId,
                         final String replySender, final String replyBody,
                         final long ttlMs) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    long ts = System.currentTimeMillis();
                    String safeBody = text == null ? ""
                            : text.replace('\u0001', ' ');
                    String safeReplyBody = replyBody == null
                            ? "" : replyBody.replace('\u0001', ' ');
                    String plain;
                    if (replySender != null) {
                        plain = "MSG\u0001" + myName
                                + "\u0001" + ts
                                + "\u0001" + replySender
                                + "\u0001" + safeReplyBody
                                + "\u0001" + safeBody
                                + "\u0001" + ttlMs;
                    } else {
                        plain = "MSG\u0001" + myName
                                + "\u0001" + ts
                                + "\u0001" + safeBody
                                + "\u0001" + ttlMs;
                    }
                    SecretKey k = keyHolder.get();
                    if (k == null) {
                        Log.w(TRACE, "MeshNode: cannot send, no key yet");
                        return;
                    }
                    String cipher = CryptoUtils.encrypt(plain, k);
                    String line;
                    if (msgId != null) {
                        line = Protocol.pack(Protocol.MSG, msgId, cipher);
                    } else {
                        line = Protocol.pack(Protocol.MSG, cipher);
                    }
                    replica.append(line);

                    PrintWriter w = out;
                    if (w == null) {
                        pending.enqueue(line);
                        return;
                    }
                    w.println(line);
                    w.flush();
                } catch (Exception e) {
                    Log.e(TRACE, "MeshNode send failed", e);
                }
            }
        }).start();
    }

    public void sendTyping() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (out != null) {
                        out.println(Protocol.pack(Protocol.TYPING, myName));
                        out.flush();
                    }
                } catch (Exception e) { }
            }
        }).start();
    }

    public int getPendingCount() { return pending.size(); }

    public void stop() {
        running = false;
        pending.clear();
        closeSocket();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception e) { }
        socket = null;
        out = null;
    }
}