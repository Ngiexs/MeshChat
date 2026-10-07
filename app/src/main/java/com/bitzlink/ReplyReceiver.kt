package com.bitzlink

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION_REPLY) return

        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val cs = results.getCharSequence(KEY_TEXT) ?: return
        val text = cs.toString().trim()
        if (text.isEmpty()) return

        val svc = Intent(ctx, MeshService::class.java).apply {
            action = MeshService.ACTION_SEND_TEXT
            putExtra(MeshService.EXTRA_TEXT, text)
        }
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
        else ctx.startService(svc)
    }

    companion object {
        const val ACTION_REPLY = "com.bitzlink.ACTION_REPLY"
        const val KEY_TEXT = "reply_text"
    }
}