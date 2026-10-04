package com.bitzlink;

public class PeerState {

    public static class ChatMessage {
        public final String  sender;
        public final String  body;
        public final boolean mine;
        public final long    timestampMs;
        public final String  replyToSender;
        public final String  replyToBody;
        public final long    expiresAt;   // 0 = never

        public ChatMessage(String sender, String body, boolean mine) {
            this(sender, body, mine, System.currentTimeMillis(),
                    null, null, 0L);
        }
        public ChatMessage(String sender, String body, boolean mine, long ts) {
            this(sender, body, mine, ts, null, null, 0L);
        }
        public ChatMessage(String sender, String body, boolean mine, long ts,
                           String replyToSender, String replyToBody) {
            this(sender, body, mine, ts, replyToSender, replyToBody, 0L);
        }
        public ChatMessage(String sender, String body, boolean mine, long ts,
                           String replyToSender, String replyToBody,
                           long expiresAt) {
            this.sender        = sender;
            this.body          = body;
            this.mine          = mine;
            this.timestampMs   = ts;
            this.replyToSender = replyToSender;
            this.replyToBody   = replyToBody;
            this.expiresAt     = expiresAt;
        }
    }

    public static class PeerInfo {
        public final String name;
        public PeerInfo(String name) { this.name = name; }
    }

    public final String myName;

    public PeerState(String myName) {
        this.myName = myName;
    }
}