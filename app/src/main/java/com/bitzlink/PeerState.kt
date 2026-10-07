package com.bitzlink

class PeerState(val myName: String) {

    class ChatMessage @JvmOverloads constructor(
        @JvmField val sender: String,
        @JvmField val body: String,
        @JvmField val mine: Boolean,
        @JvmField val timestampMs: Long = System.currentTimeMillis(),
        @JvmField val replyToSender: String? = null,
        @JvmField val replyToBody: String? = null,
        @JvmField val expiresAt: Long = 0L,
        @JvmField val imageB64: String? = null,
        @JvmField val imageMime: String? = null
    ) {
        val isImage: Boolean
            get() = !imageB64.isNullOrEmpty()
    }

    class PeerInfo(val name: String)
}