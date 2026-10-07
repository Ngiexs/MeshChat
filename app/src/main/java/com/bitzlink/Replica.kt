package com.bitzlink

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.HashSet

class Replica @JvmOverloads constructor(
    appFilesDir: File,
    groupName: String = ""
) {
    private val file: File
    private val tmpFile: File
    private val messages = ArrayDeque<String>()
    private val seenIds = HashSet<String>()
    private val tombstonedIds = HashSet<String>()

    // Append-only accounting so we know when to rewrite (compact).
    private var liveRecords = 0
    private var deadRecords = 0

    init {
        val safe = GroupRegistry.sanitize(groupName)
        val dir = if (safe.isEmpty()) appFilesDir
        else File(appFilesDir, "groups${File.separator}$safe").also {
            if (!it.exists()) it.mkdirs()
        }
        file = File(dir, FILE_NAME)
        tmpFile = File(dir, "$FILE_NAME.tmp")
        loadFromDisk()
    }

    @Synchronized
    fun append(wireLine: String?) {
        if (wireLine.isNullOrEmpty()) return
        if (!isLikelyWireLine(wireLine)) {
            Log.w(TAG, "append: rejected malformed line")
            return
        }

        val p = Protocol.unpack(wireLine)
        val msgId = if (p.size >= 3 && p[0] == Protocol.MSG) p[1] else null
        if (msgId != null && tombstonedIds.contains(msgId)) return

        messages.addLast(wireLine)
        liveRecords++
        while (messages.size > MAX_MESSAGES) {
            messages.removeFirst()
            liveRecords--
            deadRecords++
        }
        appendLineToDisk(wireLine)

        if (shouldCompact()) compact()
    }

    @Synchronized
    fun contains(wireLine: String?): Boolean =
        wireLine != null && messages.contains(wireLine)

    @Synchronized
    fun seen(msgId: String?): Boolean {
        if (msgId.isNullOrEmpty()) return false
        if (seenIds.contains(msgId)) return true
        seenIds.add(msgId)
        if (seenIds.size > SEEN_CAP) {
            val it = seenIds.iterator()
            var removed = 0
            while (it.hasNext() && removed < SEEN_CAP / 2) {
                it.next(); it.remove(); removed++
            }
        }
        return false
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(messages)

    @Synchronized
    fun size(): Int = messages.size

    @Synchronized
    fun clear() {
        messages.clear()
        seenIds.clear()
        tombstonedIds.clear()
        liveRecords = 0
        deadRecords = 0
        try {
            if (file.exists()) file.delete()
            if (tmpFile.exists()) tmpFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    @Synchronized
    fun removeById(msgId: String?) {
        if (msgId.isNullOrEmpty()) return
        if (tombstonedIds.contains(msgId)) return
        tombstonedIds.add(msgId)

        var changed = false
        val it = messages.iterator()
        while (it.hasNext()) {
            val p = Protocol.unpack(it.next())
            if (p.size >= 3 && p[0] == Protocol.MSG && msgId == p[1]) {
                it.remove(); changed = true; liveRecords--
            }
        }
        if (changed) {
            appendLineToDisk(TOMBSTONE_PREFIX + msgId)
            deadRecords++
            if (shouldCompact()) compact()
        }
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        try {
            val rawLines = ArrayList<String>()
            BufferedReader(FileReader(file)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    rawLines.add(line)
                    line = r.readLine()
                }
            }

            // If the file doesn't end in a newline, the last write was
            // truncated. Drop the tail rather than trying to salvage it.
            val endsWithNewline = file.length() > 0 && runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - 1)
                    raf.read() == '\n'.code
                }
            }.getOrDefault(true)
            if (!endsWithNewline && rawLines.isNotEmpty()) {
                rawLines.removeAt(rawLines.size - 1)
            }

            // Pass 1: collect tombstones.
            for (line in rawLines) {
                if (line.startsWith(TOMBSTONE_PREFIX)) {
                    val id = line.substring(TOMBSTONE_PREFIX.length)
                    if (id.isNotEmpty()) tombstonedIds.add(id)
                }
            }
            // Pass 2: apply.
            for (line in rawLines) {
                if (line.isEmpty()) { deadRecords++; continue }
                if (line.startsWith(TOMBSTONE_PREFIX)) { deadRecords++; continue }
                if (!isLikelyWireLine(line)) { deadRecords++; continue }
                val p = Protocol.unpack(line)
                val msgId = if (p.size >= 3 && p[0] == Protocol.MSG) p[1] else null
                if (msgId != null && tombstonedIds.contains(msgId)) {
                    deadRecords++
                    continue
                }
                messages.addLast(line)
                liveRecords++
                while (messages.size > MAX_MESSAGES) {
                    messages.removeFirst()
                    liveRecords--
                    deadRecords++
                }
            }

            if (shouldCompact()) compact()
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun appendLineToDisk(line: String) {
        try {
            FileOutputStream(file, true).use { fos ->
                val w = OutputStreamWriter(fos, Charsets.UTF_8)
                w.write(line)
                w.write("\n")
                w.flush()
                try { fos.fd.sync() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    private fun shouldCompact(): Boolean {
        val total = liveRecords + deadRecords
        if (total < COMPACT_MIN_RECORDS) return false
        if (deadRecords == 0 && total < MAX_MESSAGES + COMPACT_HEADROOM) return false
        val ratio = deadRecords.toDouble() / total.toDouble()
        return ratio > COMPACT_DEAD_RATIO || total > MAX_MESSAGES + COMPACT_HEADROOM
    }

    private fun compact() {
        try {
            FileOutputStream(tmpFile, false).use { fos ->
                val w = OutputStreamWriter(fos, Charsets.UTF_8)
                for (m in messages) { w.write(m); w.write("\n") }
                w.flush()
                try { fos.fd.sync() } catch (_: Exception) {}
            }
            if (!tmpFile.renameTo(file)) {
                file.delete()
                if (!tmpFile.renameTo(file)) {
                    Log.w(TAG, "compact rename failed")
                    try { tmpFile.delete() } catch (_: Exception) {}
                    return
                }
            }
            liveRecords = messages.size
            deadRecords = 0
        } catch (e: Exception) {
            Log.w(TAG, "compact failed: ${e.message}")
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    private fun isLikelyWireLine(line: String): Boolean {
        if (line.isEmpty()) return false
        if (line.startsWith(TOMBSTONE_PREFIX)) return true
        val pipe = line.indexOf('|')
        if (pipe <= 0) return false
        return line.substring(0, pipe) in KNOWN_TYPES
    }

    companion object {
        private const val TAG = "Replica"
        private const val MAX_MESSAGES = 1000
        private const val SEEN_CAP = 3000
        private const val FILE_NAME = "replica.log"
        private const val TOMBSTONE_PREFIX = "\u0002DEL\u0001"
        private const val COMPACT_MIN_RECORDS = 200
        private const val COMPACT_HEADROOM = 200
        private const val COMPACT_DEAD_RATIO = 0.5

        private val KNOWN_TYPES = setOf(
            Protocol.MSG, Protocol.HELLO, Protocol.ACK, Protocol.READ,
            Protocol.ROSTER, Protocol.NAMES, Protocol.GROUPKEY,
            Protocol.TYPING, Protocol.PING, Protocol.PONG,
            Protocol.CANDIDATE, Protocol.BACKUP_JOIN, Protocol.BACKUP_RANK,
            Protocol.LEADER_GONE, Protocol.FULL, Protocol.BACKUP_FULL,
            Protocol.REPLAY_BEGIN, Protocol.CLAIM
        )
    }
}