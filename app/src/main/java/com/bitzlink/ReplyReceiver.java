package com.bitzlink;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

public class ReplyReceiver extends BroadcastReceiver {

    public static final String ACTION_REPLY = "com.bitzlink.ACTION_REPLY";
    public static final String KEY_TEXT = "reply_text";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!ACTION_REPLY.equals(intent.getAction())) return;

        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;

        CharSequence cs = results.getCharSequence(KEY_TEXT);
        if (cs == null) return;
        String text = cs.toString().trim();
        if (text.length() == 0) return;

        Intent svc = new Intent(ctx, MeshService.class);
        svc.setAction(MeshService.ACTION_SEND_TEXT);
        svc.putExtra(MeshService.EXTRA_TEXT, text);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }
}