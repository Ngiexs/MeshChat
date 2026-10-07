package com.bitzlink

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TorManager private constructor(private val ctx: Context) {

    interface Listener {
        fun onTorReady(socksPort: Int, onionAddress: String?)
        fun onError(err: String?)
        fun onProgress(percent: Int)
    }

    abstract class SimpleListener : Listener {
        override fun onProgress(percent: Int) {}
    }

    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var runningHostMode = false
    @Volatile private var ready = false
    @Volatile private var torProcess: Process? = null
    @Volatile private var onionAddress: String? = null
    @Volatile private var socksPort = 0
    @Volatile private var currentGroup = ""

    private val pendingListeners = ArrayList<Listener>()
    private val generation = AtomicLong(0)

    fun getSocksPort(): Int = if (socksPort > 0) socksPort else DEFAULT_SOCKS_PORT

    fun getCurrentGroup(): String = currentGroup

    fun setActiveGroup(groupName: String?) {
        currentGroup = GroupRegistry.sanitize(groupName)
    }

    private fun getHsDir(safeGroup: String?): File {
        val filesDir = ctx.filesDir
        return if (safeGroup.isNullOrEmpty()) File(filesDir, "tor_hs")
        else File(filesDir, "tor_hs${File.separator}$safeGroup")
    }

    private fun pickFreePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: Exception) {
        Log.w(TRACE, "TorManager: pickFreePort failed, using default")
        DEFAULT_SOCKS_PORT
    }

    @JvmOverloads
    fun start(publishHiddenService: Boolean, listener: Listener?,
              groupName: String = "") {
        var notifyNow = false
        var portToSend = 0
        var onionToSend: String? = null

        val safeGroup = GroupRegistry.sanitize(groupName)

        synchronized(lock) {
            val sameMode = runningHostMode == publishHiddenService
            val sameGroup = safeGroup == currentGroup

            if (running && sameMode && sameGroup) {
                if (ready) {
                    notifyNow = true
                    portToSend = getSocksPort()
                    onionToSend = onionAddress
                } else if (listener != null) {
                    pendingListeners.add(listener)
                }
            } else {
                if (running) {
                    Log.i(TRACE, "TorManager: switching group '$currentGroup' -> " +
                        "'$safeGroup' (host=$publishHiddenService)")
                    stopLocked()
                }
                val myGen = generation.incrementAndGet()
                running = true
                runningHostMode = publishHiddenService
                currentGroup = safeGroup
                ready = false
                onionAddress = null
                if (listener != null) pendingListeners.add(listener)

                val t = Thread({
                    runTor(safeGroup, publishHiddenService, myGen)
                }, "tor-manager")
                t.isDaemon = true
                t.start()
            }
        }

        if (notifyNow && listener != null) {
            try { listener.onTorReady(portToSend, onionToSend) }
            catch (_: Exception) {}
        }
    }

    fun stop() {
        synchronized(lock) {
            generation.incrementAndGet()
            stopLocked()
        }
    }

    fun isRunning(): Boolean = running
    fun isReady(): Boolean = ready
    fun isHostMode(): Boolean = runningHostMode

    fun getOnionAddress(): String? {
        val cached = onionAddress
        if (!cached.isNullOrEmpty()) return cached

        try {
            val hsDir = getHsDir(currentGroup)
            val h = File(hsDir, "hostname")
            if (!h.exists()) return null
            BufferedReader(FileReader(h)).use { r ->
                val line = r.readLine()?.trim()
                if (line != null) {
                    onionAddress = line
                    return line
                }
            }
        } catch (e: Exception) {
            Log.w(TRACE, "TorManager: read hostname failed: ${e.message}")
        }
        return null
    }

    // ── Pre-flight probe helpers ─────────────────────────────────────

    fun hasExistingHsIdentity(groupName: String?): Boolean {
        val hsDir = getHsDir(GroupRegistry.sanitize(groupName))
        val hostnameFile = File(hsDir, "hostname")
        val secretFile = File(hsDir, "hs_ed25519_secret_key")
        return hostnameFile.exists() && secretFile.exists()
    }

    fun readOnionFromDisk(groupName: String?): String? = try {
        val hsDir = getHsDir(GroupRegistry.sanitize(groupName))
        val h = File(hsDir, "hostname")
        if (!h.exists()) null
        else BufferedReader(FileReader(h)).use { it.readLine()?.trim() }
    } catch (_: Exception) { null }

    fun probeExistingHost(onion: String?, port: Int,
                          probeName: String, timeoutMs: Long): Boolean {
        if (onion.isNullOrEmpty()) return false
        if (!running || !ready) {
            Log.i(TRACE, "probe: tor not ready, skipping")
            return false
        }
        val sp = getSocksPort()
        if (sp <= 0) return false

        var s: Socket? = null
        try {
            val proxy = Proxy(Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", sp))
            s = Socket(proxy)
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs.toInt()
            s.connect(InetSocketAddress.createUnresolved(onion, port),
                timeoutMs.toInt())

            val out = PrintWriter(s.getOutputStream(), true)
            out.println(Protocol.pack(Protocol.HELLO, probeName))
            out.flush()

            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            if (line == null) {
                Log.i(TRACE, "probe: connected but no bytes, dead")
                return false
            }
            val p = Protocol.unpack(line)
            if (p.isNotEmpty()) {
                val t = p[0]
                if (t == Protocol.GROUPKEY || t == Protocol.ROSTER ||
                    t == Protocol.PONG || t == Protocol.NAMES) {
                    Log.w(TRACE, "probe: live host, type=$t")
                    return true
                }
            }
            Log.i(TRACE, "probe: unexpected first line, dead")
            return false
        } catch (e: Exception) {
            Log.i(TRACE, "probe: ${e.message}")
            return false
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }

    // ── HS bundle import/export ──────────────────────────────────────

    @JvmOverloads
    fun exportHsBundle(groupName: String = currentGroup): String? = try {
        val hsDir = getHsDir(GroupRegistry.sanitize(groupName))
        val hostnameFile = File(hsDir, "hostname")
        val secretFile = File(hsDir, "hs_ed25519_secret_key")
        val publicFile = File(hsDir, "hs_ed25519_public_key")

        if (!hostnameFile.exists() || !secretFile.exists() || !publicFile.exists()) {
            null
        } else {
            val onion = readTextFile(hostnameFile).trim()
            val secret = Base64.encodeToString(readBytesFile(secretFile),
                Base64.NO_WRAP or Base64.URL_SAFE)
            val pub = Base64.encodeToString(readBytesFile(publicFile),
                Base64.NO_WRAP or Base64.URL_SAFE)
            if (onion.isEmpty()) null else "$onion|$secret|$pub"
        }
    } catch (e: Exception) {
        Log.w(TRACE, "exportHsBundle failed: ${e.message}")
        null
    }

    @JvmOverloads
    fun importHsBundle(groupName: String = currentGroup,
                       onion: String?, secretB64: String?,
                       publicB64: String?): Boolean {
        if (onion.isNullOrEmpty() || secretB64.isNullOrEmpty() ||
            publicB64.isNullOrEmpty()) return false
        return try {
            val safe = GroupRegistry.sanitize(groupName)
            val hsDir = getHsDir(safe)
            if (!hsDir.exists() && !hsDir.mkdirs()) return false

            val hostnameFile = File(hsDir, "hostname")
            val secretFile = File(hsDir, "hs_ed25519_secret_key")
            val publicFile = File(hsDir, "hs_ed25519_public_key")

            hostnameFile.delete()
            secretFile.delete()
            publicFile.delete()

            writeTextFile(hostnameFile, onion)
            writeBytesFile(secretFile,
                Base64.decode(secretB64, Base64.NO_WRAP or Base64.URL_SAFE))
            writeBytesFile(publicFile,
                Base64.decode(publicB64, Base64.NO_WRAP or Base64.URL_SAFE))

            secretFile.setReadable(true, true)
            secretFile.setWritable(true, true)
            secretFile.setExecutable(false, false)
            publicFile.setReadable(true, true)
            publicFile.setWritable(true, true)
            hostnameFile.setReadable(true, true)
            hostnameFile.setWritable(true, true)

            if (safe == currentGroup) onionAddress = onion
            Log.i(TRACE, "TorManager: imported HS bundle for group '$safe' " +
                shortOnion(onion))
            true
        } catch (e: Exception) {
            Log.w(TRACE, "importHsBundle failed: ${e.message}")
            false
        }
    }

    private fun shortOnion(o: String?): String {
        if (o == null) return "?"
        if (o.length <= 16) return o
        return o.substring(0, 8) + "..." + o.substring(o.length - 4)
    }

    @Throws(Exception::class)
    private fun readTextFile(f: File): String =
        BufferedReader(FileReader(f)).use { r ->
            val sb = StringBuilder()
            var line = r.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = r.readLine()
            }
            sb.toString()
        }

    @Throws(Exception::class)
    private fun readBytesFile(f: File): ByteArray =
        FileInputStream(f).use { ins ->
            val buf = ByteArray(f.length().toInt())
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            buf
        }

    @Throws(Exception::class)
    private fun writeTextFile(f: File, s: String) {
        FileWriter(f).use { it.write(s) }
    }

    @Throws(Exception::class)
    private fun writeBytesFile(f: File, b: ByteArray) {
        FileOutputStream(f).use { it.write(b) }
    }

    private fun stopLocked() {
        running = false
        ready = false

        val p = torProcess
        torProcess = null

        if (p != null) {
            Log.i(TRACE, "TorManager: stopping tor process")
            p.destroy()
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        socksPort = 0
        pendingListeners.clear()
    }

    private fun runTor(safeGroup: String, publishHiddenService: Boolean,
                       myGen: Long) {
        try {
            val filesDir = ctx.filesDir
            val torDataDir = File(filesDir, "tor_data")
            val hsDir = getHsDir(safeGroup)

            if (!torDataDir.exists() && !torDataDir.mkdirs()) {
                fail("could not create tor_data", myGen)
                return
            }

            if (publishHiddenService) {
                if (!hsDir.exists() && !hsDir.mkdirs()) {
                    fail("could not create tor_hs", myGen)
                    return
                }
            }

            val myPort = pickFreePort()
            synchronized(lock) {
                if (generation.get() != myGen || !running) return
                socksPort = myPort
            }
            Log.i(TRACE, "TorManager: picked SOCKS port $myPort for group '$safeGroup'")

            val torrcFile = File(filesDir, "torrc")
            FileWriter(torrcFile).use { w ->
                w.write("DataDirectory ${torDataDir.absolutePath}\n")
                w.write("SocksPort 127.0.0.1:$myPort\n")
                w.write("Log notice stdout\n")
                w.write("RunAsDaemon 0\n")
                w.write("ClientUseIPv4 1\n")
                w.write("ClientUseIPv6 0\n")
                if (publishHiddenService) {
                    w.write("HiddenServiceDir ${hsDir.absolutePath}\n")
                    w.write("HiddenServicePort 8888 127.0.0.1:8888\n")
                }
            }

            val torBinaryPath = ctx.applicationInfo.nativeLibraryDir + "/libtor.so"
            val torBin = File(torBinaryPath)
            Log.i(TRACE, "TorManager: tor binary at $torBinaryPath")

            if (!torBin.exists()) { fail("libtor.so not found", myGen); return }
            if (!torBin.canExecute()) torBin.setExecutable(true, false)

            val pb = ProcessBuilder(torBinaryPath, "-f", torrcFile.absolutePath)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)

            val proc = pb.start()

            synchronized(lock) {
                if (generation.get() != myGen || !running) {
                    proc.destroyForcibly()
                    return
                }
                torProcess = proc
            }

            Log.i(TRACE, "TorManager: tor process started pid-alive=${proc.isAlive}")

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var announced = false
                var lastPct = -1
                var line = reader.readLine()
                while (line != null) {
                    Log.i(TRACE, "tor: $line")

                    if (!announced && line.contains("Bootstrapped ")) {
                        val idx = line.indexOf("Bootstrapped ")
                        if (idx >= 0) {
                            val end = line.indexOf('%', idx)
                            if (end > idx) {
                                val pctStr = line.substring(idx + 13, end).trim()
                                val pct = pctStr.toIntOrNull()
                                if (pct != null && pct != lastPct) {
                                    lastPct = pct
                                    notifyProgress(pct)
                                }
                            }
                        }
                    }

                    if (!announced && line.contains("Bootstrapped 100%")) {
                        announced = true
                        Log.i(TRACE, "TorManager: bootstrapped for group '$safeGroup'")

                        var onion: String? = null
                        if (publishHiddenService) {
                            val hostname = File(hsDir, "hostname")
                            var i = 0
                            while (i < 15 && !hostname.exists()) {
                                try { Thread.sleep(1000) }
                                catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                i++
                            }
                            if (hostname.exists()) {
                                onion = BufferedReader(FileReader(hostname))
                                    .use { it.readLine() }?.trim()
                            }
                        }
                        notifyReady(onion, myGen)
                    }
                    line = reader.readLine()
                }
            }
            Log.i(TRACE, "TorManager: tor process exited")
        } catch (e: Exception) {
            if (generation.get() == myGen) {
                Log.e(TRACE, "TorManager failed: ${e.message}", e)
                fail(e.message, myGen)
            }
        } finally {
            synchronized(lock) {
                if (generation.get() == myGen) {
                    running = false
                    ready = false
                    torProcess = null
                }
            }
        }
    }

    private fun notifyProgress(pct: Int) {
        val toNotify: List<Listener>
        synchronized(lock) {
            if (!running) return
            toNotify = ArrayList(pendingListeners)
        }
        for (l in toNotify) {
            try { l.onProgress(pct) } catch (_: Exception) {}
        }
    }

    private fun notifyReady(onion: String?, myGen: Long) {
        val toNotify: List<Listener>
        val portAtReady: Int
        synchronized(lock) {
            if (generation.get() != myGen) return
            if (!running) return
            onionAddress = onion
            ready = true
            portAtReady = getSocksPort()
            toNotify = ArrayList(pendingListeners)
            pendingListeners.clear()
        }
        for (l in toNotify) {
            try { l.onTorReady(portAtReady, onion) }
            catch (e: Exception) {
                Log.w(TRACE, "TorManager: listener threw: ${e.message}")
            }
        }
    }

    private fun fail(err: String?, myGen: Long) {
        val toNotify: List<Listener>
        synchronized(lock) {
            if (generation.get() != myGen) return
            running = false
            ready = false
            toNotify = ArrayList(pendingListeners)
            pendingListeners.clear()
        }
        for (l in toNotify) {
            try { l.onError(err) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TRACE = "MeshTrace"
        const val DEFAULT_SOCKS_PORT = 9050

        @Volatile private var sInstance: TorManager? = null

        @JvmStatic
        fun get(ctx: Context): TorManager {
            var inst = sInstance
            if (inst == null) {
                synchronized(TorManager::class.java) {
                    inst = sInstance
                    if (inst == null) {
                        inst = TorManager(ctx.applicationContext)
                        sInstance = inst
                    }
                }
            }
            return inst!!
        }
    }
}