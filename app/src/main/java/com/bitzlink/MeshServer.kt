package com.bitzlink

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

class MeshServer(
    private val myName: String,
    private val keyHolder: GroupKeyHolder,
    private val listenPort: Int,
    private val listener: Listener?,
    private val replica: Replica
) {

    interface Listener {
        fun onMessage(m: PeerState.ChatMessage)
        fun onStatus(status: String?)
        fun onTyping(who: String)
        fun onBackupsChanged(backups: List<String>?)
        fun onRoster(clients: Int, maxClients: Int, backups: Int, maxBackups: Int)
        fun onNames(names: List<String>?)
        fun onAck(msgId: String, acker: String)
        fun onRead(msgId: String, reader: String)
    }

    private val pool = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val clients = ConcurrentHashMap<String, PrintWriter>()
    private val backups = ConcurrentHashMap<String, String>()
    private val msgSenders = ConcurrentHashMap<String, String>()
    private val lastTypingAt = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null

    fun start() {
        pool.submit { acceptLoop() }
        scheduler.scheduleAtFixedRate({ rebroadcastCandidates() },
            CANDIDATE_REBROADCAST_MS, CANDIDATE_REBROADCAST_MS,
            TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        for (w in clients.values) {
            try { w.close() } catch (_: Exception) {}
        }
        clients.clear()
        backups.clear()
        msgSenders.clear()
        lastTypingAt.clear()
        pool.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(listenPort)
            serverSocket = ss
            Log.i(TRACE, "MeshServer: bound successfully on $listenPort")
            listener?.onStatus("Host listening on $listenPort")
            while (running.get()) {
                val s = ss.accept()
                pool.submit { handleClient(s) }
            }
        } catch (e: Exception) {
            Log.w(TRACE, "MeshServer acceptLoop stopped: ${e.message}")
        }
    }

    private fun handleClient(s: Socket) {
        var clientName: String? = null
        var accepted = false
        var myWriter: PrintWriter? = null
        try {
            s.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val output = PrintWriter(s.getOutputStream(), true)
            myWriter = output

            var line = input.readLine()
            while (running.get() && line != null) {
                try {
                    val p = Protocol.unpack(line)
                    if (p.isEmpty()) { line = input.readLine(); continue }
                    val type = p[0]

                    when (type) {
                        Protocol.HELLO -> {
                            if (p.size >= 2) {
                                synchronized(output) {
                                    val gkB64 = keyHolder.toBase64()
                                    if (!gkB64.isNullOrEmpty()) {
                                        output.println(Protocol.pack(
                                            Protocol.GROUPKEY, gkB64))
                                    }
                                    val proposed = p[1]
                                    var full: Boolean
                                    synchronized(clients) {
                                        full = clients.size >= MAX_CLIENTS &&
                                            !clients.containsKey(proposed)
                                        if (!full) {
                                            clients[proposed] = output
                                            accepted = true
                                            clientName = proposed
                                        }
                                    }
                                    if (full) {
                                        output.println(Protocol.pack(Protocol.FULL,
                                            "${clients.size}/$MAX_CLIENTS"))
                                        return
                                    }
                                    output.println(Protocol.pack(Protocol.ROSTER,
                                        "${clients.size}|$MAX_CLIENTS|" +
                                        "${backups.size}|$MAX_BACKUPS"))
                                    Log.i(TRACE, "MeshServer: client HELLO from " +
                                        "$clientName (${clients.size}/$MAX_CLIENTS)")
                                    listener?.onStatus("$clientName joined " +
                                        "(${clients.size}/$MAX_CLIENTS)")
                                    output.println("PONG|$myName")
                                    replayTo(output)
                                }
                                broadcastRoster()
                            }
                        }
                        Protocol.BACKUP_JOIN -> {
                            if (p.size >= 2) {
                                val backupName = p[1]
                                var rank = -1
                                var joinedAt = System.currentTimeMillis()
                                var backupFull: Boolean
                                synchronized(backups) {
                                    backupFull = backups.size >= MAX_BACKUPS &&
                                        !backups.containsKey(backupName)
                                    if (!backupFull) {
                                        if (!backups.containsKey(backupName)) {
                                            rank = backups.size
                                            backups[backupName] = joinedAt.toString()
                                        } else {
                                            joinedAt = backups[backupName]?.toLongOrNull()
                                                ?: System.currentTimeMillis()
                                            rank = backups.keys.toList().indexOf(backupName)
                                        }
                                    }
                                }
                                if (backupFull) {
                                    safeWrite(output, Protocol.pack(
                                        Protocol.BACKUP_FULL,
                                        "${backups.size}/$MAX_BACKUPS"))
                                } else {
                                    safeWrite(output, Protocol.pack(
                                        Protocol.BACKUP_RANK, "$rank|$joinedAt"))
                                    for ((n, t) in backups) {
                                        safeWrite(output, Protocol.pack(
                                            Protocol.CANDIDATE, "$n|$t"))
                                    }
                                    broadcastExcept(backupName, Protocol.pack(
                                        Protocol.CANDIDATE, "$backupName|$joinedAt"))
                                    listener?.onStatus("$backupName is backup #$rank")
                                    listener?.onBackupsChanged(backups.keys.toList())
                                    broadcastRoster()
                                }
                            }
                        }
                        Protocol.CANDIDATE -> broadcastExcept(clientName, line)
                        Protocol.MSG -> {
                            var msgId: String? = null
                            var cipher: String? = null
                            if (p.size >= 3) { msgId = p[1]; cipher = p[2] }
                            else if (p.size == 2) cipher = p[1]

                            if (msgId != null && replica.seen(msgId)) {
                                safeWrite(output, Protocol.pack(Protocol.ACK, msgId, "_host"))
                                line = input.readLine(); continue
                            }
                            replica.append(line)
                            if (msgId != null) {
                                msgSenders[msgId] = clientName ?: ""
                                trimMsgSenders()
                                safeWrite(output, Protocol.pack(Protocol.ACK, msgId, "_host"))
                            }
                            broadcastExcept(clientName, line)

                            if (cipher != null) {
                                val k = keyHolder.get()
                                if (k == null) {
                                    Log.w(TRACE, "MeshServer: MSG before key ready")
                                } else {
                                    try {
                                        val plain = CryptoUtils.decrypt(cipher, k)
                                        val parts = plain.split("\u0001")
                                        val cm: PeerState.ChatMessage? = when {
                                            parts.size >= 7 -> {
                                                val ts = parts[2].toLongOrNull()
                                                    ?: System.currentTimeMillis()
                                                val ttl = parts[6].toLongOrNull() ?: 0L
                                                val exp = if (ttl > 0) ts + ttl else 0L
                                                val rs = parts[3].ifEmpty { null }
                                                val rb = parts[4].ifEmpty { null }
                                                PeerState.ChatMessage(parts[1], parts[5],
                                                    false, ts, rs, rb, exp)
                                            }
                                            parts.size == 6 -> {
                                                val ts = parts[2].toLongOrNull()
                                                    ?: System.currentTimeMillis()
                                                val rs = parts[3].ifEmpty { null }
                                                val rb = parts[4].ifEmpty { null }
                                                PeerState.ChatMessage(parts[1], parts[5],
                                                    false, ts, rs, rb, 0L)
                                            }
                                            parts.size == 5 -> {
                                                val ts = parts[2].toLongOrNull()
                                                    ?: System.currentTimeMillis()
                                                val ttl = parts[4].toLongOrNull() ?: 0L
                                                val exp = if (ttl > 0) ts + ttl else 0L
                                                PeerState.ChatMessage(parts[1], parts[3],
                                                    false, ts, null, null, exp)
                                            }
                                            parts.size >= 4 -> {
                                                val ts = parts[2].toLongOrNull()
                                                    ?: System.currentTimeMillis()
                                                PeerState.ChatMessage(parts[1], parts[3],
                                                    false, ts)
                                            }
                                            parts.size >= 3 ->
                                                PeerState.ChatMessage(parts[1], parts[2], false)
                                            else -> null
                                        }
                                        if (cm != null) listener?.onMessage(cm)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        Protocol.ACK -> {
                            if (p.size >= 3) {
                                val ackedMsgId = p[1]
                                val acker = p[2]
                                val origSender = msgSenders[ackedMsgId]
                                when {
                                    origSender == SENDER_HOST ->
                                        listener?.onAck(ackedMsgId, acker)
                                    origSender != null && origSender != clientName ->
                                        clients[origSender]?.let {
                                            safeWrite(it, Protocol.pack(Protocol.ACK,
                                                ackedMsgId, acker))
                                        }
                                }
                            }
                        }
                        Protocol.READ -> {
                            if (p.size >= 3) {
                                val readMsgId = p[1]
                                val reader = p[2]
                                val origSender = msgSenders[readMsgId]
                                when {
                                    origSender == SENDER_HOST ->
                                        listener?.onRead(readMsgId, reader)
                                    origSender != null ->
                                        clients[origSender]?.let {
                                            safeWrite(it, Protocol.pack(Protocol.READ,
                                                readMsgId, reader))
                                        }
                                }
                            }
                        }
                        Protocol.TYPING -> {
                            val now = System.currentTimeMillis()
                            val prev = lastTypingAt[clientName]
                            if (prev != null && now - prev < TYPING_MIN_INTERVAL_MS) {
                                line = input.readLine(); continue
                            }
                            lastTypingAt[clientName ?: ""] = now
                            broadcastExcept(clientName, line)
                            if (p.size >= 2 && p[1].isNotEmpty()) {
                                listener?.onTyping(p[1])
                            }
                        }
                        Protocol.PING -> safeWrite(output, "PONG|$myName")
                        Protocol.CLAIM -> {
                            running.set(false)
                            try { serverSocket?.close() } catch (_: Exception) {}
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TRACE, "MeshServer line parse failed: ${e.message}")
                }
                line = input.readLine()
            }
        } catch (e: Exception) {
            Log.w(TRACE, "MeshServer client session ended")
        } finally {
            // Only tear down our own registration. If a client with the same
            // display name reconnected on a fresh socket, clients[name] now
            // points at the new writer, and we must leave it alone.
            val writer = myWriter
            if (accepted && clientName != null && writer != null) {
                if (clients[clientName] === writer) {
                    clients.remove(clientName)
                    val wasBackup = backups.remove(clientName) != null
                    lastTypingAt.remove(clientName)
                    val it = msgSenders.entries.iterator()
                    while (it.hasNext()) {
                        if (it.next().value == clientName) it.remove()
                    }
                    listener?.onStatus("$clientName left " +
                        "(${clients.size}/$MAX_CLIENTS)")
                    if (wasBackup) listener?.onBackupsChanged(backups.keys.toList())
                    broadcastRoster()
                }
            }
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun trimMsgSenders() {
        if (msgSenders.size <= MSGID_MAP_CAP) return
        val it = msgSenders.keys.iterator()
        var removed = 0
        while (it.hasNext() && removed < msgSenders.size / 2) {
            it.next(); it.remove(); removed++
        }
    }

    private fun replayTo(out: PrintWriter) {
        val hist = replica.snapshot()
        out.println(Protocol.pack(Protocol.REPLAY_BEGIN, hist.size.toString()))
        for (h in hist) out.println(h)
    }

    private fun rebroadcastCandidates() {
        if (!running.get()) return
        for ((n, t) in backups) {
            broadcastAll(Protocol.pack(Protocol.CANDIDATE, "$n|$t"))
        }
        broadcastAll(Protocol.pack(Protocol.PONG, myName))
    }

    private fun broadcastRoster() {
        val c = clients.size
        val b = backups.size
        val roster = "$c|$MAX_CLIENTS|$b|$MAX_BACKUPS"
        broadcastAll(Protocol.pack(Protocol.ROSTER, roster))
        listener?.onRoster(c, MAX_CLIENTS, b, MAX_BACKUPS)

        val names = ArrayList<String>()
        names.add(myName)
        for (n in clients.keys) if (n != myName) names.add(n)
        broadcastAll(Protocol.pack(Protocol.NAMES, names.joinToString(",")))
        listener?.onNames(names)
    }

    private fun broadcastExcept(skipName: String?, line: String) {
        for ((k, v) in clients) {
            if (k == skipName) continue
            pool.submit { safeWrite(v, line) }
        }
    }

    private fun broadcastAll(line: String) {
        for (w in clients.values) {
            pool.submit { safeWrite(w, line) }
        }
    }

    /**
     * Host-authored message. Register the msgId so incoming ACK/READ frames
     * route back to the host UI instead of falling through the client-relay
     * path.
     */
    fun sendFromHost(wireLine: String) {
        val p = Protocol.unpack(wireLine)
        if (p.size >= 3 && p[0] == Protocol.MSG) {
            msgSenders[p[1]] = SENDER_HOST
            trimMsgSenders()
        }
        replica.append(wireLine)
        broadcastAll(wireLine)
    }

    fun sendReadTo(clientName: String?, msgId: String?) {
        if (clientName.isNullOrEmpty() || msgId.isNullOrEmpty()) return
        val w = clients[clientName] ?: return
        safeWrite(w, Protocol.pack(Protocol.READ, msgId, myName))
    }

    fun announceLeaderGone(oldHost: String?) {
        broadcastAll(Protocol.pack(Protocol.LEADER_GONE, oldHost ?: ""))
    }

    fun getBackups(): List<String> = backups.keys.toList()
    fun getClientCount(): Int = clients.size
    fun getBackupCount(): Int = backups.size
    fun isFull(): Boolean = clients.size >= MAX_CLIENTS
    fun areBackupsFull(): Boolean = backups.size >= MAX_BACKUPS

    private fun safeWrite(w: PrintWriter?, line: String?) {
        if (w == null || line == null) return
        try {
            w.println(line)
            if (w.checkError()) Log.w(TRACE, "MeshServer write error")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TRACE = "MeshTrace"
        const val MAX_CLIENTS = 30
        const val MAX_BACKUPS = 5
        private const val SENDER_HOST = "_host"
        private const val CANDIDATE_REBROADCAST_MS = 15000L
        private const val MSGID_MAP_CAP = 500
        private const val TYPING_MIN_INTERVAL_MS = 500L
    }
}