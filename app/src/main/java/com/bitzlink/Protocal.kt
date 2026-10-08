package com.bitzlink

object Protocol {
    const val HELLO = "HELLO"
    const val MSG = "MSG"
    const val TYPING = "TYPING"
    const val BACKUP_JOIN = "BACKUP_JOIN"
    const val BACKUP_FULL = "BACKUP_FULL"
    const val BACKUP_RANK = "BACKUP_RANK"
    const val CANDIDATE = "CANDIDATE"
    const val CLAIM = "CLAIM"
    const val LEADER_GONE = "LEADER_GONE"
    const val PING = "PING"
    const val PONG = "PONG"
    const val FULL = "FULL"
    const val ROSTER = "ROSTER"
    const val REPLAY_BEGIN = "REPLAY_BEGIN"
    const val NAMES = "NAMES"
    const val ACK = "ACK"
    const val READ = "READ"
    const val GROUPKEY = "GROUPKEY"

    const val DELIM = "|"
    const val SEP = "\\|"

    @JvmStatic
    fun pack(type: String, a: String?): String = type + DELIM + (a ?: "")

    @JvmStatic
    fun pack(type: String, a: String?, b: String?): String =
        type + DELIM + (a ?: "") + DELIM + (b ?: "")

    @JvmStatic
    fun unpack(line: String): Array<String> =
        // Java's String.split(regex, -1) accepts -1 and preserves trailing
        // empty fields. Kotlin's Regex.split(input, limit) requires limit >= 0
        // and throws IllegalArgumentException on -1. Use the Java API directly.
        java.util.regex.Pattern.compile(SEP).split(line, -1)
}