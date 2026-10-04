package com.bitzlink;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class SessionStore {

    private static final String LEGACY_PREFS = "bitzlink_session";
    private static final String GROUP_PREFIX = "bitzlink_group_";

    private static final String KEY_NAME         = "name";
    private static final String KEY_PEER         = "peer";
    private static final String KEY_IS_HOST      = "is_host";
    private static final String KEY_IS_BACKUP    = "is_backup";
    private static final String KEY_BACKUPS_FULL = "backups_full";
    private static final String KEY_ESTABLISHED  = "group_established";
    private static final String KEY_AUTOCONNECT  = "auto_connect";
    private static final String KEY_SHARD        = "shard_index";
    private static final String KEY_MUTED_UNTIL  = "muted_until";
    private static final String KEY_GROUP_NAME   = "group_name";
    private static final String KEY_PASSPHRASE   = "group_passphrase";
    private static final String KEY_CHECKLIST    = "checklist_seen";
    private static final String KEY_SOUND        = "sound_enabled";
    private static final String KEY_QUIET_EN     = "quiet_enabled";
    private static final String KEY_QUIET_START  = "quiet_start";
    private static final String KEY_QUIET_END    = "quiet_end";
    private static final String KEY_LAST_READ    = "last_read_ts";
    private static final String KEY_DELETED      = "deleted_sigs";
    private static final String KEY_GROUP_KEY    = "group_key";
    private static final String KEY_TTL          = "message_ttl_ms";

    private static final int DELETED_CAP = 500;

    private final SharedPreferences prefs;
    private final KeyStoreHelper crypto;

    /** Auto-selects the active group from GroupRegistry. */
    public SessionStore(Context ctx) {
        String active = new GroupRegistry(ctx).getActiveGroup();
        String safe = GroupRegistry.sanitize(active);
        if (safe.length() == 0) {
            this.prefs = ctx.getSharedPreferences(LEGACY_PREFS,
                    Context.MODE_PRIVATE);
        } else {
            this.prefs = ctx.getSharedPreferences(
                    GROUP_PREFIX + safe, Context.MODE_PRIVATE);
        }
        this.crypto = new KeyStoreHelper(ctx);
    }

    /** Explicit group constructor. */
    public SessionStore(Context ctx, String groupName) {
        String safe = GroupRegistry.sanitize(groupName);
        if (safe.length() == 0) {
            this.prefs = ctx.getSharedPreferences(LEGACY_PREFS,
                    Context.MODE_PRIVATE);
        } else {
            this.prefs = ctx.getSharedPreferences(
                    GROUP_PREFIX + safe, Context.MODE_PRIVATE);
        }
        this.crypto = new KeyStoreHelper(ctx);
    }

    public void save(String name, String peer, boolean isHost, boolean isBackup) {
        prefs.edit()
                .putString(KEY_NAME, name)
                .putString(KEY_PEER, peer)
                .putBoolean(KEY_IS_HOST, isHost)
                .putBoolean(KEY_IS_BACKUP, isBackup)
                .apply();
    }

    public String  getName()     { return prefs.getString(KEY_NAME, ""); }
    public String  getPeer()     { return prefs.getString(KEY_PEER, ""); }
    public boolean isHost()      { return prefs.getBoolean(KEY_IS_HOST, false); }
    public boolean isBackup()    { return prefs.getBoolean(KEY_IS_BACKUP, false); }
    public boolean hasSession()  { return getName().length() > 0; }

    public boolean isBackupsFull() { return prefs.getBoolean(KEY_BACKUPS_FULL, false); }
    public void setBackupsFull(boolean v) {
        prefs.edit().putBoolean(KEY_BACKUPS_FULL, v).apply();
    }

    public boolean isGroupEstablished() { return prefs.getBoolean(KEY_ESTABLISHED, false); }
    public void setGroupEstablished(boolean v) {
        prefs.edit().putBoolean(KEY_ESTABLISHED, v).apply();
    }

    public boolean shouldAutoConnect() { return prefs.getBoolean(KEY_AUTOCONNECT, false); }
    public void setAutoConnect(boolean v) {
        prefs.edit().putBoolean(KEY_AUTOCONNECT, v).apply();
    }

    public int  getShardIndex() { return prefs.getInt(KEY_SHARD, 0); }
    public void setShardIndex(int v) {
        prefs.edit().putInt(KEY_SHARD, v).apply();
    }

    public long getMutedUntil() { return prefs.getLong(KEY_MUTED_UNTIL, 0L); }
    public void setMutedUntil(long v) {
        prefs.edit().putLong(KEY_MUTED_UNTIL, v).apply();
    }
    public boolean isMuted() { return System.currentTimeMillis() < getMutedUntil(); }

    public String getGroupName() { return prefs.getString(KEY_GROUP_NAME, ""); }
    public void setGroupName(String v) {
        prefs.edit().putString(KEY_GROUP_NAME, v == null ? "" : v).apply();
    }

    public String getGroupPassphrase() {
        String stored = prefs.getString(KEY_PASSPHRASE, "");
        if (stored.length() == 0) return "";
        if (!crypto.isWrapped(stored) && crypto.isAvailable()) {
            String wrapped = crypto.encrypt(stored);
            prefs.edit().putString(KEY_PASSPHRASE, wrapped).apply();
            return stored;
        }
        return crypto.decrypt(stored);
    }

    public void setGroupPassphrase(String v) {
        String wrapped = crypto.encrypt(v == null ? "" : v);
        prefs.edit().putString(KEY_PASSPHRASE, wrapped).apply();
    }

    public String getGroupKey() {
        String stored = prefs.getString(KEY_GROUP_KEY, "");
        if (stored.length() == 0) return "";
        if (!crypto.isWrapped(stored) && crypto.isAvailable()) {
            String wrapped = crypto.encrypt(stored);
            prefs.edit().putString(KEY_GROUP_KEY, wrapped).apply();
            return stored;
        }
        return crypto.decrypt(stored);
    }

    public void setGroupKey(String b64) {
        String wrapped = crypto.encrypt(b64 == null ? "" : b64);
        prefs.edit().putString(KEY_GROUP_KEY, wrapped).apply();
    }

    public long getMessageTtlMs() { return prefs.getLong(KEY_TTL, 0L); }
    public void setMessageTtlMs(long v) {
        prefs.edit().putLong(KEY_TTL, v).apply();
    }

    public boolean hasSeenChecklist() { return prefs.getBoolean(KEY_CHECKLIST, false); }
    public void setChecklistSeen(boolean v) {
        prefs.edit().putBoolean(KEY_CHECKLIST, v).apply();
    }

    public boolean isSoundEnabled() { return prefs.getBoolean(KEY_SOUND, true); }
    public void setSoundEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_SOUND, v).apply();
    }

    public boolean isQuietEnabled() { return prefs.getBoolean(KEY_QUIET_EN, false); }
    public void setQuietEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_QUIET_EN, v).apply();
    }

    public int getQuietStart() { return prefs.getInt(KEY_QUIET_START, 22 * 60); }
    public void setQuietStart(int v) {
        prefs.edit().putInt(KEY_QUIET_START, v).apply();
    }
    public int getQuietEnd() { return prefs.getInt(KEY_QUIET_END, 7 * 60); }
    public void setQuietEnd(int v) {
        prefs.edit().putInt(KEY_QUIET_END, v).apply();
    }

    public boolean isQuietNow() {
        if (!isQuietEnabled()) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = getQuietStart();
        int end = getQuietEnd();
        if (start == end) return false;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    public long getLastReadTs() { return prefs.getLong(KEY_LAST_READ, 0L); }
    public void setLastReadTs(long v) {
        prefs.edit().putLong(KEY_LAST_READ, v).apply();
    }

    public Set<String> getDeletedSigs() {
        String raw = prefs.getString(KEY_DELETED, "");
        Set<String> s = new HashSet<String>();
        if (raw.length() > 0) {
            for (String p : raw.split(",")) {
                if (p.length() > 0) s.add(p);
            }
        }
        return s;
    }

    public void addDeletedSig(String sig) {
        if (sig == null || sig.length() == 0) return;
        Set<String> s = getDeletedSigs();
        s.add(sig);
        if (s.size() > DELETED_CAP) {
            Iterator<String> it = s.iterator();
            int toRemove = s.size() - DELETED_CAP;
            int removed = 0;
            while (it.hasNext() && removed < toRemove) {
                it.next();
                it.remove();
                removed++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append(",");
            sb.append(x);
        }
        prefs.edit().putString(KEY_DELETED, sb.toString()).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}