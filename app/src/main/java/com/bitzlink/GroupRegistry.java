package com.bitzlink;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

public class GroupRegistry {

    private static final String PREFS    = "bitzlink_groups";
    private static final String KEY_LIST = "group_list";
    private static final String KEY_ACTIVE = "active_group";

    private final SharedPreferences prefs;

    public GroupRegistry(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Returns the list of group names this device knows about. */
    public List<String> getGroupNames() {
        String raw = prefs.getString(KEY_LIST, "");
        List<String> out = new ArrayList<String>();
        if (raw.length() > 0) {
            for (String s : raw.split(",")) {
                if (s.length() > 0) out.add(s);
            }
        }
        return out;
    }

    public void addGroup(String name) {
        if (name == null || name.length() == 0) return;
        List<String> groups = getGroupNames();
        if (groups.contains(name)) return;
        groups.add(name);
        saveList(groups);
    }

    public void removeGroup(String name) {
        if (name == null) return;
        List<String> groups = getGroupNames();
        groups.remove(name);
        saveList(groups);
        if (name.equals(getActiveGroup())) {
            setActiveGroup("");
        }
    }

    private void saveList(List<String> groups) {
        StringBuilder sb = new StringBuilder();
        for (String s : groups) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        prefs.edit().putString(KEY_LIST, sb.toString()).apply();
    }

    public String getActiveGroup() {
        String a = prefs.getString(KEY_ACTIVE, "");
        return a == null ? "" : a;
    }

    public void setActiveGroup(String name) {
        prefs.edit().putString(KEY_ACTIVE, name == null ? "" : name).apply();
    }

    /**
     * Turns a user-supplied group name into a string safe for
     * filesystem paths and SharedPreferences file names. Only
     * letters, digits, dash, and underscore survive; everything
     * else becomes an underscore.
     */
    public static String sanitize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}