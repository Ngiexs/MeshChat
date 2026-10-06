package com.bitzlink;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MeshService extends Service {

    private static final String TRACE = "MeshTrace";
    private static final String CHAN_SERVICE      = "bitzlink_service";
    private static final String CHAN_CHAT         = "bitzlink_chat";
    private static final String CHAN_CHAT_SILENT  = "bitzlink_chat_silent";
    private static final int    NOTIF_ID          = 1;
    private static final long   ELECTION_RETRY_MS = 2000;
    private static final long   NET_LOSS_GRACE_MS = 30000L;
    private static final long   RECONNECT_DELAY_MS = 3000;

    private static final long SERVICE_HEARTBEAT_MS  = 10000L;
    private static final long SERVICE_ONION_TICK_MS = 5000L;

    private static final long WAKE_LOCK_TIMEOUT_MS = 45_000L;
    private static final long WAKE_LOCK_REFRESH_MS = 30_000L;

    public static final String ACTION_SEND_TEXT = "com.bitzlink.ACTION_SEND_TEXT";
    public static final String EXTRA_TEXT = "text";

    /**
     * Magic status string dispatched to the UI when the pre-flight
     * probe finds a live host already publishing this group's onion.
     * ChatActivity intercepts this and shows a refusal dialog instead
     * of displaying it as plain status text.
     */
    public static final String STATUS_HOST_ALREADY_LIVE = "HOST_ALREADY_LIVE";

    public class LocalBinder extends Binder {
        public MeshService get() { return MeshService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private MeshServer server;
    private MeshNode   node;
    private TorManager tor;
    private Replica    replica;
    private Heartbeat  heartbeat;
    private ElectionCoordinator election;

    private String myName;
    private volatile String currentPeer;
    private GroupKeyHolder keyHolder;
    private int currentPort;
    private boolean isBackup = false;
    private boolean amHost   = false;
    private long startedAt;
    private String activeGroup = "";

    private volatile boolean electing = false;
    private volatile boolean startingClient = false;
    private volatile boolean startingServer = false;

    private volatile MeshNode.Listener   uiNodeListener;
    private volatile MeshServer.Listener uiServerListener;

    private final MeshNode.Listener dispatchingNodeListener =
            new MeshNode.Listener() {
        public void onMessage(PeerState.ChatMessage m) {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onMessage(m);
        }
        public void onStatus(String s) {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onStatus(s);
        }
        public void onTyping(String who) {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onTyping(who);
        }
        public void onRoster(int c, int mc, int b, int mb) {
            cachedClients = c; cachedBackups = b;
            MeshNode.Listener l = uiNodeListener;
            if (l != null) l.onRoster(c, mc, b, mb);
        }
        public void onNames(List<String> names) {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onNames(names);
        }
        public void onAck(String msgId, String acker) {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onAck(msgId, acker);
        }
        public void onClearHistory() {
            MeshNode.Listener l = uiNodeListener; if (l != null) l.onClearHistory();
        }
    };

    private final MeshServer.Listener dispatchingServerListener =
            new MeshServer.Listener() {
        public void onMessage(PeerState.ChatMessage m) {
            MeshServer.Listener l = uiServerListener; if (l != null) l.onMessage(m);
        }
        public void onStatus(String s) {
            MeshServer.Listener l = uiServerListener; if (l != null) l.onStatus(s);
        }
        public void onTyping(String who) {
            MeshServer.Listener l = uiServerListener; if (l != null) l.onTyping(who);
        }
        public void onBackupsChanged(List<String> b) {
            MeshServer.Listener l = uiServerListener;
            if (l != null) l.onBackupsChanged(b);
        }
        public void onRoster(int c, int mc, int b, int mb) {
            cachedClients = c; cachedBackups = b;
            MeshServer.Listener l = uiServerListener;
            if (l != null) l.onRoster(c, mc, b, mb);
        }
        public void onNames(List<String> names) {
            MeshServer.Listener l = uiServerListener; if (l != null) l.onNames(names);
        }
    };

    private volatile String cachedOnion   = null;
    private volatile int    cachedClients = 0;
    private volatile int    cachedBackups = 0;

    private PowerManager.WakeLock wakeLock;

    private ConnectivityManager.NetworkCallback netCallback;
    private volatile boolean networkDown = false;

    private final Set<Network> activeNetworks =
            Collections.newSetFromMap(new ConcurrentHashMap<Network, Boolean>());

    private final Handler handler    = new Handler(Looper.getMainLooper());
    private final Handler netHandler = new Handler(Looper.getMainLooper());

    private final Runnable wakeLockRefresher = new Runnable() {
        public void run() {
            if (!amHost) return;
            try {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager)
                            getSystemService(POWER_SERVICE);
                    if (pm == null) return;
                    wakeLock = pm.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "MeshChat::MeshService");
                    wakeLock.setReferenceCounted(false);
                }
                if (wakeLock.isHeld()) wakeLock.release();
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            } catch (Exception e) {
                Log.w(TRACE, "MeshService: wake lock refresh failed: "
                        + e.getMessage());
            }
            handler.postDelayed(this, WAKE_LOCK_REFRESH_MS);
        }
    };

    private final Runnable serviceHeartbeatTicker = new Runnable() {
        public void run() {
            try { tickHeartbeat(); }
            catch (Exception e) { }
            handler.postDelayed(this, SERVICE_HEARTBEAT_MS);
        }
    };

    private final Runnable serviceOnionTicker = new Runnable() {
        public void run() {
            try { refreshCachedState(); }
            catch (Exception e) { }
            handler.postDelayed(this, SERVICE_ONION_TICK_MS);
        }
    };

    private void refreshCachedState() {
        try {
            String o = null;
            if (tor != null) o = tor.getOnionAddress();
            if (o == null || o.length() == 0) {
                o = TorManager.get(this).getOnionAddress();
            }
            if (o != null && o.length() > 0) cachedOnion = o;
        } catch (Exception e) { }

        if (server != null) {
            cachedClients = server.getClientCount();
            cachedBackups = server.getBackupCount();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        activeGroup = new GroupRegistry(this).getActiveGroup();
        String b64 = new SessionStore(this).getGroupKey();
        keyHolder = new GroupKeyHolder(null);
        if (b64 != null && b64.length() > 0) {
            keyHolder.setFromBase64(b64);
        }
        Log.i(TRACE, "MeshService: onCreate, group='" + activeGroup
                + "' key " + (keyHolder.hasKey() ? "loaded" : "absent"));
    }

    public IBinder onBind(Intent i) { return binder; }

    public int onStartCommand(Intent i, int f, int s) {
        try {
            createChannels();
            startForeground(NOTIF_ID, buildServiceNotification());
        } catch (Exception e) { }

        startNetworkMonitor();
        updateWakeLock();

        handler.removeCallbacks(serviceHeartbeatTicker);
        handler.postDelayed(serviceHeartbeatTicker, SERVICE_HEARTBEAT_MS);

        handler.removeCallbacks(serviceOnionTicker);
        handler.post(serviceOnionTicker);

        if (i != null && ACTION_SEND_TEXT.equals(i.getAction())) {
            String text = i.getStringExtra(EXTRA_TEXT);
            sendFromNotification(text);
        } else {
            resumeRoleFromSession();
        }

        return START_STICKY;
    }

    private void resumeRoleFromSession() {
        if (amHost || node != null) return;
        if (startingClient || startingServer) return;

        SessionStore store = new SessionStore(this);
        if (!store.hasSession() || !store.shouldAutoConnect()) {
            Log.i(TRACE, "MeshService: no auto-connect, not resuming");
            return;
        }
        String b64 = store.getGroupKey();
        if (b64 == null || b64.length() == 0) {
            Log.w(TRACE, "MeshService: resume skipped, no key stored");
            return;
        }

        String sessionName = store.getName();
        boolean wasHost    = store.isHost();
        boolean wasBackup  = store.isBackup();
        String peer        = store.getPeer();
        if (peer == null) peer = "";

        GroupKeyHolder kh = new GroupKeyHolder(null);
        kh.setFromBase64(b64);

        Log.i(TRACE, "MeshService: resuming group='" + activeGroup
                + "' as " + (wasHost ? "HOST" : "CLIENT"));

        if (wasHost) {
            startAsServer(sessionName, kh, AppConfig.DEFAULT_PORT, null);
        } else {
            startAsClient(sessionName, kh, peer, AppConfig.DEFAULT_PORT,
                    null, wasBackup);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!new SessionStore(this).shouldAutoConnect()) {
            super.onTaskRemoved(rootIntent);
            return;
        }
        Intent restart = new Intent(this, MeshService.class);
        restart.setPackage(getPackageName());
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void sendFromNotification(String text) {
        if (text == null || text.trim().length() == 0) return;
        if (myName == null || keyHolder == null || !keyHolder.hasKey()) {
            Log.w(TRACE, "MeshService: reply dropped, no session/key");
            return;
        }
        try {
            long ts = System.currentTimeMillis();
            long ttl = new SessionStore(this).getMessageTtlMs();
            if (amHost && server != null) {
                String msgId = UUID.randomUUID().toString();
                String plain = "MSG\u0001" + myName
                        + "\u0001" + ts + "\u0001" + text
                        + "\u0001" + ttl;
                String cipher = CryptoUtils.encrypt(plain, keyHolder.get());
                String line = Protocol.pack(Protocol.MSG, msgId, cipher);
                server.sendFromHost(line);
            } else if (node != null) {
                String msgId = UUID.randomUUID().toString();
                node.sendChat(text, msgId, null, null, ttl);
            } else {
                Log.w(TRACE, "MeshService: reply dropped, no server or node");
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshService: reply failed: " + e.getMessage());
        }
    }

    private void updateWakeLock() {
        if (amHost) {
            handler.removeCallbacks(wakeLockRefresher);
            handler.post(wakeLockRefresher);
        } else {
            handler.removeCallbacks(wakeLockRefresher);
            try {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } catch (Exception e) { }
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel svc = new NotificationChannel(
                    CHAN_SERVICE, "MeshChat Service",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(svc);

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            NotificationChannel chat = new NotificationChannel(
                    CHAN_CHAT, "Chat Messages",
                    NotificationManager.IMPORTANCE_HIGH);
            chat.enableVibration(true);
            chat.setVibrationPattern(new long[]{0, 200, 100, 200});
            chat.setSound(Uri.parse("android.resource://" + getPackageName()
                    + "/" + android.provider.Settings.System
                    .DEFAULT_NOTIFICATION_URI), attrs);
            nm.createNotificationChannel(chat);

            NotificationChannel silent = new NotificationChannel(
                    CHAN_CHAT_SILENT, "Chat Messages (silent)",
                    NotificationManager.IMPORTANCE_LOW);
            silent.enableVibration(true);
            silent.setVibrationPattern(new long[]{0, 200, 100, 200});
            silent.setSound(null, null);
            nm.createNotificationChannel(silent);
        }
    }

    private Notification buildServiceNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHAN_SERVICE)
                : new Notification.Builder(this);
        String title = "MeshChat";
        if (activeGroup != null && activeGroup.length() > 0) {
            title = "MeshChat — " + activeGroup;
        }
        return b.setContentTitle(title)
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setOngoing(true)
                .build();
    }

    private void startNetworkMonitor() {
        if (netCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    boolean wasEmpty = activeNetworks.isEmpty();
                    activeNetworks.add(network);
                    if (wasEmpty) {
                        networkDown = false;
                        netHandler.removeCallbacks(retireIfStillOffline);
                    }
                }
                @Override public void onLost(Network network) {
                    activeNetworks.remove(network);
                    if (activeNetworks.isEmpty()) {
                        networkDown = true;
                        netHandler.removeCallbacks(retireIfStillOffline);
                        netHandler.postDelayed(retireIfStillOffline,
                                NET_LOSS_GRACE_MS);
                    }
                }
            };

            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, netCallback);
        } catch (Exception e) { }
    }

    private final Runnable retireIfStillOffline = new Runnable() {
        public void run() {
            if (!networkDown) return;
            if (!amHost) return;
            if (server != null) {
                try { server.stop(); } catch (Exception e) { }
                server = null;
            }
            amHost = false;
            updateWakeLock();
        }
    };

    private void stopNetworkMonitor() {
        netHandler.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) { }
            netCallback = null;
        }
        activeNetworks.clear();
    }

    // ── Host start: pre-flight probe then host mode ──────────────────

    public synchronized void startAsServer(final String name,
                                           final GroupKeyHolder kh,
                                           final int port,
                                           final MeshServer.Listener listener) {
        if (listener != null) uiServerListener = listener;

        if (amHost && server != null) {
            Log.i(TRACE, "MeshService: already hosting, listener attached");
            if (listener != null) {
                listener.onStatus("Host listening on " + currentPort);
                listener.onRoster(server.getClientCount(),
                        MeshServer.MAX_CLIENTS,
                        server.getBackupCount(),
                        MeshServer.MAX_BACKUPS);
            }
            return;
        }
        if (startingServer) {
            Log.i(TRACE, "MeshService: server start already in progress");
            if (listener != null) listener.onStatus("Host starting…");
            return;
        }

        stopAll();
        startingServer = true;

        this.myName      = name;
        this.keyHolder   = kh;
        this.currentPort = port;
        this.amHost      = true;
        this.startedAt   = System.currentTimeMillis();
        this.activeGroup = new GroupRegistry(this).getActiveGroup();
        this.replica     = new Replica(getFilesDir(), activeGroup);
        this.heartbeat   = null;
        this.election    = null;
        updateWakeLock();

        final String fGroup = this.activeGroup;
        final TorManager tm = TorManager.get(this);
        this.tor = tm;

        final boolean hasId = tm.hasExistingHsIdentity(fGroup);
        final String knownOnion = hasId ? tm.readOnionFromDisk(fGroup) : null;

        Log.i(TRACE, "MeshService: startAsServer group='" + fGroup
                + "' port=" + port
                + " hasIdentity=" + hasId
                + " onion=" + shortOnion(knownOnion));

        if (!hasId || knownOnion == null) {
            startTorHostMode(name, kh, port, fGroup);
            return;
        }

        startTorClientAndProbe(name, kh, port, fGroup, knownOnion, tm);
    }

    private void startTorClientAndProbe(final String name,
                                        final GroupKeyHolder kh,
                                        final int port,
                                        final String group,
                                        final String knownOnion,
                                        final TorManager tm) {
        dispatchingServerListener.onStatus(
                "Checking if group is already live…");
        tm.start(group, false, new TorManager.Listener() {
            public void onTorReady(int socks, String onion) {
                new Thread(new Runnable() {
                    public void run() {
                        boolean live = tm.probeExistingHost(
                                knownOnion, port, "__probe__", 20000L);
                        if (live) {
                            Log.w(TRACE, "MeshService: refusing to host, "
                                    + "onion already live");
                            synchronized (MeshService.this) {
                                startingServer = false;
                                amHost = false;
                            }
                            updateWakeLock();
                            dispatchingServerListener.onStatus(
                                    STATUS_HOST_ALREADY_LIVE);
                            return;
                        }
                        Log.i(TRACE, "MeshService: probe clear, publishing");
                        handler.post(new Runnable() {
                            public void run() {
                                startTorHostMode(name, kh, port, group);
                            }
                        });
                    }
                }, "host-probe").start();
            }
            public void onError(String err) {
                synchronized (MeshService.this) {
                    startingServer = false;
                    amHost = false;
                }
                dispatchingServerListener.onStatus("Tor error: " + err);
            }
            public void onProgress(int pct) {
                dispatchingServerListener.onStatus(
                        "Bootstrapping Tor " + pct + "%");
            }
        });
    }

    private void startTorHostMode(final String name,
                                  final GroupKeyHolder kh,
                                  final int port,
                                  final String group) {
        tor.start(group, true, new TorManager.Listener() {
            public void onTorReady(int socksPort, String onion) {
                synchronized (MeshService.this) {
                    if (!startingServer) return;
                    startingServer = false;
                }
                dispatchingServerListener.onStatus(
                        "Host listening on " + port);
                server = new MeshServer(name, kh, port,
                        dispatchingServerListener, replica);
                server.start();
                dispatchingServerListener.onRoster(
                        0, MeshServer.MAX_CLIENTS,
                        0, MeshServer.MAX_BACKUPS);
                refreshCachedState();
                Log.i(TRACE, "MeshService: server bound on port " + port);
            }
            public void onError(String err) {
                synchronized (MeshService.this) {
                    startingServer = false;
                    amHost = false;
                }
                dispatchingServerListener.onStatus("Tor error: " + err);
            }
            public void onProgress(int pct) {
                dispatchingServerListener.onStatus(
                        "Bootstrapping Tor " + pct + "%");
            }
        });
    }

    private static String shortOnion(String o) {
        if (o == null || o.length() == 0) return "—";
        if (o.length() <= 16) return o;
        return o.substring(0, 8) + "…" + o.substring(o.length() - 4);
    }

    // ── Client start ─────────────────────────────────────────────────

    public synchronized void startAsClient(final String name,
                                           final GroupKeyHolder kh,
                                           final String host,
                                           final int port,
                                           final MeshNode.Listener listener,
                                           final boolean wantBackup) {
        if (listener != null) uiNodeListener = listener;

        String h = host == null ? "" : host;

        if (!amHost && node != null && h.equals(currentPeer)) {
            Log.i(TRACE, "MeshService: already client, listener attached");
            if (listener != null) {
                listener.onStatus("Connected");
                if (heartbeat != null) {
                    long age = heartbeat.ageMs(h);
                    if (age != Long.MAX_VALUE && age > 30000) {
                        listener.onStatus("Host not responding");
                    }
                }
            }
            return;
        }
        if (!amHost && startingClient && h.equals(currentPeer)) {
            Log.i(TRACE, "MeshService: client start already in progress");
            if (listener != null) {
                listener.onStatus("Connecting…");
            }
            return;
        }

        this.currentPeer = h;

        stopAll();
        startingClient = true;

        this.myName      = name;
        this.keyHolder   = kh;
        this.currentPort = port;
        this.isBackup    = wantBackup;
        this.amHost      = false;
        this.startedAt   = System.currentTimeMillis();
        this.activeGroup = new GroupRegistry(this).getActiveGroup();
        this.replica     = new Replica(getFilesDir(), activeGroup);
        this.election    = new ElectionCoordinator();
        updateWakeLock();

        Log.i(TRACE, "MeshService: startAsClient group='" + activeGroup
                + "' host=" + h + " backup=" + wantBackup);

        tor = TorManager.get(this);
        tor.start(activeGroup, false, new TorManager.Listener() {
            public void onTorReady(int socksPort, String onion) {
                synchronized (MeshService.this) {
                    if (!startingClient) return;
                    startingClient = false;
                }
                heartbeat = new Heartbeat(name, new Heartbeat.Callbacks() {
                    public void onHostDead() {
                        Log.w(TRACE, "MeshService: onHostDead fired");
                        dispatchingNodeListener.onStatus(
                                "Host lost — finding a new host…");
                        beginElection(h, port);
                    }
                    public void onHostAlive() { }
                });
                heartbeat.setCurrentHost(h);

                node = new MeshNode(name, kh,
                        getApplicationContext(),
                        h, port,
                        dispatchingNodeListener,
                        replica, heartbeat, election, wantBackup);
                node.start();
                Log.i(TRACE, "MeshService: node started, heartbeat armed");
            }
            public void onError(String err) {
                synchronized (MeshService.this) {
                    startingClient = false;
                }
                dispatchingNodeListener.onStatus("Tor error: " + err);
            }
            public void onProgress(int pct) {
                dispatchingNodeListener.onStatus(
                        "Bootstrapping Tor " + pct + "%");
            }
        });
    }

    private void beginElection(final String oldHost, final int port) {
        if (electing) return;
        electing = true;
        runElection(oldHost, port);
    }

    private void runElection(final String oldHost, final int port) {
        if (election == null) {
            Log.w(TRACE, "runElection: no coordinator, aborting");
            electing = false;
            return;
        }
        election.pruneDead();
        String winner = election.pickWinner();

        Log.i(TRACE, "runElection: myName=" + myName
                + " isBackup=" + isBackup
                + " candidates=" + election.size()
                + " winner=" + winner);

        if (winner == null) {
            if (isBackup) {
                election.register(new ElectionCoordinator.Candidate(
                        myName, System.currentTimeMillis()));
                Log.i(TRACE, "runElection: self-registered as candidate");
            }
            int n = election.size();
            dispatchingNodeListener.onStatus(
                    "Choosing new host… (" + n + " backup"
                    + (n == 1 ? "" : "s") + " considered)");
            handler.postDelayed(new Runnable() {
                public void run() { runElection(oldHost, port); }
            }, ELECTION_RETRY_MS);
            return;
        }

        if (isBackup && winner.equals(myName)) {
            dispatchingNodeListener.onStatus(
                    "I'm the new host — starting Tor (up to 30s)…");
            electing = false;
            promoteToHost(oldHost, port);
            return;
        }

        dispatchingNodeListener.onStatus(
                winner + " is the new host — reconnecting…");
        electing = false;
        retryConnectToHost(oldHost, port);
    }

    private void retryConnectToHost(final String host, final int port) {
        if (node != null) {
            try { node.stop(); } catch (Exception e) { }
            node = null;
        }
        if (heartbeat != null) heartbeat.setCurrentHost(host);

        handler.postDelayed(new Runnable() {
            public void run() {
                if (amHost || node != null) return;
                if (keyHolder == null) return;
                node = new MeshNode(myName, keyHolder,
                        getApplicationContext(),
                        host, port,
                        dispatchingNodeListener,
                        replica, heartbeat, election, isBackup);
                node.start();
            }
        }, RECONNECT_DELAY_MS);
    }

    private void promoteToHost(final String oldHost, final int port) {
        Log.i(TRACE, "promoteToHost: promoting to host on port " + port);

        if (node != null) {
            try { node.stop(); } catch (Exception e) { }
            node = null;
        }
        if (heartbeat != null) {
            heartbeat.clear();
            heartbeat = null;
        }

        amHost   = true;
        isBackup = false;
        if (election != null) election.clear();
        updateWakeLock();

        tor = TorManager.get(this);
        tor.start(activeGroup, true, new TorManager.Listener() {
            public void onTorReady(int socksPort, String onion) {
                dispatchingNodeListener.onStatus("Now hosting on " + port);
                server = new MeshServer(myName, keyHolder, port,
                        new MeshServer.Listener() {
                    public void onMessage(PeerState.ChatMessage m) {
                        dispatchingNodeListener.onMessage(m);
                    }
                    public void onStatus(String s) {
                        dispatchingNodeListener.onStatus(s);
                    }
                    public void onTyping(String who) {
                        dispatchingNodeListener.onTyping(who);
                    }
                    public void onBackupsChanged(List<String> b) { }
                    public void onRoster(int c, int mc, int b, int mb) {
                        cachedClients = c; cachedBackups = b;
                        dispatchingNodeListener.onRoster(c, mc, b, mb);
                    }
                    public void onNames(List<String> names) {
                        dispatchingNodeListener.onNames(names);
                    }
                }, replica);
                server.start();
                refreshCachedState();
                Log.i(TRACE, "promoteToHost: server bound");
            }
            public void onError(String err) {
                dispatchingNodeListener.onStatus(
                        "Couldn't take over: " + err);
            }
            public void onProgress(int pct) {
                dispatchingNodeListener.onStatus(
                        "Bootstrapping Tor " + pct + "%");
            }
        });
    }

    public void tickHeartbeat() {
        if (heartbeat != null) heartbeat.tick();
        if (election  != null) election.pruneDead();
    }

    public MeshServer          getServer()    { return server; }
    public MeshNode            getNode()      { return node; }
    public Replica             getReplica()   { return replica; }
    public Heartbeat           getHeartbeat() { return heartbeat; }
    public ElectionCoordinator getElection()  { return election; }
    public boolean             amHost()       { return amHost; }
    public boolean             isBackup()     { return isBackup; }
    public GroupKeyHolder      getKeyHolder() { return keyHolder; }
    public String              getActiveGroup() { return activeGroup; }

    public String getOnionAddress() {
        String c = cachedOnion;
        if (c != null && c.length() > 0) return c;
        try {
            String o = tor != null ? tor.getOnionAddress() : null;
            if (o != null && o.length() > 0) { cachedOnion = o; return o; }
            o = TorManager.get(this).getOnionAddress();
            if (o != null && o.length() > 0) { cachedOnion = o; return o; }
        } catch (Exception e) { }
        return null;
    }

    public int getCachedClientCount() { return cachedClients; }
    public int getCachedBackupCount() { return cachedBackups; }

    public void showChatNotification(String sender, String body) {
        try {
            SessionStore ss = new SessionStore(this);
            if (ss.isMuted()) return;
            if (ss.isQuietNow()) return;

            boolean sound = ss.isSoundEnabled();
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                String chan = sound ? CHAN_CHAT : CHAN_CHAT_SILENT;
                b = new Notification.Builder(this, chan);
            } else {
                b = new Notification.Builder(this);
                if (sound) b.setDefaults(Notification.DEFAULT_ALL);
                else       b.setDefaults(Notification.DEFAULT_VIBRATE);
            }
            String title = sender;
            if (activeGroup != null && activeGroup.length() > 0) {
                title = sender + " · " + activeGroup;
            }
            b.setContentTitle(title)
             .setContentText(body)
             .setSmallIcon(android.R.drawable.stat_notify_chat)
             .setAutoCancel(true);

            Intent open = new Intent(this, ChatActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("name", myName);
            open.putExtra("peer", currentPeer);
            open.putExtra("isHost", amHost);
            open.putExtra("isBackup", isBackup);
            int contentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23)
                contentFlags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(
                    this, 1001, open, contentFlags));

            if (Build.VERSION.SDK_INT >= 24) {
                Intent replyIntent = new Intent(this, ReplyReceiver.class);
                replyIntent.setAction(ReplyReceiver.ACTION_REPLY);
                int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31)
                    replyFlags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent replyPi = PendingIntent.getBroadcast(
                        this, 2002, replyIntent, replyFlags);

                RemoteInput remote = new RemoteInput.Builder(
                        ReplyReceiver.KEY_TEXT)
                        .setLabel("Reply")
                        .build();
                Notification.Action replyAction =
                        new Notification.Action.Builder(
                                android.R.drawable.ic_menu_send,
                                "Reply", replyPi)
                        .addRemoteInput(remote)
                        .build();
                b.addAction(replyAction);
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null)
                nm.notify((int) System.currentTimeMillis(), b.build());
        } catch (Exception e) { }
    }

    public synchronized void stopAll() {
        if (server    != null) { server.stop();    server    = null; }
        if (node      != null) { node.stop();      node      = null; }
        if (heartbeat != null) { heartbeat.clear(); heartbeat = null; }
        if (election  != null) { election.clear();  election  = null; }
        electing       = false;
        startingClient = false;
        startingServer = false;
        updateWakeLock();
    }

    @Override
    public void onTimeout(int startId) {
        Log.w(TRACE, "MeshService: onTimeout — restarting after 6h cap");
        try {
            Intent restart = new Intent(this, MeshService.class);
            restart.setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getForegroundService(
                    this, 42, restart,
                    PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 5000L, pi);
            }
        } catch (Exception e) {
            Log.w(TRACE, "MeshService: onTimeout restart failed: "
                    + e.getMessage());
        }
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        stopAll();
        stopNetworkMonitor();
        handler.removeCallbacksAndMessages(null);
        if (tor != null) { tor.stop(); tor = null; }

        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); }
            catch (Exception e) { }
            wakeLock = null;
        }
        super.onDestroy();
    }
}