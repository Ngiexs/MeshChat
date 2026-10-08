package com.bitzlink

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

class MeshNode(
    private val myName: String,
    private val keyHolder: GroupKeyHolder,
    private val ctx: Context,
    host: String?,
    private val port: Int,
    private val listener: Listener?,
    private val replica: Replica,
    private val heartbeat: Heartbeat?,
    private val election: ElectionCoordinator?,
    private val wantBackup: Boolean
) {

    interface Listener {
        fun onMessage(m: PeerState.ChatMessage)
        fun onStatus(status: String?)
        fun onTyping(who: String)
        fun onRoster(clients: Int, maxClients: Int, backups: Int, maxBackups: Int)
        fun onNames(names: List<String>?)
        fun onAck(msgId: String, acker: String)
        fun onRead(msgId: String, reader: String)
        fun onReaction(sender: String, targetSig: String, emoji: String)
        fun onClearHistory()
        fun onSendFailed(msgId: String?, reason: String?)
    }

    private val host: String = host?.trim() ?: ""

    @Volatile private var socket: Socket? = null
    @Volatile private var out: PrintWriter? = null
    @Volatile private var running = false
    @Volatile private var fatalError = false
    @Volatile private var backupRank = -1
    private var keepaliveThread: Thread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mesh-node-write").apply { isDaemon = true }
    }

    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mesh-node-send").apply { isDaemon = true }
    }

    private val pending = OutboundQueue()
    private val pendingAcks = ConcurrentHashMap<String, String>()

    fun start() {
        if (host.isEmpty()) {
            Log.w(TRACE, "MeshNode: refusing to start, host is empty")
            listener?.onStatus("No host onion set — re-scan the QR or paste " +
                "the credentials again")
            fatalError = true
            running = false
            return
        }

        running = true
        listener?.onStatus("Connecting to ${shortHost(host)}…")
        val t = Thread({ connectWithRetries() }, "mesh-node")
        t.isDaemon = true
        t.start()
    }

    private fun connectWithRetries() {
        var round = 0
        var backoff = BACKOFF_MIN_MS
        var handshakeFailures = 0

        while (running && !fatalError) {
            round++
            listener?.onStatus("Connecting to ${shortHost(host)} (round $round)")
            Log.i(TRACE, "MeshNode: round $round target=$host:$port backoff=${backoff}ms")

            val winner = raceConnections()
            if (winner == null) {
                if (!running || fatalError) return
                listener?.onStatus("Retry in ${backoff / 1000}s…")
                try { Thread.sleep(backoff) }
                catch (_: InterruptedException) { return }
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }

            socket = winner
            try {
                winner.tcpNoDelay = true
                winner.keepAlive = true
                val w = PrintWriter(winner.getOutputStream(), true)
                out = w
                w.println(Protocol.pack(Protocol.HELLO, myName))
                if (wantBackup) {
                    w.println(Protocol.pack(Protocol.BACKUP_JOIN, myName))
                    election?.register(ElectionCoordinator.Candidate(
                        myName, System.currentTimeMillis()))
                }
                w.flush()

                resendPendingAcks()

                listener?.onStatus("Handshaking with host…")
                val firstLine = awaitFirstLine(winner)

                if (firstLine == null) {
                    handshakeFailures++
                    Log.w(TRACE, "MeshNode: handshake timeout (failure #$handshakeFailures)")
                    if (handshakeFailures >= HANDSHAKE_FAILURE_LIMIT) {
                        listener?.onStatus(
                            "Can't reach host. Check that the host device is " +
                            "online and Tor has finished bootstrapping there.")
                    }
                    closeSocket()
                    if (!running || fatalError) return
                    backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                    listener?.onStatus("Retry in ${backoff / 1000}s…")
                    try { Thread.sleep(backoff) }
                    catch (_: InterruptedException) { return }
                    continue
                }

                handshakeFailures = 0
                backoff = BACKOFF_MIN_MS

                heartbeat?.setCurrentHost(host)
                heartbeat?.recordSeen(host)

                listener?.onStatus("Connected")
                Log.i(TRACE, "MeshNode: handshake OK first=${firstLine.take(60)}")

                handle(firstLine)
                if (fatalError) return

                startKeepalive()
                continueReadLoop(winner)
            } catch (e: Exception) {
                Log.w(TRACE, "MeshNode post-connect: ${e.message}")
            }
            closeSocket()
            if (!running || fatalError) return
            listener?.onStatus("Reconnecting…")
        }
    }

    private fun awaitFirstLine(s: Socket): String? {
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        try {
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
        } catch (e: Exception) {
            Log.w(TRACE, "MeshNode: could not set handshake timeout: ${e.message}")
        }
        return try {
            reader.readLine()
        } catch (e: SocketTimeoutException) {
            null
        } finally {
            try { s.soTimeout = 0 } catch (_: Exception) {}
        }
    }

    private fun continueReadLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            var line = reader.readLine()
            while (running && line != null) {
                heartbeat?.recordSeen(host)
                handle(line)
                if (fatalError) return
                line = reader.readLine()
            }
        } catch (e: Exception) {
            Log.w(TRACE, "MeshNode readLoop ended: ${e.message}")
        }
    }

    private fun resendPendingAcks() {
        if (pendingAcks.isEmpty()) return
        val w = out ?: return
        val n = pendingAcks.size
        Log.i(TRACE, "MeshNode: resending $n un-ACKed message" +
            (if (n == 1) "" else "s"))
        for (line in pendingAcks.values) {
            try { w.println(line) }
            catch (e: Exception) {
                Log.w(TRACE, "MeshNode: resend failed: ${e.message}")
                return
            }
        }
        try { w.flush() } catch (_: Exception) {}
    }

    private fun raceConnections(): Socket? {
        var socksPort = TorManager.get(ctx).getSocksPort()
        if (socksPort <= 0) socksPort = TorManager.DEFAULT_SOCKS_PORT
        val fPort = socksPort

        val pool = Executors.newFixedThreadPool(ATTEMPTS_PER_ROUND)
        val futures = ArrayList<java.util.concurrent.Future<Socket>>()
        val claimed = AtomicBoolean(false)

        for (i in 0 until ATTEMPTS_PER_ROUND) {
            val idx = i
            futures.add(pool.submit(Callable {
                var s: Socket? = null
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", fPort))
                    s = Socket(proxy)
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress.createUnresolved(host, port),
                        ATTEMPT_TIMEOUT_MS)

                    if (!claimed.compareAndSet(false, true)) {
                        try { s.close() } catch (_: Exception) {}
                        return@Callable null
                    }
                    s
                } catch (e: Exception) {
                    try { s?.close() } catch (_: Exception) {}
                    if (idx == 0) {
                        Log.i(TRACE, "MeshNode: attempt failed: " +
                            "${e.javaClass.simpleName} ${e.message}")
                    }
                    null
                }
            }))
        }

        var winner: Socket? = null
        try {
            for (f in futures) {
                try {
                    val s = f.get((ATTEMPT_TIMEOUT_MS + 1500).toLong(),
                        TimeUnit.MILLISECONDS)
                    if (s != null && winner == null) winner = s
                } catch (_: Exception) {}
            }
        } finally {
            pool.shutdownNow()
        }
        return winner
    }

    private fun startKeepalive() {
        if (keepaliveThread?.isAlive == true) return
        keepaliveThread = Thread({
            while (running && socket?.isClosed == false) {
                try { out?.let { it.println(Protocol.pack(Protocol.PING, myName)); it.flush() } }
                catch (_: Exception) {}
                try { Thread.sleep(10000) }
                catch (_: InterruptedException) { return@Thread }
            }
        }, "mesh-node-keepalive").also { it.isDaemon = true; it.start() }
    }

    private fun shortHost(h: String?): String {
        if (h == null) return "?"
        return if (h.length > 16) h.substring(0, 12) + "…" else h
    }

    fun sendPing() {
        writeExecutor.execute {
            try { out?.let { it.println(Protocol.pack(Protocol.PING, myName)); it.flush() } }
            catch (_: Exception) {}
        }
    }

    fun sendRead(msgId: String?) {
        if (msgId.isNullOrEmpty()) return
        writeExecutor.execute {
            try {
                out?.let {
                    it.println(Protocol.pack(Protocol.READ, msgId, myName))
                    it.flush()
                }
            } catch (_: Exception) {}
        }
    }

    private fun handle(line: String) {
        try {
            val p = Protocol.unpack(line)
            if (p.isEmpty()) return
            val type = p[0]

            when (type) {
                Protocol.GROUPKEY -> {
                    if (p.size >= 2 && p[1].isNotEmpty()) {
                        keyHolder.setFromBase64(p[1])
                        SessionStore(ctx).setGroupKey(p[1])
                        listener?.onStatus("Group key received")
                    }
                }
                Protocol.MSG -> {
                    if (p.size < 2) return
                    val msgId = if (p.size >= 3) p[1] else null
                    val cipher = if (p.size >= 3) p[2] else p[1]

                    if (msgId != null && replica.seen(msgId)) {
                        sendAck(msgId)
                        return
                    }
                    if (!replica.contains(line)) replica.append(line)

                    val k = keyHolder.get()
                    if (k == null) {
                        Log.w(TRACE, "MeshNode: MSG before key, dropping")
                        return
                    }
                    val plain = CryptoUtils.decrypt(cipher, k)
                    parsePlainAndDispatch(plain)

                    if (msgId != null) sendAck(msgId)
                }
                Protocol.TYPING -> {
                    if (p.size >= 2 && p[1].isNotEmpty()) listener?.onTyping(p[1])
                }
                Protocol.ACK -> {
                    if (p.size >= 3) {
                        pendingAcks.remove(p[1])
                        listener?.onAck(p[1], p[2])
                    } else if (p.size >= 2) {
                        pendingAcks.remove(p[1])
                        listener?.onAck(p[1], "_host")
                    }
                }
                Protocol.READ -> {
                    if (p.size >= 3) listener?.onRead(p[1], p[2])
                }
                Protocol.PONG -> {}
                Protocol.NAMES -> {
                    if (p.size >= 2) {
                        val names = p[1].split(",").filter { it.isNotEmpty() }
                        listener?.onNames(names)
                    }
                }
                Protocol.LEADER_GONE -> {
                    if (p.size >= 2) {
                        heartbeat?.forceDead()
                        closeSocket()
                    }
                }
                Protocol.FULL -> {
                    val info = if (p.size >= 2) p[1] else "?"
                    fatalError = true
                    running = false
                    listener?.onStatus("Group is full ($info)")
                    try { socket?.close() } catch (_: Exception) {}
                }
                Protocol.BACKUP_FULL -> {
                    val info = if (p.size >= 2) p[1] else "?"
                    listener?.onStatus("Backup slots full ($info)")
                }
                Protocol.BACKUP_RANK -> {
                    if (p.size >= 3) {
                        backupRank = p[1].toIntOrNull() ?: -1
                        val joinedAt = p[2].toLongOrNull()
                        if (joinedAt != null) {
                            election?.register(ElectionCoordinator.Candidate(myName, joinedAt))
                        }
                    }
                }
                Protocol.CANDIDATE -> {
                    if (p.size >= 3) {
                        val joinedAt = p[2].toLongOrNull()
                        if (joinedAt != null) {
                            election?.register(ElectionCoordinator.Candidate(p[1], joinedAt))
                        }
                    }
                }
                Protocol.REPLAY_BEGIN -> listener?.onClearHistory()
                Protocol.ROSTER -> {
                    if (p.size >= 5) {
                        val c = p[1].toIntOrNull() ?: 0
                        val mc = p[2].toIntOrNull() ?: 0
                        val b = p[3].toIntOrNull() ?: 0
                        val mb = p[4].toIntOrNull() ?: 0
                        listener?.onRoster(c, mc, b, mb)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TRACE, "MeshNode handle failed: ${e.message}")
        }
    }

    private fun parsePlainAndDispatch(plain: String) {
        val parts = plain.split("\u0001")

        // REACT|sender|targetSig|emoji — a reaction event, not a chat
        // message. Must be routed through onReaction so the UI attaches
        // it to the target bubble. Falling through to the generic size
        // branches below would render the emoji as a standalone bubble.
        if (parts.isNotEmpty() && parts[0] == "REACT" && parts.size >= 4) {
            listener?.onReaction(parts[1], parts[2], parts[3])
            return
        }

        val cm: PeerState.ChatMessage? = when {
            // PHOTO: PHOTO|sender|ts|ttl|mime|b64 — 6 fields. Must be checked
            // before the generic 6-field MSG-with-reply branch, otherwise the
            // ttl/mime/base64 get misread as reply metadata and body.
            parts[0] == "PHOTO" && parts.size >= 6 -> {
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val ttl = parts[3].toLongOrNull() ?: 0L
                val exp = if (ttl > 0) ts + ttl else 0L
                PeerState.ChatMessage(parts[1], "[photo]", myName == parts[1],
                    ts, null, null, exp, parts[5], parts[4])
            }
            parts.size >= 7 -> {
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val ttl = parts[6].toLongOrNull() ?: 0L
                val exp = if (ttl > 0) ts + ttl else 0L
                val rs = parts[3].ifEmpty { null }
                val rb = parts[4].ifEmpty { null }
                PeerState.ChatMessage(parts[1], parts[5],
                    myName == parts[1], ts, rs, rb, exp)
            }
            parts.size == 6 -> {
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val rs = parts[3].ifEmpty { null }
                val rb = parts[4].ifEmpty { null }
                PeerState.ChatMessage(parts[1], parts[5], myName == parts[1],
                    ts, rs, rb, 0L)
            }
            parts.size == 5 -> {
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val ttl = parts[4].toLongOrNull() ?: 0L
                val exp = if (ttl > 0) ts + ttl else 0L
                PeerState.ChatMessage(parts[1], parts[3], myName == parts[1],
                    ts, null, null, exp)
            }
            parts.size >= 4 -> {
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                PeerState.ChatMessage(parts[1], parts[3], myName == parts[1], ts)
            }
            parts.size >= 3 -> PeerState.ChatMessage(parts[1], parts[2],
                myName == parts[1])
            else -> null
        }
        if (cm != null) listener?.onMessage(cm)
    }

    private fun sendAck(msgId: String) {
        writeExecutor.execute {
            try {
                out?.let {
                    it.println(Protocol.pack(Protocol.ACK, msgId, myName))
                    it.flush()
                }
            } catch (_: Exception) {}
        }
    }

    fun getBackupRank(): Int = backupRank
    fun isFatalError(): Boolean = fatalError

    @JvmOverloads
    fun sendChat(text: String, msgId: String?,
                 replySender: String? = null, replyBody: String? = null,
                 ttlMs: Long = 0L) {
        sendExecutor.execute {
            try {
                val ts = System.currentTimeMillis()
                val safeBody = text.replace('\u0001', ' ')
                val safeReplyBody = replyBody?.replace('\u0001', ' ') ?: ""
                val plain = if (replySender != null) {
                    "MSG\u0001$myName\u0001$ts\u0001$replySender" +
                        "\u0001$safeReplyBody\u0001$safeBody\u0001$ttlMs"
                } else {
                    "MSG\u0001$myName\u0001$ts\u0001$safeBody\u0001$ttlMs"
                }
                val k = keyHolder.get()
                if (k == null) {
                    Log.w(TRACE, "MeshNode: cannot send, no key yet")
                    notifySendFailed(msgId, "No group key yet")
                    return@execute
                }
                val cipher = CryptoUtils.encrypt(plain, k)
                val line = if (msgId != null)
                    Protocol.pack(Protocol.MSG, msgId, cipher)
                else Protocol.pack(Protocol.MSG, cipher)
                replica.append(line)
                if (msgId != null) pendingAcks[msgId] = line

                val w = out
                if (w == null) {
                    pending.enqueue(line)
                    return@execute
                }
                try { w.println(line); w.flush() }
                catch (e: Exception) {
                    Log.w(TRACE, "MeshNode: write failed: ${e.message}")
                    notifySendFailed(msgId, "Connection lost")
                }
            } catch (e: Exception) {
                Log.e(TRACE, "MeshNode send failed", e)
                notifySendFailed(msgId, e.message)
            }
        }
    }

    fun sendPhoto(b64: String?, mime: String?, msgId: String, ttlMs: Long) {
        sendExecutor.execute {
            try {
                val ts = System.currentTimeMillis()
                val plain = "PHOTO\u0001$myName\u0001$ts\u0001$ttlMs\u0001" +
                    "${mime ?: "image/jpeg"}\u0001${b64 ?: ""}"
                val k = keyHolder.get()
                if (k == null) {
                    notifySendFailed(msgId, "No group key yet")
                    return@execute
                }
                val cipher = CryptoUtils.encrypt(plain, k)
                val line = Protocol.pack(Protocol.MSG, msgId, cipher)
                replica.append(line)
                pendingAcks[msgId] = line

                val w = out
                if (w == null) { pending.enqueue(line); return@execute }
                try {
                    w.println(line)
                    w.flush()
                } catch (_: Exception) {
                    notifySendFailed(msgId, "Connection lost")
                }
            } catch (e: Exception) {
                notifySendFailed(msgId, e.message)
            }
        }
    }

    fun sendReaction(targetSig: String?, emoji: String?, msgId: String) {
        sendExecutor.execute {
            try {
                val safeSig = targetSig?.replace('\u0001', ' ') ?: ""
                val plain = "REACT\u0001$myName\u0001$safeSig\u0001${emoji ?: "👍"}"
                val k = keyHolder.get() ?: return@execute
                val cipher = CryptoUtils.encrypt(plain, k)
                val line = Protocol.pack(Protocol.MSG, msgId, cipher)
                replica.append(line)
                pendingAcks[msgId] = line

                val w = out
                if (w == null) { pending.enqueue(line); return@execute }
                try { w.println(line); w.flush() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    private fun notifySendFailed(msgId: String?, reason: String?) {
        if (msgId == null) return
        try { listener?.onSendFailed(msgId, reason ?: "Send failed") }
        catch (_: Exception) {}
    }

    fun sendTyping() {
        writeExecutor.execute {
            try { out?.let { it.println(Protocol.pack(Protocol.TYPING, myName)); it.flush() } }
            catch (_: Exception) {}
        }
    }

    fun getPendingCount(): Int = pendingAcks.size

    fun stop() {
        running = false
        pending.clear()
        closeSocket()
        try { writeExecutor.shutdownNow() } catch (_: Exception) {}
        try { sendExecutor.shutdownNow() } catch (_: Exception) {}
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    companion object {
        private const val TRACE = "MeshTrace"
        private const val ATTEMPTS_PER_ROUND = 6
        private const val ATTEMPT_TIMEOUT_MS = 6000
        private const val BACKOFF_MIN_MS = 500L
        private const val BACKOFF_MAX_MS = 15_000L
        private const val HANDSHAKE_TIMEOUT_MS = 25_000
        private const val HANDSHAKE_FAILURE_LIMIT = 3
    }
}