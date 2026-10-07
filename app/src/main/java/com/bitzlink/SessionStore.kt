package com.bitzlink

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.HashSet

class SessionStore {

    private val prefs: SharedPreferences
    private val crypto: KeyStoreHelper

    constructor(ctx: Context) {
        val active = GroupRegistry(ctx).getActiveGroup()
        val safe = GroupRegistry.sanitize(active)
        prefs = ctx.getSharedPreferences(
            if (safe.isEmpty()) LEGACY_PREFS else GROUP_PREFIX + safe,
            Context.MODE_PRIVATE)
        crypto = KeyStoreHelper(ctx)
    }

    constructor(ctx: Context, groupName: String) {
        val safe = GroupRegistry.sanitize(groupName)
        prefs = ctx.getSharedPreferences(
            if (safe.isEmpty()) LEGACY_PREFS else GROUP_PREFIX + safe,
            Context.MODE_PRIVATE)
        crypto = KeyStoreHelper(ctx)
    }

    fun save(name: String, peer: String, isHost: Boolean, isBackup: Boolean) {
        prefs.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_PEER, peer)
            .putBoolean(KEY_IS_HOST, isHost)
            .putBoolean(KEY_IS_BACKUP, isBackup)
            .apply()
    }

    fun getName(): String = prefs.getString(KEY_NAME, "") ?: ""
    fun getPeer(): String = prefs.getString(KEY_PEER, "") ?: ""
    fun isHost(): Boolean = prefs.getBoolean(KEY_IS_HOST, false)
    fun isBackup(): Boolean = prefs.getBoolean(KEY_IS_BACKUP, false)
    fun hasSession(): Boolean = getName().isNotEmpty()

    fun isBackupsFull(): Boolean = prefs.getBoolean(KEY_BACKUPS_FULL, false)
    fun setBackupsFull(v: Boolean) =
        prefs.edit().putBoolean(KEY_BACKUPS_FULL, v).apply()

    fun isGroupEstablished(): Boolean = prefs.getBoolean(KEY_ESTABLISHED, false)
    fun setGroupEstablished(v: Boolean) =
        prefs.edit().putBoolean(KEY_ESTABLISHED, v).apply()

    fun shouldAutoConnect(): Boolean = prefs.getBoolean(KEY_AUTOCONNECT, false)
    fun setAutoConnect(v: Boolean) =
        prefs.edit().putBoolean(KEY_AUTOCONNECT, v).apply()

    fun getShardIndex(): Int = prefs.getInt(KEY_SHARD, 0)
    fun setShardIndex(v: Int) = prefs.edit().putInt(KEY_SHARD, v).apply()

    fun getMutedUntil(): Long = prefs.getLong(KEY_MUTED_UNTIL, 0L)
    fun setMutedUntil(v: Long) = prefs.edit().putLong(KEY_MUTED_UNTIL, v).apply()
    fun isMuted(): Boolean = System.currentTimeMillis() < getMutedUntil()

    fun getGroupName(): String = prefs.getString(KEY_GROUP_NAME, "") ?: ""
    fun setGroupName(v: String?) =
        prefs.edit().putString(KEY_GROUP_NAME, v ?: "").apply()

    fun getGroupPassphrase(): String {
        val stored = prefs.getString(KEY_PASSPHRASE, "") ?: ""
        if (stored.isEmpty()) return ""
        if (!crypto.isWrapped(stored) && crypto.isAvailable()) {
            prefs.edit().putString(KEY_PASSPHRASE, crypto.encrypt(stored)).apply()
            return stored
        }
        return crypto.decrypt(stored)
    }

    fun setGroupPassphrase(v: String?) =
        prefs.edit().putString(KEY_PASSPHRASE, crypto.encrypt(v ?: "")).apply()

    fun getGroupKey(): String {
        val stored = prefs.getString(KEY_GROUP_KEY, "") ?: ""
        if (stored.isEmpty()) return ""
        if (!crypto.isWrapped(stored) && crypto.isAvailable()) {
            prefs.edit().putString(KEY_GROUP_KEY, crypto.encrypt(stored)).apply()
            return stored
        }
        return crypto.decrypt(stored)
    }

    fun setGroupKey(b64: String?) =
        prefs.edit().putString(KEY_GROUP_KEY, crypto.encrypt(b64 ?: "")).apply()

    fun getMessageTtlMs(): Long = prefs.getLong(KEY_TTL, 0L)
    fun setMessageTtlMs(v: Long) = prefs.edit().putLong(KEY_TTL, v).apply()

    fun hasSeenChecklist(): Boolean = prefs.getBoolean(KEY_CHECKLIST, false)
    fun setChecklistSeen(v: Boolean) =
        prefs.edit().putBoolean(KEY_CHECKLIST, v).apply()

    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND, true)
    fun setSoundEnabled(v: Boolean) =
        prefs.edit().putBoolean(KEY_SOUND, v).apply()

    fun isQuietEnabled(): Boolean = prefs.getBoolean(KEY_QUIET_EN, false)
    fun setQuietEnabled(v: Boolean) =
        prefs.edit().putBoolean(KEY_QUIET_EN, v).apply()

    fun getQuietStart(): Int = prefs.getInt(KEY_QUIET_START, 22 * 60)
    fun setQuietStart(v: Int) = prefs.edit().putInt(KEY_QUIET_START, v).apply()
    fun getQuietEnd(): Int = prefs.getInt(KEY_QUIET_END, 7 * 60)
    fun setQuietEnd(v: Int) = prefs.edit().putInt(KEY_QUIET_END, v).apply()

    fun isQuietNow(): Boolean {
        if (!isQuietEnabled()) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val start = getQuietStart()
        val end = getQuietEnd()
        if (start == end) return false
        return if (start < end) now in start until end else now >= start || now < end
    }

    fun getLastReadTs(): Long = prefs.getLong(KEY_LAST_READ, 0L)
    fun setLastReadTs(v: Long) = prefs.edit().putLong(KEY_LAST_READ, v).apply()

    fun getDeletedSigs(): MutableSet<String> {
        val raw = prefs.getString(KEY_DELETED, "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }.toMutableSet()
    }

    fun addDeletedSig(sig: String?) {
        if (sig.isNullOrEmpty()) return
        val s = getDeletedSigs()
        s.add(sig)
        if (s.size > DELETED_CAP) {
            val it = s.iterator()
            var removed = 0
            while (it.hasNext() && removed < s.size - DELETED_CAP) {
                it.next(); it.remove(); removed++
            }
        }
        prefs.edit().putString(KEY_DELETED, s.joinToString(",")).apply()
    }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        private const val LEGACY_PREFS = "bitzlink_session"
        private const val GROUP_PREFIX = "bitzlink_group_"

        private const val KEY_NAME = "name"
        private const val KEY_PEER = "peer"
        private const val KEY_IS_HOST = "is_host"
        private const val KEY_IS_BACKUP = "is_backup"
        private const val KEY_BACKUPS_FULL = "backups_full"
        private const val KEY_ESTABLISHED = "group_established"
        private const val KEY_AUTOCONNECT = "auto_connect"
        private const val KEY_SHARD = "shard_index"
        private const val KEY_MUTED_UNTIL = "muted_until"
        private const val KEY_GROUP_NAME = "group_name"
        private const val KEY_PASSPHRASE = "group_passphrase"
        private const val KEY_CHECKLIST = "checklist_seen"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_QUIET_EN = "quiet_enabled"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        private const val KEY_LAST_READ = "last_read_ts"
        private const val KEY_DELETED = "deleted_sigs"
        private const val KEY_GROUP_KEY = "group_key"
        private const val KEY_TTL = "message_ttl_ms"
        private const val DELETED_CAP = 500
    }
}