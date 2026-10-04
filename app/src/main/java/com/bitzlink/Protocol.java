package com.bitzlink;

public class Protocol {
    public static final String HELLO        = "HELLO";
    public static final String MSG          = "MSG";
    public static final String TYPING       = "TYPING";
    public static final String BACKUP_JOIN  = "BACKUP_JOIN";
    public static final String BACKUP_FULL  = "BACKUP_FULL";
    public static final String BACKUP_RANK  = "BACKUP_RANK";
    public static final String CANDIDATE    = "CANDIDATE";
    public static final String CLAIM        = "CLAIM";
    public static final String LEADER_GONE  = "LEADER_GONE";
    public static final String PING         = "PING";
    public static final String PONG         = "PONG";
    public static final String FULL         = "FULL";
    public static final String ROSTER       = "ROSTER";
    public static final String REPLAY_BEGIN = "REPLAY_BEGIN";
    public static final String NAMES        = "NAMES";
    public static final String ACK          = "ACK";
    public static final String GROUPKEY     = "GROUPKEY";

    public static final String DELIM = "|";
    public static final String SEP   = "\\|";

    public static String pack(String type, String a) {
        return type + DELIM + (a == null ? "" : a);
    }

    public static String pack(String type, String a, String b) {
        return type + DELIM + (a == null ? "" : a)
                    + DELIM + (b == null ? "" : b);
    }

    public static String[] unpack(String line) {
        return line.split(SEP, -1);
    }
}