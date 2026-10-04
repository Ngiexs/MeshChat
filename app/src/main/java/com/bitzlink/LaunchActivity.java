package com.bitzlink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LaunchActivity extends Activity {

    private static final String TRACE = "MeshTrace";
    private static final String PREFS = "bitzlink_launch";
    private static final String KEY_COUNT = "launch_count";

    private static final long INTRO_DELAY_MS  = 600;
    private static final long STEP_GAP_MS     = 400;
    private static final long TOTAL_MS        = 3200;

    private static final long FAST_HANDOFF_MS = 150;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_launch);

        // If there's already an active session, skip the splash and
        // go straight to MainActivity (which will auto-reconnect to
        // ChatActivity). This makes re-opening the app feel instant.
        boolean hasActiveSession = false;
        try {
            GroupRegistry reg = new GroupRegistry(this);
            if (reg.getActiveGroup().length() > 0) {
                SessionStore s = new SessionStore(this);
                hasActiveSession = s.hasSession() && s.shouldAutoConnect();
            }
        } catch (Exception e) {
            Log.w(TRACE, "LaunchActivity: session check failed: "
                    + e.getMessage());
        }

        if (hasActiveSession) {
            Log.i(TRACE, "LaunchActivity: active session, skipping splash");
            final Handler h = new Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                public void run() { handoff(); }
            }, FAST_HANDOFF_MS);
            return;
        }

        // Fresh launch. Show the animation.
        final ImageView   icon    = (ImageView) findViewById(R.id.launchIcon);
        final TextView    title   = (TextView)  findViewById(R.id.launchTitle);
        final TextView    sub     = (TextView)  findViewById(R.id.launchSub);
        final ProgressBar spinner = (ProgressBar) findViewById(R.id.launchSpinner);
        final TextView    version = (TextView)  findViewById(R.id.versionText);

        int launches = bumpLaunchCount();
        if (version != null) {
            version.setText("Version 0.4.0-beta1 ·  Launch #" + launches);
        }

        final Handler h = new Handler(Looper.getMainLooper());

        h.postDelayed(new Runnable() {
            public void run() {
                icon.animate().alpha(1f).setDuration(700).start();
            }
        }, INTRO_DELAY_MS);

        h.postDelayed(new Runnable() {
            public void run() {
                title.animate().alpha(1f).setDuration(700).start();
            }
        }, INTRO_DELAY_MS + STEP_GAP_MS);

        h.postDelayed(new Runnable() {
            public void run() {
                sub.animate().alpha(1f).setDuration(700).start();
                spinner.animate().alpha(1f).setDuration(700).start();
            }
        }, INTRO_DELAY_MS + STEP_GAP_MS * 2);

        h.postDelayed(new Runnable() {
            public void run() { handoff(); }
        }, TOTAL_MS);
    }

    private void handoff() {
        if (isFinishing()) return;
        Intent i = new Intent(LaunchActivity.this, MainActivity.class);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in,
                android.R.anim.fade_out);
        finish();
    }

    private int bumpLaunchCount() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int n = p.getInt(KEY_COUNT, 0) + 1;
        p.edit().putInt(KEY_COUNT, n).apply();
        return n;
    }
}