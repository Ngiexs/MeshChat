package com.bitzlink

import android.content.Context
import android.content.SharedPreferences

class GroupRegistry(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // One-time migration: old format used "," which broke any group name
        // that itself contained a comma. New format uses \u0001.
        val raw = prefs.getString(KEY_LIST, "") ?: ""
        if (raw.isNotEmpty() && raw.contains(",") && !raw.contains("\u0001")) {
            val migrated = raw.split(",").filter { it.isNotEmpty() }
            prefs.edit().putString(KEY_LIST, migrated.joinToString("\u0001")).apply()
        }
    }

    fun getGroupNames(): List<String> {
        val raw = prefs.getString(KEY_LIST, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\u0001").filter { it.isNotEmpty() }
    }

    fun addGroup(name: String?) {
        if (name.isNullOrEmpty()) return
        val groups = getGroupNames().toMutableList()
        if (groups.contains(name)) return
        groups.add(name)
        saveList(groups)
    }

    fun removeGroup(name: String?) {
        if (name == null) return
        val groups = getGroupNames().toMutableList()
        groups.remove(name)
        saveList(groups)
        if (name == getActiveGroup()) setActiveGroup("")
    }

    private fun saveList(groups: List<String>) {
        prefs.edit().putString(KEY_LIST, groups.joinToString("\u0001")).apply()
    }

    fun getActiveGroup(): String = prefs.getString(KEY_ACTIVE, "") ?: ""

    fun setActiveGroup(name: String?) {
        prefs.edit().putString(KEY_ACTIVE, name ?: "").apply()
    }

    companion object {
        private const val PREFS = "bitzlink_groups"
        private const val KEY_LIST = "group_list"
        private const val KEY_ACTIVE = "active_group"

        @JvmStatic
        fun sanitize(name: String?): String {
            if (name == null) return ""
            val sb = StringBuilder()
            for (c in name) {
                sb.append(
                    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                        c == '-' || c == '_') c else '_'
                )
            }
            return sb.toString()
        }
    }
}