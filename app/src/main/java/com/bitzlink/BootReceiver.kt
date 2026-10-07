package com.bitzlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        intent ?: return
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val s = SessionStore(ctx)
        if (!s.hasSession() || !s.shouldAutoConnect()) return

        val svc = Intent(ctx, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
        else ctx.startService(svc)
    }
}