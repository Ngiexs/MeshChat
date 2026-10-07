package com.bitzlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

class LaunchActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_launch)

        var hasActiveSession = false
        try {
            val reg = GroupRegistry(this)
            if (reg.getActiveGroup().isNotEmpty()) {
                val s = SessionStore(this)
                hasActiveSession = s.hasSession() && s.shouldAutoConnect()
            }
        } catch (e: Exception) {
            Log.w(TRACE, "LaunchActivity: session check failed: ${e.message}")
        }

        if (hasActiveSession) {
            Log.i(TRACE, "LaunchActivity: active session, skipping splash")
            Handler(Looper.getMainLooper()).postDelayed({
                handoff()
            }, FAST_HANDOFF_MS)
            return
        }

        val icon = findViewById<ImageView>(R.id.launchIcon)
        val title = findViewById<TextView>(R.id.launchTitle)
        val sub = findViewById<TextView>(R.id.launchSub)
        val spinner = findViewById<ProgressBar>(R.id.launchSpinner)
        val version = findViewById<TextView>(R.id.versionText)

        val launches = bumpLaunchCount()
        version?.text = "Version 0.4.3_hotFix-beta1 ·  Launch #$launches"

        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ icon.animate().alpha(1f).setDuration(700).start() },
            INTRO_DELAY_MS)
        h.postDelayed({ title.animate().alpha(1f).setDuration(700).start() },
            INTRO_DELAY_MS + STEP_GAP_MS)
        h.postDelayed({
            sub.animate().alpha(1f).setDuration(700).start()
            spinner.animate().alpha(1f).setDuration(700).start()
        }, INTRO_DELAY_MS + STEP_GAP_MS * 2)
        h.postDelayed({ handoff() }, TOTAL_MS)
    }

    private fun handoff() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun bumpLaunchCount(): Int {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getInt(KEY_COUNT, 0) + 1
        p.edit().putInt(KEY_COUNT, n).apply()
        return n
    }

    companion object {
        private const val TRACE = "MeshTrace"
        private const val PREFS = "bitzlink_launch"
        private const val KEY_COUNT = "launch_count"
        private const val INTRO_DELAY_MS = 600L
        private const val STEP_GAP_MS = 400L
        private const val TOTAL_MS = 3200L
        private const val FAST_HANDOFF_MS = 150L
    }
}