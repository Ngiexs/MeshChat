package com.bitzlink

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MeshService : Service() {

    inner class LocalBinder : Binder() {
        fun get(): MeshService = this@MeshService
    }

    private val binder = LocalBinder()
    private var server: MeshServer? = null
    private var node: MeshNode? = null
    private var tor: TorManager? = null
    private var replica: Replica? = null
    private var heartbeat: Heartbeat? = null
    private var election: ElectionCoordinator? = null

    private var myName: String? = null
    @Volatile private var currentPeer: String? = null
    private var keyHolder: GroupKeyHolder? = null
    private var currentPort = 0
    private var isBackup = false
    private var amHost = false
    private var startedAt = 0L
    private var activeGroup = ""

    @Volatile private var electing = false
    @Volatile private var electionGen = 0L
    @Volatile private var startingClient = false
    @Volatile private var startingServer = false

    @Volatile private var pendingReplyText: String? = null

    @Volatile private var uiNodeListener: MeshNode.Listener? = null
    @Volatile private var uiServerListener: MeshServer.Listener? = null

    private val dispatchingNodeListener = object : MeshNode.Listener {
        override fun onMessage(m: PeerState.ChatMessage) { uiNodeListener?.onMessage(m) }
        override fun onStatus(s: String?) { uiNodeListener?.onStatus(s) }
        override fun onTyping(who: String) { uiNodeListener?.onTyping(who) }
        override fun onRoster(c: Int, mc: Int, b: Int, mb: Int) {
            cachedClients = c; cachedBackups = b
            uiNodeListener?.onRoster(c, mc, b, mb)
        }
        override fun onNames(names: List<String>?) { uiNodeListener?.onNames(names) }
        override fun onAck(msgId: String, acker: String) { uiNodeListener?.onAck(msgId, acker) }
        override fun onRead(msgId: String, reader: String) { uiNodeListener?.onRead(msgId, reader) }
        override fun onClearHistory() { uiNodeListener?.onClearHistory() }
        override fun onSendFailed(msgId: String?, reason: String?) {
            uiNodeListener?.onSendFailed(msgId, reason)
        }
    }

    private val dispatchingServerListener = object : MeshServer.Listener {
        override fun onMessage(m: PeerState.ChatMessage) { uiServerListener?.onMessage(m) }
        override fun onStatus(s: String?) { uiServerListener?.onStatus(s) }
        override fun onTyping(who: String) { uiServerListener?.onTyping(who) }
        override fun onBackupsChanged(b: List<String>?) { uiServerListener?.onBackupsChanged(b) }
        override fun onRoster(c: Int, mc: Int, b: Int, mb: Int) {
            cachedClients = c; cachedBackups = b
            uiServerListener?.onRoster(c, mc, b, mb)
        }
        override fun onNames(names: List<String>?) { uiServerListener?.onNames(names) }
        override fun onAck(msgId: String, acker: String) { uiServerListener?.onAck(msgId, acker) }
        override fun onRead(msgId: String, reader: String) { uiServerListener?.onRead(msgId, reader) }
    }

    @Volatile private var cachedOnion: String? = null
    @Volatile private var cachedClients = 0
    @Volatile private var cachedBackups = 0

    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkDown = false
    private val activeNetworks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    private val handler = Handler(Looper.getMainLooper())
    private val netHandler = Handler(Looper.getMainLooper())

    private val wakeLockRefresher = object : Runnable {
        override fun run() {
            if (!amHost) return
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "MeshChat::MeshService")
                    wakeLock?.setReferenceCounted(false)
                }
                wakeLock?.let {
                    if (it.isHeld) it.release()
                    it.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.w(TRACE, "MeshService: wake lock refresh failed: ${e.message}")
            }
            handler.postDelayed(this, WAKE_LOCK_REFRESH_MS)
        }
    }

    private val serviceHeartbeatTicker = object : Runnable {
        override fun run() {
            try { tickHeartbeat() } catch (_: Exception) {}
            handler.postDelayed(this, SERVICE_HEARTBEAT_MS)
        }
    }

    private val serviceOnionTicker = object : Runnable {
        override fun run() {
            try { refreshCachedState() } catch (_: Exception) {}
            handler.postDelayed(this, SERVICE_ONION_TICK_MS)
        }
    }

    private fun refreshCachedState() {
        try {
            var o: String? = tor?.getOnionAddress()
            if (o.isNullOrEmpty()) o = TorManager.get(this).getOnionAddress()
            if (!o.isNullOrEmpty()) cachedOnion = o
        } catch (_: Exception) {}
        server?.let {
            cachedClients = it.getClientCount()
            cachedBackups = it.getBackupCount()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeGroup = GroupRegistry(this).getActiveGroup()
        val b64 = SessionStore(this).getGroupKey()
        keyHolder = GroupKeyHolder(null)
        if (b64.isNotEmpty()) keyHolder?.setFromBase64(b64)
        Log.i(TRACE, "MeshService: onCreate, group='$activeGroup' key " +
            (if (keyHolder?.hasKey() == true) "loaded" else "absent"))
    }

    override fun onBind(i: Intent?): IBinder = binder

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        try {
            createChannels()
            startForeground(NOTIF_ID, buildServiceNotification())
        } catch (_: Exception) {}

        startNetworkMonitor()
        updateWakeLock()

        handler.removeCallbacks(serviceHeartbeatTicker)
        handler.postDelayed(serviceHeartbeatTicker, SERVICE_HEARTBEAT_MS)
        handler.removeCallbacks(serviceOnionTicker)
        handler.post(serviceOnionTicker)

        if (i != null && ACTION_SEND_TEXT == i.action) {
            sendFromNotification(i.getStringExtra(EXTRA_TEXT))
        } else {
            resumeRoleFromSession()
        }
        return START_STICKY
    }

    private fun resumeRoleFromSession() {
        if (amHost || node != null) return
        if (startingClient || startingServer) return

        val store = SessionStore(this)
        if (!store.hasSession() || !store.shouldAutoConnect()) {
            Log.i(TRACE, "MeshService: no auto-connect, not resuming")
            return
        }
        val b64 = store.getGroupKey()
        if (b64.isEmpty()) {
            Log.w(TRACE, "MeshService: resume skipped, no key stored")
            return
        }

        val sessionName = store.getName()
        val wasHost = store.isHost()
        val wasBackup = store.isBackup()
        val peer = store.getPeer().ifEmpty { "" }

        val kh = GroupKeyHolder(null)
        kh.setFromBase64(b64)

        if (myName.isNullOrEmpty()) myName = sessionName
        if (keyHolder?.hasKey() != true) keyHolder = kh

        Log.i(TRACE, "MeshService: resuming group='$activeGroup' as " +
            (if (wasHost) "HOST" else "CLIENT"))

        if (wasHost) startAsServer(sessionName, kh, AppConfig.DEFAULT_PORT, null)
        else startAsClient(sessionName, kh, peer, AppConfig.DEFAULT_PORT,
            null, wasBackup)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!SessionStore(this).shouldAutoConnect()) {
            super.onTaskRemoved(rootIntent)
            return
        }
        val restart = Intent(this, MeshService::class.java).apply {
            setPackage(packageName)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart)
        else startService(restart)
        super.onTaskRemoved(rootIntent)
    }

    private fun sendFromNotification(text: String?) {
        if (text.isNullOrEmpty() || text.trim().isEmpty()) return

        if (myName.isNullOrEmpty()) myName = SessionStore(this).getName()
        if (keyHolder?.hasKey() != true) {
            val b64 = SessionStore(this).getGroupKey()
            if (b64.isNotEmpty()) {
                if (keyHolder == null) keyHolder = GroupKeyHolder(null)
                keyHolder?.setFromBase64(b64)
            }
        }
        if (myName.isNullOrEmpty() || keyHolder?.hasKey() != true) {
            Log.w(TRACE, "MeshService: reply dropped, no session/key")
            return
        }

        pendingReplyText = text
        if ((amHost && server != null) || node != null) { flushPendingReply(); return }

        val store = SessionStore(this)
        if (!store.shouldAutoConnect() || !store.hasSession()) {
            Log.w(TRACE, "MeshService: reply dropped, no resumable session")
            pendingReplyText = null
            return
        }
        resumeRoleFromSession()
    }

    private fun flushPendingReply() {
        val text = pendingReplyText ?: return
        val name = myName
        val kh = keyHolder
        if (name.isNullOrEmpty() || kh?.hasKey() != true) return

        try {
            val ts = System.currentTimeMillis()
            val ttl = SessionStore(this).getMessageTtlMs()
            if (amHost && server != null) {
                pendingReplyText = null
                val msgId = UUID.randomUUID().toString()
                val plain = "MSG\u0001$name\u0001$ts\u0001$text\u0001$ttl"
                val cipher = CryptoUtils.encrypt(plain, kh.get()!!)
                server?.sendFromHost(Protocol.pack(Protocol.MSG, msgId, cipher))
            } else if (node != null) {
                pendingReplyText = null
                val msgId = UUID.randomUUID().toString()
                node?.sendChat(text, msgId, null, null, ttl)
            }
        } catch (e: Exception) {
            Log.w(TRACE, "MeshService: reply failed: ${e.message}")
            pendingReplyText = null
        }
    }

    private fun updateWakeLock() {
        if (amHost) {
            handler.removeCallbacks(wakeLockRefresher)
            handler.post(wakeLockRefresher)
        } else {
            handler.removeCallbacks(wakeLockRefresher)
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHAN_SERVICE,
                "MeshChat Service", NotificationManager.IMPORTANCE_LOW))

            val attrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val chat = NotificationChannel(CHAN_CHAT, "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH)
            chat.enableVibration(true)
            chat.vibrationPattern = longArrayOf(0, 200, 100, 200)
            chat.setSound(Uri.parse("android.resource://$packageName/" +
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI), attrs)
            nm.createNotificationChannel(chat)

            val silent = NotificationChannel(CHAN_CHAT_SILENT,
                "Chat Messages (silent)", NotificationManager.IMPORTANCE_LOW)
            silent.enableVibration(true)
            silent.vibrationPattern = longArrayOf(0, 200, 100, 200)
            silent.setSound(null, null)
            nm.createNotificationChannel(silent)
        }
    }

    private fun buildServiceNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHAN_SERVICE)
        else Notification.Builder(this)
        val title = if (activeGroup.isNotEmpty()) "MeshChat — $activeGroup"
                    else "MeshChat"
        return b.setContentTitle(title)
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()
    }

    private fun startNetworkMonitor() {
        if (netCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasEmpty = activeNetworks.isEmpty()
                    activeNetworks.add(network)
                    if (wasEmpty) {
                        networkDown = false
                        netHandler.removeCallbacks(retireIfStillOffline)
                    }
                }
                override fun onLost(network: Network) {
                    activeNetworks.remove(network)
                    if (activeNetworks.isEmpty()) {
                        networkDown = true
                        netHandler.removeCallbacks(retireIfStillOffline)
                        netHandler.postDelayed(retireIfStillOffline, NET_LOSS_GRACE_MS)
                    }
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, netCallback!!)
        } catch (_: Exception) {}
    }

    private val retireIfStillOffline = object : Runnable {
        override fun run() {
            if (!networkDown || !amHost) return
            try { server?.stop() } catch (_: Exception) {}
            server = null
            amHost = false
            updateWakeLock()
        }
    }

    private fun stopNetworkMonitor() {
        netHandler.removeCallbacksAndMessages(null)
        netCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
        activeNetworks.clear()
    }

    @Synchronized
    fun startAsServer(name: String, kh: GroupKeyHolder, port: Int,
                      listener: MeshServer.Listener?) {
        if (listener != null) uiServerListener = listener
        if (amHost && server != null) {
            Log.i(TRACE, "MeshService: already hosting, listener attached")
            listener?.onStatus("Host listening on $currentPort")
            listener?.onRoster(server!!.getClientCount(), MeshServer.MAX_CLIENTS,
                server!!.getBackupCount(), MeshServer.MAX_BACKUPS)
            return
        }
        if (startingServer) {
            Log.i(TRACE, "MeshService: server start already in progress")
            listener?.onStatus("Host starting…")
            return
        }

        stopAll()
        startingServer = true

        myName = name; keyHolder = kh; currentPort = port
        amHost = true; startedAt = System.currentTimeMillis()
        activeGroup = GroupRegistry(this).getActiveGroup()
        replica = Replica(filesDir, activeGroup)
        heartbeat = null; election = null
        updateWakeLock()

        val fGroup = activeGroup
        val tm = TorManager.get(this); tor = tm
        val hasId = tm.hasExistingHsIdentity(fGroup)
        val knownOnion = if (hasId) tm.readOnionFromDisk(fGroup) else null

        Log.i(TRACE, "MeshService: startAsServer group='$fGroup' port=$port " +
            "hasIdentity=$hasId onion=${shortOnion(knownOnion)}")

        if (!hasId || knownOnion == null) {
            startTorHostMode(name, kh, port, fGroup); return
        }
        startTorClientAndProbe(name, kh, port, fGroup, knownOnion, tm)
    }

    private fun startTorClientAndProbe(name: String, kh: GroupKeyHolder,
                                        port: Int, group: String,
                                        knownOnion: String, tm: TorManager) {
        dispatchingServerListener.onStatus("Checking if group is already live…")
        tm.start(false, object : TorManager.Listener {
            override fun onTorReady(socks: Int, onion: String?) {
                Thread({
                    val live = tm.probeExistingHost(knownOnion, port,
                        "__probe__", 20000L)
                    if (live) {
                        Log.w(TRACE, "MeshService: refusing to host, onion already live")
                        synchronized(this@MeshService) {
                            startingServer = false; amHost = false
                        }
                        updateWakeLock()
                        dispatchingServerListener.onStatus(STATUS_HOST_ALREADY_LIVE)
                        return@Thread
                    }
                    Log.i(TRACE, "MeshService: probe clear, publishing")
                    handler.post { startTorHostMode(name, kh, port, group) }
                }, "host-probe").start()
            }
            override fun onError(err: String?) {
                synchronized(this@MeshService) {
                    startingServer = false; amHost = false
                }
                dispatchingServerListener.onStatus("Tor error: $err")
            }
            override fun onProgress(percent: Int) {
                dispatchingServerListener.onStatus("Bootstrapping Tor $percent%")
            }
        }, group)
    }

    private fun startTorHostMode(name: String, kh: GroupKeyHolder,
                                 port: Int, group: String) {
        tor?.start(true, object : TorManager.Listener {
            override fun onTorReady(socksPort: Int, onion: String?) {
                synchronized(this@MeshService) {
                    if (!startingServer) return
                    startingServer = false
                }
                dispatchingServerListener.onStatus("Host listening on $port")
                val srv = MeshServer(name, kh, port, dispatchingServerListener,
                    replica!!)
                server = srv
                srv.start()
                dispatchingServerListener.onRoster(0, MeshServer.MAX_CLIENTS,
                    0, MeshServer.MAX_BACKUPS)
                refreshCachedState()
                Log.i(TRACE, "MeshService: server bound on port $port")
                flushPendingReply()
            }
            override fun onError(err: String?) {
                synchronized(this@MeshService) {
                    startingServer = false; amHost = false
                }
                dispatchingServerListener.onStatus("Tor error: $err")
            }
            override fun onProgress(percent: Int) {
                dispatchingServerListener.onStatus("Bootstrapping Tor $percent%")
            }
        }, group)
    }

    @Synchronized
    fun startAsClient(name: String, kh: GroupKeyHolder, host: String?,
                      port: Int, listener: MeshNode.Listener?,
                      wantBackup: Boolean) {
        if (listener != null) uiNodeListener = listener
        val h = host?.trim() ?: ""

        if (h.isEmpty()) {
            Log.w(TRACE, "MeshService: startAsClient refused, peer empty")
            listener?.onStatus("No host onion set — re-scan the QR or paste " +
                "the credentials again")
            return
        }

        if (!amHost && node != null && h == currentPeer) {
            Log.i(TRACE, "MeshService: already client, listener attached")
            listener?.onStatus("Connected")
            heartbeat?.let {
                val age = it.ageMs(h)
                if (age != Long.MAX_VALUE && age > 30000) {
                    listener?.onStatus("Host not responding")
                }
            }
            return
        }
        if (!amHost && startingClient && h == currentPeer) {
            Log.i(TRACE, "MeshService: client start already in progress")
            listener?.onStatus("Connecting…")
            return
        }

        currentPeer = h
        stopAll()
        startingClient = true

        myName = name; keyHolder = kh; currentPort = port
        isBackup = wantBackup; amHost = false
        startedAt = System.currentTimeMillis()
        activeGroup = GroupRegistry(this).getActiveGroup()
        replica = Replica(filesDir, activeGroup)
        election = ElectionCoordinator()
        updateWakeLock()

        Log.i(TRACE, "MeshService: startAsClient group='$activeGroup' host=$h " +
            "backup=$wantBackup")

        tor = TorManager.get(this)
        tor?.start(false, object : TorManager.Listener {
            override fun onTorReady(socksPort: Int, onion: String?) {
                synchronized(this@MeshService) {
                    if (!startingClient) return
                    startingClient = false
                }
                val hb = Heartbeat(name, object : Heartbeat.Callbacks {
                    override fun onHostDead() {
                        Log.w(TRACE, "MeshService: onHostDead fired")
                        dispatchingNodeListener.onStatus(
                            "Host lost — finding a new host…")
                        beginElection(h, port)
                    }
                    override fun onHostAlive() {}
                })
                hb.setCurrentHost(h)
                heartbeat = hb

                val n = MeshNode(name, kh, applicationContext, h, port,
                    dispatchingNodeListener, replica!!, hb, election, wantBackup)
                node = n
                n.start()
                Log.i(TRACE, "MeshService: node started, heartbeat armed")
                flushPendingReply()
            }
            override fun onError(err: String?) {
                synchronized(this@MeshService) { startingClient = false }
                dispatchingNodeListener.onStatus("Tor error: $err")
            }
            override fun onProgress(percent: Int) {
                dispatchingNodeListener.onStatus("Bootstrapping Tor $percent%")
            }
        }, activeGroup)
    }

    private fun beginElection(oldHost: String, port: Int) {
        if (electing) return
        electing = true
        val myGen = ++electionGen
        runElection(oldHost, port, myGen)
    }

    private fun runElection(oldHost: String, port: Int, gen: Long) {
        if (gen != electionGen) {
            Log.i(TRACE, "runElection: stale generation, dropping")
            return
        }
        val el = election
        if (el == null) {
            Log.w(TRACE, "runElection: no coordinator, aborting")
            electing = false
            return
        }
        el.pruneDead()
        val winner = el.pickWinner()

        Log.i(TRACE, "runElection: myName=$myName isBackup=$isBackup " +
            "candidates=${el.size()} winner=$winner")

        if (winner == null) {
            if (isBackup) {
                el.register(ElectionCoordinator.Candidate(myName ?: "",
                    System.currentTimeMillis()))
            }
            val n = el.size()
            dispatchingNodeListener.onStatus("Choosing new host… ($n backup" +
                (if (n == 1) "" else "s") + " considered)")
            handler.postDelayed({
                if (gen == electionGen && electing) {
                    runElection(oldHost, port, gen)
                }
            }, ELECTION_RETRY_MS)
            return
        }

        if (isBackup && winner == myName) {
            dispatchingNodeListener.onStatus(
                "I'm the new host — starting Tor (up to 30s)…")
            electing = false
            electionGen++          // invalidate any queued retry
            promoteToHost(oldHost, port)
            return
        }

        dispatchingNodeListener.onStatus("$winner is the new host — reconnecting…")
        electing = false
        electionGen++              // invalidate any queued retry
        retryConnectToHost(oldHost, port)
    }

    private fun retryConnectToHost(host: String, port: Int) {
        try { node?.stop() } catch (_: Exception) {}
        node = null
        heartbeat?.setCurrentHost(host)

        handler.postDelayed({
            if (amHost || node != null) return@postDelayed
            val kh = keyHolder ?: return@postDelayed
            val n = MeshNode(myName ?: "", kh, applicationContext, host, port,
                dispatchingNodeListener, replica!!, heartbeat, election, isBackup)
            node = n
            n.start()
            flushPendingReply()
        }, RECONNECT_DELAY_MS)
    }

    private fun promoteToHost(oldHost: String, port: Int) {
        Log.i(TRACE, "promoteToHost: promoting to host on port $port")
        try { node?.stop() } catch (_: Exception) {}
        node = null
        heartbeat?.clear(); heartbeat = null

        amHost = true; isBackup = false
        election?.clear()
        updateWakeLock()

        tor = TorManager.get(this)
        tor?.start(true, object : TorManager.Listener {
            override fun onTorReady(socksPort: Int, onion: String?) {
                dispatchingNodeListener.onStatus("Now hosting on $port")
                val kh = keyHolder ?: return
                val srv = MeshServer(myName ?: "", kh, port,
                    object : MeshServer.Listener {
                        override fun onMessage(m: PeerState.ChatMessage) =
                            dispatchingNodeListener.onMessage(m)
                        override fun onStatus(s: String?) =
                            dispatchingNodeListener.onStatus(s)
                        override fun onTyping(who: String) =
                            dispatchingNodeListener.onTyping(who)
                        override fun onBackupsChanged(b: List<String>?) {}
                        override fun onRoster(c: Int, mc: Int, b: Int, mb: Int) {
                            cachedClients = c; cachedBackups = b
                            dispatchingNodeListener.onRoster(c, mc, b, mb)
                        }
                        override fun onNames(names: List<String>?) =
                            dispatchingNodeListener.onNames(names)
                        override fun onAck(msgId: String, acker: String) =
                            dispatchingNodeListener.onAck(msgId, acker)
                        override fun onRead(msgId: String, reader: String) =
                            dispatchingNodeListener.onRead(msgId, reader)
                    }, replica!!)
                server = srv
                srv.start()
                refreshCachedState()
                Log.i(TRACE, "promoteToHost: server bound")
                flushPendingReply()
            }
            override fun onError(err: String?) {
                dispatchingNodeListener.onStatus("Couldn't take over: $err")
            }
            override fun onProgress(percent: Int) {
                dispatchingNodeListener.onStatus("Bootstrapping Tor $percent%")
            }
        }, activeGroup)
    }

    fun tickHeartbeat() {
        heartbeat?.tick()
        election?.pruneDead()
    }

    fun getServer(): MeshServer? = server
    fun getNode(): MeshNode? = node
    fun getReplica(): Replica? = replica
    fun getHeartbeat(): Heartbeat? = heartbeat
    fun getElection(): ElectionCoordinator? = election
    fun amHost(): Boolean = amHost
    fun isBackup(): Boolean = isBackup
    fun getKeyHolder(): GroupKeyHolder? = keyHolder
    fun getActiveGroup(): String = activeGroup
    fun getMyName(): String? = myName

    fun getOnionAddress(): String? {
        cachedOnion?.let { if (it.isNotEmpty()) return it }
        try {
            var o = tor?.getOnionAddress()
            if (!o.isNullOrEmpty()) { cachedOnion = o; return o }
            o = TorManager.get(this).getOnionAddress()
            if (!o.isNullOrEmpty()) { cachedOnion = o; return o }
        } catch (_: Exception) {}
        return null
    }

    fun getCachedClientCount(): Int = cachedClients
    fun getCachedBackupCount(): Int = cachedBackups

    fun showChatNotification(sender: String, body: String) {
        try {
            val ss = SessionStore(this)
            if (ss.isMuted() || ss.isQuietNow()) return

            val sound = ss.isSoundEnabled()
            val b = if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(this, if (sound) CHAN_CHAT else CHAN_CHAT_SILENT)
            else Notification.Builder(this).apply {
                if (sound) setDefaults(Notification.DEFAULT_ALL)
                else setDefaults(Notification.DEFAULT_VIBRATE)
            }

            var title = sender
            if (activeGroup.isNotEmpty()) title = "$sender · $activeGroup"
            b.setContentTitle(title).setContentText(body)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)

            var nName = myName
            var nPeer = currentPeer
            var nHost = amHost
            var nBkup = isBackup
            if (nName.isNullOrEmpty()) {
                nName = ss.getName()
                if (nPeer.isNullOrEmpty()) nPeer = ss.getPeer()
                nHost = ss.isHost(); nBkup = ss.isBackup()
            }
            if (nName == null) nName = ""
            if (nPeer == null) nPeer = ""

            val open = Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("name", nName)
                putExtra("peer", nPeer)
                putExtra("isHost", nHost)
                putExtra("isBackup", nBkup)
            }
            var contentFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) contentFlags = contentFlags or
                PendingIntent.FLAG_IMMUTABLE
            b.setContentIntent(PendingIntent.getActivity(this, 1001, open,
                contentFlags))

            if (Build.VERSION.SDK_INT >= 24) {
                val replyIntent = Intent(this, ReplyReceiver::class.java).apply {
                    action = ReplyReceiver.ACTION_REPLY
                }
                var replyFlags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) replyFlags = replyFlags or
                    PendingIntent.FLAG_MUTABLE
                val replyPi = PendingIntent.getBroadcast(this, 2002, replyIntent,
                    replyFlags)
                val remote = RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
                    .setLabel("Reply").build()
                val action = Notification.Action.Builder(
                    android.R.drawable.ic_menu_send, "Reply", replyPi)
                    .addRemoteInput(remote).build()
                b.addAction(action)
            }

            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(nextNotificationId(), b.build())
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stopAll() {
        electionGen++              // invalidate any queued election retry
        try { server?.stop() } catch (_: Exception) {}; server = null
        try { node?.stop() } catch (_: Exception) {}; node = null
        heartbeat?.clear(); heartbeat = null
        election?.clear(); election = null
        electing = false; startingClient = false; startingServer = false
        updateWakeLock()
    }

    override fun onTimeout(startId: Int) {
        Log.w(TRACE, "MeshService: onTimeout — restarting after 6h cap")
        try {
            val restart = Intent(this, MeshService::class.java).apply {
                setPackage(packageName)
            }
            val pi = PendingIntent.getForegroundService(this, 42, restart,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(ALARM_SERVICE) as? AlarmManager
            am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5000L, pi)
        } catch (e: Exception) {
            Log.w(TRACE, "MeshService: onTimeout restart failed: ${e.message}")
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopAll()
        stopNetworkMonitor()
        handler.removeCallbacksAndMessages(null)
        tor?.stop(); tor = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TRACE = "MeshTrace"
        private const val CHAN_SERVICE = "bitzlink_service"
        private const val CHAN_CHAT = "bitzlink_chat"
        private const val CHAN_CHAT_SILENT = "bitzlink_chat_silent"
        private const val NOTIF_ID = 1
        private const val ELECTION_RETRY_MS = 2000L
        private const val NET_LOSS_GRACE_MS = 30000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val SERVICE_HEARTBEAT_MS = 10000L
        private const val SERVICE_ONION_TICK_MS = 5000L
        private const val WAKE_LOCK_TIMEOUT_MS = 45_000L
        private const val WAKE_LOCK_REFRESH_MS = 30_000L

        const val ACTION_SEND_TEXT = "com.bitzlink.ACTION_SEND_TEXT"
        const val EXTRA_TEXT = "text"
        const val STATUS_HOST_ALREADY_LIVE = "HOST_ALREADY_LIVE"

        // Monotonic notification id. Wraps every ~2 billion notifications.
        private val notifIdSeq = AtomicInteger(1000)

        private fun nextNotificationId(): Int =
            notifIdSeq.incrementAndGet() and 0x7FFFFFFF

        private fun shortOnion(o: String?): String {
            if (o.isNullOrEmpty()) return "—"
            if (o.length <= 16) return o
            return o.substring(0, 8) + "…" + o.substring(o.length - 4)
        }
    }
}