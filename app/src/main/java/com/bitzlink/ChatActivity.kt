package com.bitzlink

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKey

class ChatActivity : Activity() {

    private var service: MeshService? = null
    private lateinit var messagesBox: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var typingBar: TextView
    private lateinit var scrollBtn: Button
    private lateinit var searchBtn: Button
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private var photoBtn: ImageButton? = null
    private var headerDot: View? = null

    private var searchBar: LinearLayout? = null
    private var searchInput: EditText? = null
    private var searchClose: Button? = null
    private var searchActive = false

    private var replyBar: LinearLayout? = null
    private var replyWho: TextView? = null
    private var replyPreview: TextView? = null
    private var replyCancel: Button? = null

    private lateinit var menuBtn: Button
    private lateinit var drawerScroll: ScrollView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var headerGroup: TextView
    private lateinit var drawerGroup: TextView
    private lateinit var drawerName: TextView
    private lateinit var drawerType: TextView
    private lateinit var drawerStatus: TextView
    private lateinit var drawerOnion: TextView
    private lateinit var drawerMembers: TextView
    private lateinit var drawerNames: TextView
    private lateinit var drawerBackups: TextView
    private lateinit var drawerGroupName: TextView
    private lateinit var drawerPassphrase: TextView
    private var versionLabel: TextView? = null
    private lateinit var muteBtn: Button
    private lateinit var soundBtn: Button
    private lateinit var quietBtn: Button
    private lateinit var leaveBtn: Button
    private lateinit var clearHistoryBtn: Button
    private lateinit var copyOnionBtn: Button
    private lateinit var shareCredsBtn: Button
    private lateinit var showQrBtn: Button
    private lateinit var ttlBtn: Button
    private lateinit var switchGroupBtn: Button
    private var exportLogBtn: Button? = null
    private var drawerOpen = false

    private var name: String? = null
    private var peer: String = ""
    private var isHost = false
    private var isBackup = false
    private var activeGroup = ""
    private var keyHolder: GroupKeyHolder? = null
    private var currentTtlMs = 0L
    private var lastTypingSent = 0L
    private var handler: Handler? = null

    private var typeLabel = "Client"
    private var fullOnion: String? = null

    private var typingClearRunnable: Runnable? = null
    private var userAtBottom = true

    private var lastBackPress = 0L
    private var backCallback: android.window.OnBackInvokedCallback? = null

    private var isForeground = false
    private var lastClearHistoryAt = 0L

    private var lastSender: String? = null
    private var lastMessageTs = 0L
    private var lastDividerDay = -1

    private var currentlyUnstable = false
    private var replyTo: PeerState.ChatMessage? = null

    private var unreadThresholdTs = 0L
    private var unreadDividerDrawn = false
    private var maxRenderedTs = 0L

    private var deletedSigs: MutableSet<String> = HashSet()
    private val renderedSigs = HashSet<String>()
    private val failedSends = HashSet<String>()

    private var networkStarted = false

    private val pendingTicks = HashMap<String, TextView>()
    private val ackedBy = HashMap<String, MutableSet<String>>()
    private val readBy = HashMap<String, MutableSet<String>>()
    private val readSent = HashSet<String>()
    private val reactions = HashMap<String, HashMap<String, MutableSet<String>>>()
    private val reactionRows = HashMap<String, LinearLayout>()
    private val typingPeers = HashMap<String, Long>()

    private var emptyStateView: View? = null

    private val photoCache = LruCache<String, Bitmap>(PHOTO_CACHE_BYTES)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    private inner class RowMeta {
        var searchText: String = ""
        var msgId: String? = null
        var signature: String = ""
        var sender: String = ""
        var mine: Boolean = false
        var ts: Long = 0L
        var expiresAt: Long = 0L
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            service = (b as MeshService.LocalBinder).get()
            tryStartNetwork()
            refreshOnion()
            refreshDrawerSnapshot()
        }
        override fun onServiceDisconnected(n: ComponentName?) {
            service = null
        }
    }

    private val networkPoll = object : Runnable {
        override fun run() {
            val svc = service
            if (svc != null) {
                svc.tickHeartbeat()
                svc.getNode()?.let {
                    it.sendPing()
                    val pending = it.getPendingCount()
                    if (pending > 0) setStatus("$pending queued (offline)")
                }
                refreshOnion()
                refreshDrawerSnapshot()
                updateSendBtn()
                checkStability()
                refreshTypingBar()
            }
            handler?.postDelayed(this, nextPollDelay())
        }
    }

    private fun nextPollDelay(): Long =
        if (fullOnion.isNullOrEmpty()) POLL_FAST_MS else POLL_SLOW_MS

    private val expiryTicker = object : Runnable {
        override fun run() {
            sweepExpired()
            handler?.postDelayed(this, EXPIRY_TICK_MS)
        }
    }

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun avatarColor(who: String?): Int {
        val w = who ?: "?"
        val idx = Math.abs(w.hashCode()) % AVATAR_PALETTE.size
        return AVATAR_PALETTE[idx]
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_chat)
        handler = Handler(Looper.getMainLooper())

        messagesBox = findViewById(R.id.messagesContainer)
        scroller = findViewById(R.id.scrollView)
        typingBar = findViewById(R.id.typingBar)
        scrollBtn = findViewById(R.id.scrollBtn)
        searchBtn = findViewById(R.id.searchBtn)
        input = findViewById(R.id.msgInput)
        sendBtn = findViewById(R.id.sendBtn)
        photoBtn = findViewById<ImageButton>(R.id.photoBtn)
        headerDot = findViewById<View>(R.id.headerDot)

        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchClose = findViewById(R.id.searchClose)

        replyBar = findViewById(R.id.replyBar)
        replyWho = findViewById(R.id.replyWho)
        replyPreview = findViewById(R.id.replyPreview)
        replyCancel = findViewById(R.id.replyCancel)

        menuBtn = findViewById(R.id.menuBtn)
        headerGroup = findViewById(R.id.headerGroup)
        drawerScroll = findViewById(R.id.drawerScroll)
        drawerPanel = findViewById(R.id.drawerPanel)
        drawerScrim = findViewById(R.id.drawerScrim)
        drawerGroup = findViewById(R.id.drawerGroup)
        drawerName = findViewById(R.id.drawerName)
        drawerType = findViewById(R.id.drawerType)
        drawerStatus = findViewById(R.id.drawerStatus)
        drawerOnion = findViewById(R.id.drawerOnion)
        drawerMembers = findViewById(R.id.drawerMembers)
        drawerNames = findViewById(R.id.drawerNames)
        drawerBackups = findViewById(R.id.drawerBackups)
        drawerGroupName = findViewById(R.id.drawerGroupName)
        drawerPassphrase = findViewById(R.id.drawerPassphrase)
        versionLabel = findViewById(R.id.versionLabel)
        muteBtn = findViewById(R.id.muteBtn)
        soundBtn = findViewById(R.id.soundBtn)
        quietBtn = findViewById(R.id.quietBtn)
        leaveBtn = findViewById(R.id.leaveBtn)
        clearHistoryBtn = findViewById(R.id.clearHistoryBtn)
        copyOnionBtn = findViewById(R.id.copyOnionBtn)
        shareCredsBtn = findViewById(R.id.shareCredsBtn)
        showQrBtn = findViewById(R.id.showQrBtn)
        ttlBtn = findViewById(R.id.ttlBtn)
        switchGroupBtn = findViewById(R.id.switchGroupBtn)
        exportLogBtn = findViewById(R.id.exportLogBtn)

        versionLabel?.text = "version ${AppConfig.VERSION_LABEL}"
        updateHeaderDot(COLOR_STATUS_OFFLINE)

        activeGroup = GroupRegistry(this).getActiveGroup()
        val session = SessionStore(this)
        currentTtlMs = session.getMessageTtlMs()

        name = intent.getStringExtra("name")
        peer = intent.getStringExtra("peer") ?: ""
        if (name.isNullOrEmpty()) {
            val sn = session.getName()
            if (sn.isNotEmpty()) name = sn
        }
        if (peer.isEmpty()) {
            val sp = session.getPeer()
            if (sp.isNotEmpty()) peer = sp
        }

        isHost = if (intent.hasExtra("isHost"))
            intent.getBooleanExtra("isHost", false) else session.isHost()
        isBackup = if (intent.hasExtra("isBackup"))
            intent.getBooleanExtra("isBackup", false) else session.isBackup()

        Log.i("MeshTrace", "ChatActivity launch: name=$name peer=${peer.isNotEmpty()} " +
            "isHost=$isHost isBackup=$isBackup")

        typeLabel = computeTypeLabel()

        val cachedKey = session.getGroupKey()
        val passphrase = session.getGroupPassphrase()
        var groupName = session.getGroupName()
        if (groupName.isEmpty()) groupName = activeGroup

        if (cachedKey.isNotEmpty()) {
            val kh = GroupKeyHolder(null)
            kh.setFromBase64(cachedKey)
            keyHolder = kh
        } else if (passphrase.isNotEmpty()) {
            setStatus("Deriving key…")
            val fPass = passphrase
            val fGroup = if (groupName.isNotEmpty()) groupName else "MeshChat"
            val fSession = session
            Thread({
                try {
                    val derived = CryptoUtils.deriveKey(fPass,
                        AppConfig.GROUP_SALT_PREFIX + fGroup)
                    val b64 = Base64.encodeToString(derived.encoded, Base64.NO_WRAP)
                    fSession.setGroupKey(b64)
                    val kh = GroupKeyHolder(derived)
                    keyHolder = kh
                    runOnUiThread {
                        setStatus("Key ready")
                        updateSendBtn()
                        tryStartNetwork()
                    }
                } catch (e: Exception) {
                    runOnUiThread { setStatus("Key derivation failed") }
                }
            }, "key-derive").start()
            keyHolder = GroupKeyHolder(null)
        } else {
            try {
                val testKey = CryptoUtils.deriveKeyB64(
                    AppConfig.TEST_PASSPHRASE, AppConfig.TEST_SALT_B64)
                keyHolder = GroupKeyHolder(testKey)
            } catch (_: Exception) {
                keyHolder = GroupKeyHolder(null)
            }
        }

        deletedSigs = session.getDeletedSigs()
        unreadThresholdTs = session.getLastReadTs()
        if (unreadThresholdTs == 0L) unreadDividerDrawn = true

        drawerGroup.text = if (groupName.isNotEmpty()) groupName else "MeshChat"
        headerGroup.text = if (groupName.isNotEmpty()) groupName else "MeshChat"
        drawerName.text = name ?: ""
        drawerType.text = typeLabel
        drawerType.setTextColor(typeColor())

        drawerGroupName.text = if (groupName.isNotEmpty()) groupName else "MeshChat"
        if (passphrase.isEmpty()) {
            drawerPassphrase.text = "(blank — using test key)"
            drawerPassphrase.setTextColor(0xFFFFA726.toInt())
        } else {
            val stars = "•".repeat(Math.min(passphrase.length, 12))
            drawerPassphrase.text = "$stars  (long-press to reveal)"
            val fPass2 = passphrase
            drawerPassphrase.setOnLongClickListener {
                drawerPassphrase.text = fPass2
                true
            }
        }

        drawerOnion.setOnLongClickListener { copyOnionToClipboard(); true }

        setStatus("Connecting...")
        showEmptyStateIfNeeded()
        updateMuteBtn(); updateSoundBtn(); updateQuietBtn(); updateTtlBtn()

        scroller.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            val child = scroller.getChildAt(0)
            if (child == null) { userAtBottom = true; updateScrollBtn() }
            else {
                val distFromBottom = child.bottom -
                    (scroller.height + scroller.scrollY)
                userAtBottom = distFromBottom <= AUTO_SCROLL_THRESHOLD_PX
                updateScrollBtn()
            }
        }

        scrollBtn.setOnClickListener {
            val child = scroller.getChildAt(0)
            if (child != null) scroller.smoothScrollTo(0, child.bottom)
        }
        menuBtn.setOnClickListener { toggleDrawer() }
        drawerScrim.setOnClickListener { closeDrawer() }
        searchBtn.setOnClickListener { openSearch() }
        searchClose?.setOnClickListener { closeSearch() }
        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applySearchFilter(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        replyCancel?.setOnClickListener { clearReply() }

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!)
        }

        muteBtn.setOnClickListener { showMuteDialog() }
        soundBtn.setOnClickListener {
            val ss = SessionStore(this)
            ss.setSoundEnabled(!ss.isSoundEnabled())
            updateSoundBtn()
        }
        quietBtn.setOnClickListener { showQuietDialog() }
        clearHistoryBtn.setOnClickListener { showClearHistoryConfirm() }
        copyOnionBtn.setOnClickListener { copyOnionToClipboard() }
        shareCredsBtn.setOnClickListener { shareCredentials() }
        showQrBtn.setOnClickListener { showGroupQr() }
        ttlBtn.setOnClickListener { showTtlDialog() }
        switchGroupBtn.setOnClickListener { switchGroup() }
        exportLogBtn?.setOnClickListener { exportLog() }
        photoBtn?.setOnClickListener { pickPhoto() }

        leaveBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Leave group?")
                .setMessage("This stops the MeshChat service on this device. " +
                    "You can rejoin at any time.")
                .setPositiveButton("Leave") { _, _ -> doLeave() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        bindService(Intent(this, MeshService::class.java), conn, Context.BIND_AUTO_CREATE)

        sendBtn.setOnClickListener { sendCurrentText() }

        updateSendBtn()

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSendBtn()
                val now = System.currentTimeMillis()
                if (now - lastTypingSent > 2000) {
                    lastTypingSent = now
                    if (!isHost) service?.getNode()?.sendTyping()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)

        val newGroup = GroupRegistry(this).getActiveGroup()
        if (newGroup.isNotEmpty() && newGroup != activeGroup) {
            activeGroup = newGroup
            headerGroup.text = activeGroup
            drawerGroup.text = activeGroup
            drawerGroupName.text = activeGroup
            Log.i("MeshTrace", "ChatActivity onNewIntent: activeGroup=$activeGroup")
        }

        val nName = intent.getStringExtra("name")
        val nPeer = intent.getStringExtra("peer")
        val nHost = if (intent.hasExtra("isHost")) intent.getBooleanExtra("isHost", false) else isHost
        val nBackup = if (intent.hasExtra("isBackup")) intent.getBooleanExtra("isBackup", false) else isBackup

        var changed = false
        if (!nName.isNullOrEmpty() && nName != name) {
            name = nName
            drawerName.text = name ?: ""
            changed = true
        }
        if (!nPeer.isNullOrEmpty() && nPeer != peer) {
            peer = nPeer
            changed = true
        }
        if (nHost != isHost) {
            isHost = nHost; typeLabel = computeTypeLabel()
            drawerType.text = typeLabel; drawerType.setTextColor(typeColor())
            changed = true
        }
        if (nBackup != isBackup) {
            isBackup = nBackup; typeLabel = computeTypeLabel()
            drawerType.text = typeLabel; drawerType.setTextColor(typeColor())
            changed = true
        }
        if (changed) Log.i("MeshTrace", "ChatActivity onNewIntent: refreshed")

        refreshOnion()
        refreshDrawerSnapshot()
        updateSendBtn()
    }

    private fun sendCurrentText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (keyHolder?.hasKey() != true) {
            Toast.makeText(this, "Waiting for group key…", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")

        val ts = System.currentTimeMillis()
        val replySender = replyTo?.sender
        val replyBody = replyTo?.body
        val ttl = currentTtlMs
        val expiresAt = if (ttl > 0) ts + ttl else 0L

        val svc = service
        if (svc != null && svc.amHost() && svc.getServer() != null) {
            val msgId = UUID.randomUUID().toString()
            val plain = if (replySender != null) {
                val safeReplyBody = replyBody?.replace('\u0001', ' ') ?: ""
                "MSG\u0001$name\u0001$ts\u0001$replySender\u0001" +
                    "$safeReplyBody\u0001$text\u0001$ttl"
            } else {
                "MSG\u0001$name\u0001$ts\u0001$text\u0001$ttl"
            }
            sendFromHost(plain, msgId)
            addBubble(PeerState.ChatMessage(name ?: "", text, true, ts,
                replySender, replyBody, expiresAt), msgId)
            // No local self-ACK. Ticks climb to "✓ 1" only when a peer
            // actually acknowledges the frame on the wire, so both host
            // and client show the same progression.
        } else if (svc != null && svc.getNode() != null &&
            svc.getNode()?.isFatalError() == false) {
            val msgId = UUID.randomUUID().toString()
            svc.getNode()?.sendChat(text, msgId, replySender, replyBody, ttl, ts)
            addBubble(PeerState.ChatMessage(name ?: "", text, true, ts,
                replySender, replyBody, expiresAt), msgId)
        } else {
            Toast.makeText(this, "Not connected — message not sent",
                Toast.LENGTH_SHORT).show()
        }
        clearReply()
    }

    private fun pickPhoto() {
        if (keyHolder?.hasKey() != true) {
            Toast.makeText(this, "Waiting for group key…", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            i.addCategory(Intent.CATEGORY_OPENABLE)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PHOTO)
        } catch (e: Exception) {
            Toast.makeText(this, "No picker available: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return

        Thread({
            try {
                val jpeg = loadAndCompressPhoto(uri)
                if (jpeg == null || jpeg.isEmpty()) {
                    runOnUiThread { Toast.makeText(this,
                        "Couldn't process that image", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                if (jpeg.size > PHOTO_MAX_BYTES) {
                    runOnUiThread { Toast.makeText(this,
                        "Image too large after compression", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp == null) {
                    runOnUiThread { Toast.makeText(this,
                        "Couldn't decode that image", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                runOnUiThread { showPhotoConfirmDialog(bmp, jpeg) }
            } catch (e: Exception) {
                Log.w("MeshTrace", "photo load failed", e)
                runOnUiThread { Toast.makeText(this,
                    "Couldn't load that image", Toast.LENGTH_SHORT).show() }
            }
        }, "photo-encode").start()
    }

    private fun showPhotoConfirmDialog(bmp: Bitmap, jpeg: ByteArray) {
        if (isFinishing) return

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.adjustViewBounds = true
        val maxW = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        iv.maxWidth = maxW; iv.maxHeight = maxH
        wrap.addView(iv)

        val info = TextView(this).apply {
            text = "${bmp.width}×${bmp.height}  ·  ${jpeg.size / 1024} KB"
            textSize = 12f
            setTextColor(0xFF8B98A5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, 0)
        }
        wrap.addView(info)

        AlertDialog.Builder(this)
            .setTitle("Send photo?")
            .setView(wrap)
            .setPositiveButton("Send") { _, _ ->
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                sendPhotoBytes(b64, "image/jpeg")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAndCompressPhoto(uri: Uri): ByteArray? {
        val ins = contentResolver.openInputStream(uri) ?: return null
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeStream(ins, null, opts)
        try { ins.close() } catch (_: Exception) {}

        val w = opts.outWidth; val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while ((w / sample) > PHOTO_MAX_DIM || (h / sample) > PHOTO_MAX_DIM) sample *= 2

        val opts2 = BitmapFactory.Options()
        opts2.inSampleSize = sample
        val ins2 = contentResolver.openInputStream(uri) ?: return null
        var bmp = BitmapFactory.decodeStream(ins2, null, opts2)
        try { ins2.close() } catch (_: Exception) {}
        if (bmp == null) return null

        val maxDim = Math.max(bmp.width, bmp.height)
        if (maxDim > PHOTO_MAX_DIM) {
            val scale = PHOTO_MAX_DIM.toFloat() / maxDim
            val nw = Math.max(1, (bmp.width * scale).toInt())
            val nh = Math.max(1, (bmp.height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        var best: ByteArray? = null
        for (q in PHOTO_QUALITIES) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            val b = bos.toByteArray()
            best = b
            if (b.size <= PHOTO_MAX_BYTES) break
        }
        bmp.recycle()
        return best
    }

    private fun sendPhotoBytes(b64: String, mime: String) {
        val ts = System.currentTimeMillis()
        val ttl = currentTtlMs
        val expiresAt = if (ttl > 0) ts + ttl else 0L
        val msgId = UUID.randomUUID().toString()

        val m = PeerState.ChatMessage(name ?: "", "[photo]", true, ts,
            null, null, expiresAt, b64, mime)

        val svc = service
        if (svc != null && svc.amHost() && svc.getServer() != null) {
            try {
                val plain = "PHOTO\u0001$name\u0001$ts\u0001$ttl\u0001$mime\u0001$b64"
                val k = keyHolder?.get()
                val cipher = CryptoUtils.encrypt(plain, k!!)
                val line = Protocol.pack(Protocol.MSG, msgId, cipher)
                svc.getServer()?.sendFromHost(line)
                addBubble(m, msgId)
                // No self-ACK — see sendCurrentText for rationale.
            } catch (e: Exception) { handleSendFailed(msgId, e.message) }
        } else if (svc != null && svc.getNode() != null &&
            svc.getNode()?.isFatalError() == false) {
            svc.getNode()?.sendPhoto(b64, mime, msgId, ttl, ts)
            addBubble(m, msgId)
        } else {
            Toast.makeText(this, "Not connected — photo not sent",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(m: PeerState.ChatMessage) {
        val b64 = m.imageB64
        if (b64.isNullOrEmpty()) return
        val mime = m.imageMime ?: "image/jpeg"

        Thread({
            var toast: String
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val filename = "meshchat_${System.currentTimeMillis()}.jpg"

                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/MeshChat")
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore insert returned null")
                    contentResolver.openOutputStream(uri).use { out ->
                        if (out == null) throw IOException("openOutputStream null")
                        out.write(bytes)
                        out.flush()
                    }
                    toast = "Saved to Pictures/MeshChat"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        ?: filesDir
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, filename)
                    FileOutputStream(f).use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    toast = "Saved to ${f.absolutePath}"
                }
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.w("MeshTrace", "save image failed", e)
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, "Save failed: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "save-image").start()
    }

    // ── Reactions ────────────────────────────────────────────────────

    private fun applyReaction(sender: String?, targetSig: String?, emoji: String?) {
        if (targetSig == null || emoji == null) return
        val byEmoji = reactions.getOrPut(targetSig) { HashMap() }
        val senders = byEmoji.getOrPut(emoji) { HashSet() }
        senders.add(sender ?: "?")
        refreshReactionRow(targetSig)
    }

    private fun refreshReactionRow(targetSig: String) {
        val row = reactionRows[targetSig] ?: return
        runOnUiThread {
            row.removeAllViews()
            val byEmoji = reactions[row.tag as String]
            if (byEmoji.isNullOrEmpty()) { row.visibility = View.GONE; return@runOnUiThread }
            row.visibility = View.VISIBLE
            for ((emoji, senders) in byEmoji) {
                if (senders.isEmpty()) continue
                val chip = TextView(this)
                chip.text = "$emoji ${senders.size}"
                chip.textSize = 13f
                chip.setTextColor(COLOR_MINE_TEXT)
                chip.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.RECTANGLE
                bg.cornerRadius = dp(12f).toFloat()
                bg.setColor(COLOR_REACT_FILL)
                bg.setStroke(dp(1f), COLOR_REACT_BORDER)
                chip.background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.rightMargin = dp(4f); lp.topMargin = dp(4f)
                chip.layoutParams = lp
                row.addView(chip)
            }
        }
    }

    private fun sendReaction(target: PeerState.ChatMessage?, emoji: String?) {
        if (target == null || emoji == null) return
        if (keyHolder?.hasKey() != true) return
        val sig = signatureOf(target)
        val msgId = UUID.randomUUID().toString()
        applyReaction(name ?: "?", sig, emoji)

        val svc = service
        if (svc != null && svc.amHost() && svc.getServer() != null) {
            try {
                val plain = "REACT\u0001$name\u0001$sig\u0001$emoji"
                val k = keyHolder?.get()
                val cipher = CryptoUtils.encrypt(plain, k!!)
                val line = Protocol.pack(Protocol.MSG, msgId, cipher)
                svc.getServer()?.sendFromHost(line)
            } catch (_: Exception) {}
        } else if (svc != null && svc.getNode() != null) {
            svc.getNode()?.sendReaction(sig, emoji, msgId)
        }
    }

    private fun showReactionPicker(m: PeerState.ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle("React")
            .setItems(REACTION_EMOJIS) { _, which -> sendReaction(m, REACTION_EMOJIS[which]) }
            .show()
    }

    // ── Typing aggregation ───────────────────────────────────────────

    private fun showTyping(who: String) {
        if (who.isEmpty()) return
        synchronized(typingPeers) {
            typingPeers[who] = System.currentTimeMillis()
        }
        refreshTypingBar()
    }

    private fun refreshTypingBar() {
        val now = System.currentTimeMillis()
        val active = ArrayList<String>()
        synchronized(typingPeers) {
            val stale = ArrayList<String>()
            for ((k, v) in typingPeers) {
                if (now - v > TYPING_TTL_MS) stale.add(k) else active.add(k)
            }
            for (s in stale) typingPeers.remove(s)
        }
        if (active.isEmpty()) { typingBar.text = ""; return }
        typingBar.text = when {
            active.size == 1 -> "✏️ ${active[0]} is typing…"
            active.size == 2 -> "✏️ ${active[0]} and ${active[1]} are typing…"
            else -> "✏️ ${active[0]}, ${active[1]} +${active.size - 2} are typing…"
        }
    }

    // ── Header dot ───────────────────────────────────────────────────

    private fun updateHeaderDot(color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        headerDot?.background = d
    }

    private fun checkStability() {
        val svc = service
        val dotColor = when {
            svc == null -> COLOR_STATUS_OFFLINE
            isHost -> if (svc.getServer() != null) COLOR_STATUS_OK else COLOR_STATUS_UNSTABLE
            else -> {
                val hb = svc.getHeartbeat()
                if (hb == null) COLOR_STATUS_UNSTABLE
                else {
                    val age = hb.ageMs(peer)
                    if (age == Long.MAX_VALUE || age > UNSTABLE_AGE_MS)
                        COLOR_STATUS_UNSTABLE else COLOR_STATUS_OK
                }
            }
        }
        updateHeaderDot(dotColor)

        val hb = svc?.getHeartbeat() ?: return
        val target = if (isHost) "_host_self" else peer
        val age = hb.ageMs(target)
        val unstable = age != Long.MAX_VALUE && age > UNSTABLE_AGE_MS
        if (unstable != currentlyUnstable) {
            currentlyUnstable = unstable
            drawerStatus.setTextColor(
                if (unstable) COLOR_STATUS_UNSTABLE else COLOR_STATUS_OK)
        }
    }

    // ── Export log ───────────────────────────────────────────────────

    private fun exportLog() {
        val r = service?.getReplica() ?: run {
            Toast.makeText(this, "No history to export", Toast.LENGTH_SHORT).show()
            return
        }
        val k = keyHolder?.get() ?: run {
            Toast.makeText(this, "No group key available", Toast.LENGTH_SHORT).show()
            return
        }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = r.snapshot()
        val sb = StringBuilder()
        var count = 0
        for (line in lines) {
            try {
                val p = Protocol.unpack(line)
                if (p.size < 2 || p[0] != Protocol.MSG) continue
                val cipher = if (p.size >= 3) p[2] else p[1]
                val plain = CryptoUtils.decrypt(cipher, k)
                if (plain.startsWith("REACT\u0001")) continue
                val parts = plain.split("\u0001")
                if (parts.size < 4) continue
                val ts = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val sender = parts[1]
                val body = if (parts[0] == "PHOTO") "[photo]" else
                    if (parts.size >= 6) parts[5] else parts[3]
                sb.append(df.format(Date(ts))).append("  ")
                    .append(sender).append(": ").append(body).append('\n')
                count++
            } catch (_: Exception) {}
        }
        if (sb.isEmpty()) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("meshchat-log", sb.toString()))
        Toast.makeText(this, "Copied $count messages", Toast.LENGTH_SHORT).show()
    }

    // ── Mark helpers ─────────────────────────────────────────────────

    private fun markAcked(msgId: String?, acker: String) {
        if (msgId == null) return
        failedSends.remove(msgId)
        val set = ackedBy.getOrPut(msgId) { HashSet() }
        set.add(acker)
        updateTick(msgId)
    }

    private fun markRead(msgId: String?, reader: String) {
        if (msgId == null || reader.isEmpty()) return
        val set = readBy.getOrPut(msgId) { HashSet() }
        set.add(reader)
        updateTick(msgId)
    }

    private fun maybeSendRead(m: PeerState.ChatMessage, msgId: String) {
        if (m.mine) return
        if (!isForeground) return
        if (readSent.contains(msgId)) return
        readSent.add(msgId)
        val svc = service ?: return
        try {
            if (svc.amHost() && svc.getServer() != null) {
                svc.getServer()?.sendReadTo(m.sender, msgId)
            } else svc.getNode()?.sendRead(msgId)
        } catch (_: Exception) {}
    }

    private fun handleSendFailed(msgId: String?, reason: String?) {
        if (msgId != null) { failedSends.add(msgId); updateTick(msgId) }
        if (isFinishing) return
        Toast.makeText(this, "Message not delivered — $reason",
            Toast.LENGTH_LONG).show()
    }

    private fun switchGroup() {
        AlertDialog.Builder(this)
            .setTitle("Switch group?")
            .setMessage("This disconnects from the current group and returns " +
                "to the group picker.")
            .setPositiveButton("Switch") { _, _ ->
                SessionStore(this).setAutoConnect(false)
                try { service?.stopAll() } catch (_: Exception) {}
                try { stopService(Intent(this, MeshService::class.java)) } catch (_: Exception) {}
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleHostAlreadyLive() {
        if (isFinishing) return
        SessionStore(this).setAutoConnect(false)
        AlertDialog.Builder(this)
            .setTitle("Group already live")
            .setMessage("Another device is already hosting this group on " +
                "this onion address.\n\nIf you host here too, both devices " +
                "will publish the same hidden service and messages will " +
                "split into two disconnected groups.\n\nTap OK to go back, " +
                "then choose Join instead if you want to connect to the " +
                "existing host.")
            .setPositiveButton("OK") { _, _ ->
                try { service?.stopAll() } catch (_: Exception) {}
                try { stopService(Intent(this, MeshService::class.java)) } catch (_: Exception) {}
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun updateTtlBtn() {
        val label = AppConfig.ttlLabel(currentTtlMs)
        ttlBtn.text = "Disappearing: $label"
        ttlBtn.setTextColor(if (currentTtlMs > 0) COLOR_ACCENT else 0xFF8B98A5.toInt())
    }

    private fun showTtlDialog() {
        val values = longArrayOf(AppConfig.TTL_OFF, AppConfig.TTL_30S,
            AppConfig.TTL_5M, AppConfig.TTL_1H, AppConfig.TTL_1D)
        val labels = arrayOf("Off — keep messages", "30 seconds",
            "5 minutes", "1 hour", "1 day")
        AlertDialog.Builder(this)
            .setTitle("Disappearing messages")
            .setItems(labels) { _, which ->
                currentTtlMs = values[which]
                SessionStore(this).setMessageTtlMs(currentTtlMs)
                updateTtlBtn()
            }
            .show()
    }

    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        val replica = service?.getReplica()
        for (i in messagesBox.childCount - 1 downTo 0) {
            val v = messagesBox.getChildAt(i)
            val tag = v.tag
            if (tag !is RowMeta) continue
            if (tag.expiresAt <= 0 || now < tag.expiresAt) continue
            messagesBox.removeViewAt(i)
            renderedSigs.remove(tag.signature)
            reactionRows.remove(tag.signature)
            reactions.remove(tag.signature)
            photoCache.remove(tag.signature)
            tag.msgId?.let { id ->
                try { replica?.removeById(id) } catch (_: Exception) {}
                photoCache.remove(id)
                pendingTicks.remove(id)
                ackedBy.remove(id)
                readBy.remove(id)
                readSent.remove(id)
                failedSends.remove(id)
            }
        }
    }

    private fun sweepReplicaExpiry(replica: Replica, k: SecretKey) {
        if (currentTtlMs <= 0L) return

        val now = System.currentTimeMillis()
        for (line in replica.snapshot()) {
            try {
                val p = Protocol.unpack(line)
                if (p.size < 2 || p[0] != Protocol.MSG) continue
                val msgId = if (p.size >= 3) p[1] else null
                val cipher = if (p.size >= 3) p[2] else p[1]
                val plain = CryptoUtils.decrypt(cipher, k)
                if (plain.startsWith("REACT\u0001")) continue
                val parts = plain.split("\u0001")
                var ts = 0L; var ttl = 0L
                if (parts[0] == "PHOTO" && parts.size >= 4) {
                    ts = parts[2].toLongOrNull() ?: 0L
                    ttl = parts[3].toLongOrNull() ?: 0L
                } else if (parts.size >= 7) {
                    ts = parts[2].toLongOrNull() ?: 0L
                    ttl = parts[6].toLongOrNull() ?: 0L
                } else if (parts.size == 5) {
                    ts = parts[2].toLongOrNull() ?: 0L
                    ttl = parts[4].toLongOrNull() ?: 0L
                }
                if (ttl > 0 && ts > 0 && now >= ts + ttl && msgId != null) {
                    replica.removeById(msgId)
                }
            } catch (_: Exception) {}
        }
    }

    private fun tryStartNetwork() {
        if (networkStarted) return
        if (service == null) return
        if (keyHolder?.hasKey() != true) return
        networkStarted = true
        initNetwork()
    }

    private fun computeTypeLabel(): String = when {
        isHost -> "Host"
        isBackup -> "Backup node"
        else -> "Client"
    }

    private fun typeColor(): Int = when {
        isHost -> 0xFFFFB300.toInt()
        isBackup -> 0xFF5CFFC8.toInt()
        else -> 0xFF8B98A5.toInt()
    }

    private fun copyOnionToClipboard() {
        var value = fullOnion
        if (value.isNullOrEmpty()) value = peer
        if (value.isNullOrEmpty()) {
            Toast.makeText(this, "No onion yet", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("onion", value))
        Toast.makeText(this, "Onion copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareCredentials() {
        val s = SessionStore(this)
        val group = s.getGroupName()
        val pass = s.getGroupPassphrase()
        var onion = ""; var secret = ""; var pub = ""
        TorManager.get(this).exportHsBundle()?.let {
            val parts = it.split("|")
            if (parts.size >= 3) {
                onion = parts[0]; secret = parts[1]; pub = parts[2]
            }
        }
        if (onion.isEmpty() && peer.isNotEmpty()) onion = peer

        val b64g = Base64.encodeToString(group.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val b64p = Base64.encodeToString(pass.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val b64o = Base64.encodeToString(onion.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val payload = "MESHCHAT2|$b64g|$b64p|$b64o|$secret|$pub"

        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("creds", payload))

        val note = if (secret.isNotEmpty()) "Full identity copied (with onion keys)."
                   else "Group + onion copied (client-only)."
        AlertDialog.Builder(this)
            .setTitle("Group credentials copied")
            .setMessage(note)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showGroupQr() {
        val s = SessionStore(this)
        val group = s.getGroupName()
        val pass = s.getGroupPassphrase()
        if (group.isEmpty()) {
            Toast.makeText(this, "No group name set", Toast.LENGTH_SHORT).show()
            return
        }
        var onion = ""; var secret = ""; var pub = ""
        TorManager.get(this).exportHsBundle()?.let {
            val parts = it.split("|")
            if (parts.size >= 3) { onion = parts[0]; secret = parts[1]; pub = parts[2] }
        }
        if (onion.isEmpty() && peer.isNotEmpty()) onion = peer

        if (onion.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Not ready yet")
                .setMessage("Tor hasn't finished bootstrapping this group's " +
                    "hidden service. Wait until the ONION field in the drawer " +
                    "shows an address, then tap Show QR again.\n\nFirst run " +
                    "takes 30 to 90 seconds.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val b64g = Base64.encodeToString(group.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val b64p = Base64.encodeToString(pass.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val b64o = Base64.encodeToString(onion.toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE)
        val payload = "MESHCHAT2|$b64g|$b64p|$b64o|$secret|$pub"

        val px = (300 * resources.displayMetrics.density).toInt()

        val bmp: Bitmap
        try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 4
            bmp = BarcodeEncoder().encodeBitmap(payload,
                BarcodeFormat.QR_CODE, px, px, hints)
        } catch (e: Exception) {
            Log.e("MeshTrace", "QR generation failed", e)
            Toast.makeText(this, "Couldn't generate QR: ${e.message}",
                Toast.LENGTH_LONG).show()
            return
        }

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.gravity = Gravity.CENTER
        wrap.setBackgroundColor(Color.WHITE)
        wrap.setPadding(24, 24, 24, 24)

        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        wrap.addView(iv, LinearLayout.LayoutParams(px, px))

        val cap = TextView(this)
        cap.text = "Group: $group\nHost: ${shortenOnion(onion)}\n\n" +
            "If the camera won't scan, tap Copy text and paste on the other phone."
        cap.setTextColor(Color.DKGRAY)
        cap.textSize = 12f
        cap.gravity = Gravity.CENTER
        cap.setPadding(0, 16, 0, 0)
        wrap.addView(cap)

        AlertDialog.Builder(this)
            .setTitle("Scan on the other phone")
            .setView(wrap)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy text") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("creds", payload))
                Toast.makeText(this, "Credentials copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showClearHistoryConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Clear chat history?")
            .setMessage("This removes all messages on this device.")
            .setPositiveButton("Clear") { _, _ -> clearLocalHistory() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearLocalHistory() {
        try { service?.getReplica()?.clear() } catch (_: Exception) {}
        messagesBox.removeAllViews()
        pendingTicks.clear(); ackedBy.clear(); readBy.clear(); readSent.clear()
        renderedSigs.clear(); failedSends.clear()
        reactions.clear(); reactionRows.clear()
        photoCache.evictAll()
        synchronized(typingPeers) { typingPeers.clear() }
        typingBar.text = ""
        lastSender = null; lastMessageTs = 0; lastDividerDay = -1
        unreadDividerDrawn = true
        emptyStateView = null
        showEmptyStateIfNeeded()
    }

    private fun loadHistoryFromReplica() {
        val replica = service?.getReplica() ?: return
        val k = keyHolder?.get() ?: return
        sweepReplicaExpiry(replica, k)

        val lines = replica.snapshot()
        if (lines.isEmpty()) return

        val now = System.currentTimeMillis()
        for (line in lines) {
            try {
                val p = Protocol.unpack(line)
                if (p.size < 2 || p[0] != Protocol.MSG) continue
                val msgId = if (p.size >= 3) p[1] else null
                val cipher = if (p.size >= 3) p[2] else p[1]
                val plain = CryptoUtils.decrypt(cipher, k)

                if (plain.startsWith("REACT\u0001")) {
                    val rp = plain.split("\u0001")
                    if (rp.size >= 4) applyReaction(rp[1], rp[2], rp[3])
                    continue
                }

                val parts = plain.split("\u0001")
                val m = when {
                    parts[0] == "PHOTO" && parts.size >= 6 -> {
                        val ts = parts[2].toLongOrNull() ?: now
                        val ttl = parts[3].toLongOrNull() ?: 0L
                        val exp = if (ttl > 0) ts + ttl else 0L
                        PeerState.ChatMessage(parts[1], "[photo]",
                            name == parts[1], ts, null, null, exp,
                            parts[5], parts[4])
                    }
                    parts.size >= 7 -> {
                        val ts = parts[2].toLongOrNull() ?: now
                        val ttl = parts[6].toLongOrNull() ?: 0L
                        val exp = if (ttl > 0) ts + ttl else 0L
                        val rs = parts[3].ifEmpty { null }
                        val rb = parts[4].ifEmpty { null }
                        PeerState.ChatMessage(parts[1], parts[5],
                            name == parts[1], ts, rs, rb, exp)
                    }
                    parts.size == 6 -> {
                        val ts = parts[2].toLongOrNull() ?: now
                        val rs = parts[3].ifEmpty { null }
                        val rb = parts[4].ifEmpty { null }
                        PeerState.ChatMessage(parts[1], parts[5],
                            name == parts[1], ts, rs, rb, 0L)
                    }
                    parts.size == 5 -> {
                        val ts = parts[2].toLongOrNull() ?: now
                        val ttl = parts[4].toLongOrNull() ?: 0L
                        val exp = if (ttl > 0) ts + ttl else 0L
                        PeerState.ChatMessage(parts[1], parts[3],
                            name == parts[1], ts, null, null, exp)
                    }
                    parts.size >= 4 -> {
                        val ts = parts[2].toLongOrNull() ?: now
                        PeerState.ChatMessage(parts[1], parts[3],
                            name == parts[1], ts)
                    }
                    parts.size >= 3 -> PeerState.ChatMessage(parts[1],
                        parts[2], name == parts[1])
                    else -> null
                } ?: continue

                if (m.expiresAt > 0 && now >= m.expiresAt) continue
                addBubble(m, msgId)
                // Note: no auto-ack on history load. Ticks only reflect
                // wire-level acknowledgements received during this session.
            } catch (_: Exception) {}
        }

        if (renderedSigs.isNotEmpty()) {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshDrawerSnapshot() {
        val svc = service ?: return
        val srv = svc.getServer()
        if (srv != null) {
            val c = srv.getClientCount(); val b = srv.getBackupCount()
            if (c > 0) drawerMembers.text = "$c / ${MeshServer.MAX_CLIENTS}"
            if (b > 0) drawerBackups.text = "$b / ${MeshServer.MAX_BACKUPS}"
        } else {
            val cc = svc.getCachedClientCount(); val cb = svc.getCachedBackupCount()
            if (cc > 0) drawerMembers.text = "$cc / ${MeshServer.MAX_CLIENTS}"
            if (cb > 0) drawerBackups.text = "$cb / ${MeshServer.MAX_BACKUPS}"
        }
    }

    private fun updateSoundBtn() {
        val on = SessionStore(this).isSoundEnabled()
        soundBtn.text = "Sound: ${if (on) "ON" else "OFF"}"
        soundBtn.setTextColor(if (on) 0xFF8B98A5.toInt() else 0xFFFFA726.toInt())
    }

    private fun updateQuietBtn() {
        val s = SessionStore(this)
        if (!s.isQuietEnabled()) {
            quietBtn.text = "Quiet hours: OFF"
            quietBtn.setTextColor(0xFF8B98A5.toInt())
            return
        }
        quietBtn.text = "Quiet: ${fmtTime(s.getQuietStart())}–${fmtTime(s.getQuietEnd())}"
        quietBtn.setTextColor(if (s.isQuietNow()) 0xFFFFA726.toInt() else 0xFF8B98A5.toInt())
    }

    private fun fmtTime(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun showQuietDialog() {
        val options = arrayOf("Off", "22:00 – 07:00", "23:00 – 06:00", "00:00 – 08:00")
        val ranges = arrayOf(intArrayOf(0, 0), intArrayOf(22 * 60, 7 * 60),
            intArrayOf(23 * 60, 6 * 60), intArrayOf(0, 8 * 60))
        AlertDialog.Builder(this)
            .setTitle("Quiet hours")
            .setItems(options) { _, which ->
                val s = SessionStore(this)
                if (which == 0) s.setQuietEnabled(false)
                else {
                    s.setQuietEnabled(true)
                    s.setQuietStart(ranges[which][0])
                    s.setQuietEnd(ranges[which][1])
                }
                updateQuietBtn()
            }
            .show()
    }

    private fun openSearch() {
        searchActive = true
        searchBar?.visibility = View.VISIBLE
        searchInput?.setText("")
        searchInput?.requestFocus()
    }

    private fun closeSearch() {
        searchActive = false
        searchBar?.visibility = View.GONE
        searchInput?.setText("")
        for (i in 0 until messagesBox.childCount)
            messagesBox.getChildAt(i).visibility = View.VISIBLE
    }

    private fun applySearchFilter(query: String) {
        val q = query.trim().lowercase(Locale.US)
        for (i in 0 until messagesBox.childCount) {
            val v = messagesBox.getChildAt(i)
            if (q.isEmpty()) { v.visibility = View.VISIBLE; continue }
            val tag = v.tag
            if (tag is RowMeta) {
                v.visibility = if (tag.searchText.contains(q)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setReplyTo(m: PeerState.ChatMessage) {
        replyTo = m
        replyWho?.text = m.sender
        val preview = if (m.isImage) "[photo]" else m.body
        replyPreview?.text = if (preview.length > 80)
            preview.substring(0, 80) + "…" else preview
        replyBar?.visibility = View.VISIBLE
        input.requestFocus()
    }

    private fun clearReply() {
        replyTo = null
        replyBar?.visibility = View.GONE
    }

    private fun updateMuteBtn() {
        val s = SessionStore(this)
        if (s.isMuted()) {
            val until = s.getMutedUntil()
            val mins = Math.max(1, (until - System.currentTimeMillis()) / 60000)
            muteBtn.text = "Muted (${mins}m left)"
            muteBtn.setTextColor(0xFFFFA726.toInt())
        } else {
            muteBtn.text = "Mute notifications"
            muteBtn.setTextColor(0xFF8B98A5.toInt())
        }
    }

    private fun showMuteDialog() {
        val options = arrayOf("1 hour", "8 hours", "1 day", "Forever", "Unmute")
        AlertDialog.Builder(this)
            .setTitle("Mute notifications")
            .setItems(options) { _, which ->
                val s = SessionStore(this)
                val now = System.currentTimeMillis()
                when (which) {
                    0 -> s.setMutedUntil(now + 60L * 60L * 1000L)
                    1 -> s.setMutedUntil(now + 8L * 60L * 60L * 1000L)
                    2 -> s.setMutedUntil(now + 24L * 60L * 60L * 1000L)
                    3 -> s.setMutedUntil(Long.MAX_VALUE)
                    4 -> s.setMutedUntil(0L)
                }
                updateMuteBtn()
            }
            .show()
    }

    private fun updateSendBtn() {
        val hasText = input.text.toString().trim().isNotEmpty()
        val keyReady = keyHolder?.hasKey() == true
        val enabled = hasText && keyReady
        sendBtn.isEnabled = enabled

        val targetAlpha = if (enabled) 1f else 0f
        val targetScale = if (enabled) 1f else 0.7f
        if (Math.abs(sendBtn.alpha - targetAlpha) > 0.01f) {
            sendBtn.animate().alpha(targetAlpha)
                .scaleX(targetScale).scaleY(targetScale)
                .setDuration(160).start()
        }
        photoBtn?.isEnabled = keyReady
        photoBtn?.alpha = if (keyReady) 0.75f else 0.3f
    }

    private fun showEmptyStateIfNeeded() {
        if (emptyStateView != null) return
        if (messagesBox.childCount > 0) return
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(dp(24f), dp(48f), dp(24f), dp(48f))
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        val icon = TextView(this).apply {
            text = "💬"; textSize = 48f; gravity = Gravity.CENTER
        }
        wrap.addView(icon)

        val line1 = TextView(this).apply {
            text = "No messages yet"; textSize = 16f
            setTextColor(0xFF8B98A5.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(4f))
        }
        wrap.addView(line1)

        val line2 = TextView(this).apply {
            text = "Say hi to start the conversation"; textSize = 13f
            setTextColor(0xFF5B6A75.toInt()); gravity = Gravity.CENTER
        }
        wrap.addView(line2)

        emptyStateView = wrap
        messagesBox.addView(wrap)
    }

    private fun hideEmptyState() {
        emptyStateView?.let { messagesBox.removeView(it); emptyStateView = null }
    }

    private fun dayOfYear(ts: Long): Int {
        val c = Calendar.getInstance(); c.timeInMillis = ts
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun dayLabel(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Today"
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (yest.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            yest.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Yesterday"
        return dayFmt.format(Date(ts))
    }

    private fun maybeAddDateDivider(ts: Long) {
        val day = dayOfYear(ts)
        if (day == lastDividerDay) return
        lastDividerDay = day
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(dp(8f), dp(14f), dp(8f), dp(6f))
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        val pill = TextView(this).apply {
            text = dayLabel(ts); textSize = 11f
            setTextColor(0xFF8B98A5.toInt())
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        }
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(12f).toFloat()
        bg.setColor(0xFF1E2732.toInt())
        pill.background = bg
        wrap.addView(pill)
        messagesBox.addView(wrap)
    }

    private fun maybeAddUnreadDivider(ts: Long) {
        if (unreadDividerDrawn) return
        if (ts <= unreadThresholdTs) return
        unreadDividerDrawn = true
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(dp(8f), dp(10f), dp(8f), dp(6f))
        wrap.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        val l1 = View(this)
        l1.layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        l1.setBackgroundColor(COLOR_ACCENT)
        wrap.addView(l1)

        val label = TextView(this).apply {
            text = "  New messages  "; textSize = 11f
            setTextColor(COLOR_ACCENT)
            setTypeface(null, Typeface.BOLD)
        }
        wrap.addView(label)

        val l2 = View(this)
        l2.layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        l2.setBackgroundColor(COLOR_ACCENT)
        wrap.addView(l2)

        messagesBox.addView(wrap)
    }

    private fun bubbleDrawable(isMine: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(if (isMine) COLOR_MINE_BUBBLE else COLOR_THEIRS_BUBBLE)
        val r = dp(18f).toFloat()
        val small = dp(4f).toFloat()
        if (isMine) d.cornerRadii = floatArrayOf(r, r, r, r, small, small, r, r)
        else d.cornerRadii = floatArrayOf(r, r, r, r, r, r, small, small)
        return d
    }

    private fun makeAvatar(who: String): TextView {
        val size = dp(32f)
        val av = TextView(this)
        av.layoutParams = LinearLayout.LayoutParams(size, size)
        av.gravity = Gravity.CENTER
        av.textSize = 14f
        av.setTextColor(0xFFFFFFFF.toInt())
        av.setTypeface(null, Typeface.BOLD)
        av.text = if (who.isEmpty()) "?" else
            who.substring(0, 1).uppercase(Locale.getDefault())
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(avatarColor(who))
        av.background = bg
        return av
    }

    private fun updateTick(msgId: String?) {
        if (msgId == null) return
        val tick = pendingTicks[msgId] ?: return
        if (failedSends.contains(msgId)) {
            tick.text = "!"
            tick.setTextColor(COLOR_TICK_FAILED)
            return
        }
        // Read wins over delivered. "✓✓" is reserved exclusively for the
        // read state; ack counts use a single ✓ with a numeric suffix so
        // they can never be confused for the read glyph.
        val readers = readBy[msgId]
        if (!readers.isNullOrEmpty()) {
            tick.text = "✓✓"
            tick.setTextColor(COLOR_TICK_READ)
            return
        }
        val ackers = ackedBy[msgId]
        val n = ackers?.size ?: 0
        when {
            n == 0 -> {
                tick.text = "◯"
                tick.setTextColor(COLOR_TICK_PENDING)
            }
            n == 1 -> {
                tick.text = "✓ 1"
                tick.setTextColor(COLOR_TICK_PARTIAL)
            }
            else -> {
                tick.text = "✓ $n"
                tick.setTextColor(COLOR_TICK_DELIVERED)
            }
        }
    }

    private fun signatureOf(m: PeerState.ChatMessage): String =
        "${m.sender}|${m.timestampMs}"

    private fun isEmojiOnlyMessage(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim()
        if (t.isEmpty() || t.length > 16) return false
        var emojiCount = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val cc = Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) return false
            if (isEmojiCodepoint(cp)) {
                if (cp != 0x200D && cp != 0xFE0F &&
                    !(cp in 0x1F3FB..0x1F3FF)) emojiCount++
            } else if (cp != ' '.code && cp != '\t'.code && cp != '\n'.code) {
                return false
            }
            i += cc
        }
        return emojiCount in 1..3
    }

    private fun isEmojiCodepoint(cp: Int): Boolean = when {
        cp == 0x200D -> true
        cp == 0xFE0F -> true
        cp in 0x1F3FB..0x1F3FF -> true
        cp in 0x1F300..0x1FAFF -> true
        cp in 0x2600..0x27BF -> true
        cp in 0x1F1E6..0x1F1FF -> true
        cp in 0x1F000..0x1F0FF -> true
        cp in 0x1F100..0x1F1FF -> true
        cp in 0x2B00..0x2BFF -> true
        cp == 0x2764 -> true
        else -> false
    }

    private fun addBubble(m: PeerState.ChatMessage, msgId: String?) {
        val sig = signatureOf(m)
        if (deletedSigs.contains(sig)) return
        if (renderedSigs.contains(sig)) return
        renderedSigs.add(sig)

        if (!m.mine && msgId != null) maybeSendRead(m, msgId)

        val outOfOrder = m.timestampMs < maxRenderedTs
        hideEmptyState()
        if (!outOfOrder) {
            maybeAddDateDivider(m.timestampMs)
            if (!m.mine) maybeAddUnreadDivider(m.timestampMs)
        }

        val isMine = m.mine
        val sameRun = !outOfOrder &&
            m.sender == lastSender &&
            (m.timestampMs - lastMessageTs) < GROUP_WINDOW_MS
        val showAvatar = !isMine && !sameRun
        val showSenderName = !isMine && !sameRun
        if (!outOfOrder) {
            lastSender = m.sender
            lastMessageTs = m.timestampMs
        }

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(dp(8f), if (sameRun) dp(1f) else dp(6f),
            dp(8f), if (sameRun) dp(1f) else dp(6f))
        outer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        content.gravity = if (isMine) Gravity.END else Gravity.START

        if (showSenderName) {
            val senderTv = TextView(this).apply {
                text = m.sender; textSize = 11f
                setTextColor(COLOR_NAME)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8f), 0, dp(8f), dp(2f))
            }
            content.addView(senderTv)
        }

        if (m.replyToSender != null && m.replyToBody != null) {
            val maxQuoteW = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            val quote = LinearLayout(this)
            quote.orientation = LinearLayout.VERTICAL
            quote.setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            val qbg = GradientDrawable()
            qbg.shape = GradientDrawable.RECTANGLE
            qbg.cornerRadius = dp(8f).toFloat()
            qbg.setColor(0x33000000)
            quote.background = qbg

            val qWho = TextView(this).apply {
                text = m.replyToSender; textSize = 10f
                setTextColor(COLOR_ACCENT)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(2f))
                maxWidth = maxQuoteW
            }
            val qBody = TextView(this).apply {
                text = m.replyToBody; textSize = 12f
                setTextColor(0xFF8B98A5.toInt())
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = maxQuoteW
            }
            quote.addView(qWho); quote.addView(qBody)

            val qlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            qlp.setMargins(dp(4f), 0, dp(4f), dp(4f))
            quote.layoutParams = qlp
            content.addView(quote)
        }

        var collapseToggle: TextView? = null

        val bubble: View = if (m.isImage) {
            val iv = ImageView(this)
            val cacheKey = msgId ?: sig
            var bmp = photoCache.get(cacheKey)
            if (bmp == null) {
                try {
                    val bytes = Base64.decode(m.imageB64, Base64.NO_WRAP)
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) photoCache.put(cacheKey, bmp)
                } catch (_: Exception) {}
            }
            if (bmp != null) iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val maxW = Math.min(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(),
                dp(PHOTO_BUBBLE_MAX_W_DP.toFloat()))
            val maxH = Math.min(
                (resources.displayMetrics.heightPixels * 0.45f).toInt(),
                dp(PHOTO_BUBBLE_MAX_H_DP.toFloat()))
            iv.maxWidth = maxW; iv.maxHeight = maxH
            iv.adjustViewBounds = true
            iv.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            iv.background = bubbleDrawable(isMine)
            iv
        } else {
            val tv = TextView(this)
            val emojiOnly = isEmojiOnlyMessage(m.body)
            tv.text = m.body
            if (emojiOnly) {
                tv.textSize = 44f
                tv.setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
            } else {
                tv.textSize = 15f
                tv.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                if (m.body.length > LONG_MSG_THRESHOLD) {
                    tv.maxLines = LONG_MSG_MAX_LINES
                    tv.ellipsize = TextUtils.TruncateAt.END
                    collapseToggle = TextView(this).apply {
                        text = "Show more"
                        textSize = 12f
                        setTextColor(COLOR_ACCENT)
                        setPadding(dp(8f), dp(2f), dp(8f), dp(4f))
                        setOnClickListener {
                            if (tv.maxLines == LONG_MSG_MAX_LINES) {
                                tv.maxLines = Int.MAX_VALUE
                                tv.ellipsize = null
                                text = "Show less"
                            } else {
                                tv.maxLines = LONG_MSG_MAX_LINES
                                tv.ellipsize = TextUtils.TruncateAt.END
                                text = "Show more"
                            }
                        }
                    }
                }
            }
            tv.setTextColor(if (isMine) COLOR_MINE_TEXT else COLOR_THEIRS_TEXT)
            tv.background = bubbleDrawable(isMine)
            tv.maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            tv
        }

        bubble.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        bubble.tag = outer

        bubble.setOnLongClickListener {
            showBubbleMenu(m, msgId, bubble); true
        }
        attachSwipeToReply(bubble, m)
        content.addView(bubble)

        collapseToggle?.let { content.addView(it) }

        val reactRow = LinearLayout(this)
        reactRow.orientation = LinearLayout.HORIZONTAL
        reactRow.gravity = if (isMine) Gravity.END else Gravity.START
        reactRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        reactRow.visibility = View.GONE
        reactRow.tag = sig
        content.addView(reactRow)
        reactionRows[sig] = reactRow

        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = if (isMine) Gravity.END else Gravity.START
        meta.setPadding(dp(6f), dp(2f), dp(6f), 0)
        meta.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        val time = TextView(this).apply {
            text = timeFmt.format(Date(m.timestampMs))
            textSize = 10f
            setTextColor(COLOR_TIME)
        }
        meta.addView(time)

        if (isMine) {
            val tick = TextView(this).apply {
                textSize = 11f
                setPadding(dp(4f), 0, 0, 0)
            }
            if (msgId == null) {
                tick.text = "✓ 1"
                tick.setTextColor(COLOR_TICK_DELIVERED)
            } else {
                tick.text = "◯"
                tick.setTextColor(COLOR_TICK_PENDING)
                pendingTicks[msgId] = tick
                if (!ackedBy.containsKey(msgId)) ackedBy[msgId] = HashSet()
                updateTick(msgId)
            }
            meta.addView(tick)
        }
        content.addView(meta)

        if (isMine) {
            val spacer = View(this)
            spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            row.addView(spacer); row.addView(content)
        } else {
            if (showAvatar) {
                val avWrap = LinearLayout(this)
                avWrap.orientation = LinearLayout.HORIZONTAL
                avWrap.layoutParams = LinearLayout.LayoutParams(dp(32f),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                avWrap.gravity = Gravity.TOP
                avWrap.addView(makeAvatar(m.sender))
                row.addView(avWrap)
            } else {
                val spacer = View(this)
                spacer.layoutParams = LinearLayout.LayoutParams(dp(32f), 1)
                row.addView(spacer)
            }
            val cp2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            cp2.leftMargin = dp(8f)
            content.layoutParams = cp2
            row.addView(content)
            val spacer = View(this)
            spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            row.addView(spacer)
        }

        outer.addView(row)

        val tag = RowMeta().apply {
            searchText = if (m.isImage) "[photo]" else (m.body ?: "").lowercase(Locale.US)
            this.msgId = msgId
            signature = sig
            sender = m.sender
            mine = isMine
            ts = m.timestampMs
            expiresAt = m.expiresAt
        }
        outer.tag = tag

        if (outOfOrder) {
            var insertAt = messagesBox.childCount
            for (i in 0 until messagesBox.childCount) {
                val t = messagesBox.getChildAt(i).tag
                if (t is RowMeta && m.timestampMs < t.ts) { insertAt = i; break }
            }
            messagesBox.addView(outer, insertAt)
        } else {
            messagesBox.addView(outer)
        }

        if (m.timestampMs > maxRenderedTs) maxRenderedTs = m.timestampMs
        if (reactions.containsKey(sig)) refreshReactionRow(sig)

        if (userAtBottom && !outOfOrder) {
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun attachSwipeToReply(v: View, m: PeerState.ChatMessage) {
        var startX = 0f; var startY = 0f
        val threshold = dp(72f); val ySlop = dp(50f)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x; startY = e.y; false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - startX
                    val dy = Math.abs(e.y - startY)
                    if (dx > threshold && dy < ySlop) { setReplyTo(m); true }
                    else false
                }
                else -> false
            }
        }
    }

    private fun showBubbleMenu(m: PeerState.ChatMessage, msgId: String?, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Reply")
        pm.menu.add(0, 2, 1, "React")
        pm.menu.add(0, 3, 2, "Copy")
        pm.menu.add(0, 4, 3, "Forward to input")
        if (m.mine && msgId != null) pm.menu.add(0, 5, 4, "Who's seen this")
        if (m.isImage) pm.menu.add(0, 7, 5, "Save image")
        pm.menu.add(0, 6, 6, "Delete for me")

        pm.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem?): Boolean {
                when (item?.itemId) {
                    1 -> setReplyTo(m)
                    2 -> showReactionPicker(m)
                    3 -> {
                        val body = if (m.isImage) "[photo]" else m.body
                        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("message", body))
                        Toast.makeText(this@ChatActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        if (m.isImage) Toast.makeText(this@ChatActivity,
                            "Photos can't be forwarded yet", Toast.LENGTH_SHORT).show()
                        else {
                            input.setText(m.body)
                            input.setSelection(input.text.length)
                            Toast.makeText(this@ChatActivity,
                                "Ready to send — edit and tap send", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> showSeenBy(msgId)
                    6 -> deleteForMe(m, anchor)
                    7 -> saveImageToGallery(m)
                }
                return true
            }
        })
        pm.show()
    }

    private fun showSeenBy(msgId: String?) {
        val readers = readBy[msgId]
        val ackers = ackedBy[msgId]
        val sb = StringBuilder()
        if (!readers.isNullOrEmpty()) {
            sb.append("Read by:\n")
            for (s in readers) sb.append(if (s == "_host") "the host" else s).append('\n')
        }
        if (!ackers.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Delivered to:\n")
            for (s in ackers) sb.append(if (s == "_host") "the host" else s).append('\n')
        }
        if (sb.isEmpty()) {
            Toast.makeText(this, "Nobody has acknowledged this yet",
                Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Message status")
            .setMessage(sb.toString().trim())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun deleteForMe(m: PeerState.ChatMessage, anchor: View) {
        AlertDialog.Builder(this)
            .setTitle("Delete for me?")
            .setMessage("This removes the message on this device only.")
            .setPositiveButton("Delete") { _, _ ->
                val sig = signatureOf(m)
                SessionStore(this).addDeletedSig(sig)
                deletedSigs.add(sig)

                var container = anchor.tag as? View
                if (container == null || container.parent !== messagesBox) {
                    var parent = anchor.parent
                    var hops = 0
                    while (parent != null && hops < 6) {
                        if (parent.parent === messagesBox) {
                            container = parent as? View
                            break
                        }
                        parent = parent.parent
                        hops++
                    }
                }
                if (container != null && container.parent === messagesBox) {
                    messagesBox.removeView(container)
                }

                reactionRows.remove(sig)
                reactions.remove(sig)
                photoCache.remove(sig)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateScrollBtn() {
        val show = !userAtBottom && messagesBox.childCount > 0
        if (show && scrollBtn.visibility != View.VISIBLE) {
            scrollBtn.visibility = View.VISIBLE
            scrollBtn.alpha = 0f
            scrollBtn.animate().alpha(1f).setDuration(150).start()
        } else if (!show && scrollBtn.visibility == View.VISIBLE) {
            scrollBtn.animate().alpha(0f).setDuration(150)
                .withEndAction { scrollBtn.visibility = View.GONE }.start()
        }
    }

    private fun toggleDrawer() { if (drawerOpen) closeDrawer() else openDrawer() }

    private fun openDrawer() {
        drawerOpen = true
        drawerScroll.visibility = View.VISIBLE
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.alpha = 0f
        val w = (300 * resources.displayMetrics.density).toInt()
        drawerScroll.translationX = -w.toFloat()
        drawerScroll.animate().translationX(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        drawerScrim.animate().alpha(1f).setDuration(220).start()
        manualRefreshQuiet()
    }

    private fun manualRefreshQuiet() {
        if (service == null) return
        refreshOnion(); refreshDrawerSnapshot(); updateSendBtn()
    }

    private fun closeDrawer() {
        drawerOpen = false
        val w = (300 * resources.displayMetrics.density).toInt()
        drawerScroll.animate().translationX(-w.toFloat()).setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { drawerScroll.visibility = View.GONE }.start()
        drawerScrim.animate().alpha(0f).setDuration(180)
            .withEndAction { drawerScrim.visibility = View.GONE }.start()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() { handleBack() }

    private fun handleBack() {
        if (drawerOpen) { closeDrawer(); return }
        if (searchActive) { closeSearch(); return }
        if (replyTo != null) { clearReply(); return }
        val now = System.currentTimeMillis()
        if (now - lastBackPress > BACK_EXIT_WINDOW_MS) {
            lastBackPress = now
            Toast.makeText(this, "Press back again to exit",
                Toast.LENGTH_SHORT).show()
        } else {
            finishAffinity()
        }
    }

    private fun doLeave() {
        SessionStore(this).setAutoConnect(false)
        val svc = service
        val wasHost = svc != null && svc.amHost() && svc.getServer() != null
        if (wasHost) {
            try { svc?.getServer()?.announceLeaderGone(name) } catch (_: Exception) {}
        }
        val teardown = {
            try { service?.stopAll() } catch (_: Exception) {}
            try { stopService(Intent(this, MeshService::class.java)) } catch (_: Exception) {}
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
        }
        if (wasHost) Handler(Looper.getMainLooper()).postDelayed(teardown, 500)
        else teardown()
    }

    private fun setStatus(text: String) {
        drawerStatus.text = text
        drawerStatus.setTextColor(
            if (currentlyUnstable) COLOR_STATUS_UNSTABLE else COLOR_STATUS_OK)
    }

    override fun onResume() {
        super.onResume()
        service?.clearChatNotifications(activeGroup)
        isForeground = true
        updateSendBtn()

        for (i in 0 until messagesBox.childCount) {
            val v = messagesBox.getChildAt(i)
            val tag = v.tag
            if (tag is RowMeta && !tag.mine && tag.msgId != null &&
                !readSent.contains(tag.msgId)) {
                val synthetic = PeerState.ChatMessage(tag.sender, "", false,
                    tag.ts, null, null, 0L)
                maybeSendRead(synthetic, tag.msgId!!)
            }
        }

        handler?.let {
            it.removeCallbacks(networkPoll); it.post(networkPoll)
            it.removeCallbacks(expiryTicker); it.post(expiryTicker)
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        if (maxRenderedTs > 0) SessionStore(this).setLastReadTs(maxRenderedTs)
        handler?.removeCallbacks(networkPoll)
        handler?.removeCallbacks(expiryTicker)
    }

    private fun maybeNotify(m: PeerState.ChatMessage) {
        if (m.mine) return
        if (isForeground) return
        if (service == null) return
        val now = System.currentTimeMillis()
        if (now - lastClearHistoryAt < REPLAY_NOTIFY_SUPPRESS_MS) return
        val s = SessionStore(this)
        if (s.isMuted() || s.isQuietNow()) return
        val body = if (m.isImage) "📷 Photo" else m.body
        service?.showChatNotification(m.sender, body)
    }

    private fun initNetwork() {
        val kh = keyHolder ?: return
        handler?.removeCallbacks(networkPoll); handler?.post(networkPoll)
        handler?.removeCallbacks(expiryTicker); handler?.post(expiryTicker)

        val svc = service ?: return

        if (isHost) {
            svc.startAsServer(name ?: "", kh, AppConfig.DEFAULT_PORT,
                object : MeshServer.Listener {
                    override fun onMessage(m: PeerState.ChatMessage) {
                        runOnUiThread { addBubble(m, null) }
                        maybeNotify(m)
                    }
                    override fun onStatus(s: String?) {
                        runOnUiThread {
                            if (s == MeshService.STATUS_HOST_ALREADY_LIVE) {
                                handleHostAlreadyLive(); return@runOnUiThread
                            }
                            setStatus(s ?: "")
                        }
                    }
                    override fun onTyping(who: String) {
                        runOnUiThread { showTyping(who) }
                    }
                    override fun onBackupsChanged(b: List<String>?) {}
                    override fun onRoster(c: Int, mc: Int, b: Int, mb: Int) {
                        runOnUiThread {
                            drawerMembers.text = "$c / $mc"
                            drawerBackups.text = "$b / $mb"
                        }
                    }
                    override fun onNames(names: List<String>?) { updateNames(names) }
                    override fun onAck(msgId: String, acker: String) {
                        runOnUiThread { markAcked(msgId, acker) }
                    }
                    override fun onRead(msgId: String, reader: String) {
                        runOnUiThread { markRead(msgId, reader) }
                    }
                    override fun onReaction(sender: String, targetSig: String,
                                            emoji: String) {
                        runOnUiThread { applyReaction(sender, targetSig, emoji) }
                    }
                })
        } else {
            svc.startAsClient(name ?: "", kh, peer, AppConfig.DEFAULT_PORT,
                object : MeshNode.Listener {
                    override fun onMessage(m: PeerState.ChatMessage) {
                        runOnUiThread { addBubble(m, null) }
                        maybeNotify(m)
                    }
                    override fun onStatus(s: String?) {
                        runOnUiThread { setStatus(s ?: "") }
                    }
                    override fun onTyping(who: String) {
                        runOnUiThread { showTyping(who) }
                    }
                    override fun onClearHistory() {
                        lastClearHistoryAt = System.currentTimeMillis()
                        runOnUiThread {
                            messagesBox.removeAllViews()
                            pendingTicks.clear(); ackedBy.clear()
                            readBy.clear(); readSent.clear()
                            renderedSigs.clear(); failedSends.clear()
                            reactions.clear(); reactionRows.clear()
                            photoCache.evictAll()
                            lastSender = null; lastMessageTs = 0
                            lastDividerDay = -1
                            emptyStateView = null
                            showEmptyStateIfNeeded()
                        }
                    }
                    override fun onRoster(c: Int, mc: Int, b: Int, mb: Int) {
                        runOnUiThread {
                            drawerMembers.text = "$c / $mc"
                            drawerBackups.text = "$b / $mb"
                        }
                        SessionStore(this@ChatActivity).setBackupsFull(b >= mb)
                    }
                    override fun onNames(names: List<String>?) { updateNames(names) }
                    override fun onAck(msgId: String, acker: String) {
                        runOnUiThread { markAcked(msgId, acker) }
                    }
                    override fun onRead(msgId: String, reader: String) {
                        runOnUiThread { markRead(msgId, reader) }
                    }
                    override fun onReaction(sender: String, targetSig: String,
                                            emoji: String) {
                        runOnUiThread { applyReaction(sender, targetSig, emoji) }
                    }
                    override fun onSendFailed(msgId: String?, reason: String?) {
                        runOnUiThread { handleSendFailed(msgId, reason) }
                    }
                }, isBackup)
        }

        handler?.postDelayed({ loadHistoryFromReplica() }, 200)
    }

    private fun updateNames(names: List<String>?) {
        runOnUiThread {
            if (names.isNullOrEmpty()) { drawerNames.text = "—"; return@runOnUiThread }
            drawerNames.text = names.joinToString(", ")
        }
    }

    private fun refreshOnion() {
        if (isHost) {
            val onion = service?.getOnionAddress()
            if (onion.isNullOrEmpty()) { drawerOnion.text = "Loading..."; return }
            fullOnion = onion
            drawerOnion.text = shortenOnion(onion)
            return
        }
        if (peer.isNotEmpty()) {
            fullOnion = peer
            drawerOnion.text = "[${peer.length}] ${shortenOnion(peer)}"
        } else {
            fullOnion = null
            drawerOnion.text = "[0] (no host onion set)"
        }
    }

    private fun shortenOnion(onion: String?): String {
        if (onion.isNullOrEmpty()) return "—"
        if (onion.length <= 24) return onion
        return onion.substring(0, 6) + "…" + onion.substring(onion.length - 10)
    }

    private fun sendFromHost(plain: String, msgId: String): Boolean {
        val k = keyHolder?.get() ?: run {
            handleSendFailed(msgId, "No group key"); return false
        }
        return try {
            val cipher = CryptoUtils.encrypt(plain, k)
            val line = Protocol.pack(Protocol.MSG, msgId, cipher)
            val srv = service?.getServer() ?: run {
                handleSendFailed(msgId, "Host not ready"); return false
            }
            srv.sendFromHost(line)
            true
        } catch (e: Exception) {
            handleSendFailed(msgId, e.message)
            false
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!) }
            catch (_: Exception) {}
            backCallback = null
        }
        handler?.removeCallbacksAndMessages(null)
        handler = null
        photoCache.evictAll()
        try { if (service != null) unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val AUTO_SCROLL_THRESHOLD_PX = 120
        private const val BACK_HINT_DEBOUNCE_MS = 2000L
        private const val BACK_EXIT_WINDOW_MS = 2000L
        private const val REPLAY_NOTIFY_SUPPRESS_MS = 3000L
        private const val EXPIRY_TICK_MS = 3000L
        private const val POLL_FAST_MS = 1000L
        private const val POLL_SLOW_MS = 10000L
        private const val GROUP_WINDOW_MS = 60000L
        private const val UNSTABLE_AGE_MS = 30000L
        private const val TYPING_TTL_MS = 3500L

        private const val REQ_PHOTO = 1003
        private const val PHOTO_MAX_DIM = 1024
        private const val PHOTO_MAX_BYTES = 400 * 1024
        private val PHOTO_QUALITIES = intArrayOf(75, 60, 50)
        private const val PHOTO_CACHE_BYTES = 8 * 1024 * 1024

        private const val PHOTO_BUBBLE_MAX_W_DP = 320
        private const val PHOTO_BUBBLE_MAX_H_DP = 420

        private const val LONG_MSG_THRESHOLD = 600
        private const val LONG_MSG_MAX_LINES = 12

        private const val COLOR_MINE_BUBBLE = 0xFF1F6F5C.toInt()
        private const val COLOR_MINE_TEXT = 0xFFE7E9EA.toInt()
        private const val COLOR_THEIRS_BUBBLE = 0xFF1E2732.toInt()
        private const val COLOR_THEIRS_TEXT = 0xFFE7E9EA.toInt()
        private const val COLOR_TIME = 0xFF8B98A5.toInt()
        private const val COLOR_NAME = 0xFF8B98A5.toInt()
        private const val COLOR_TICK_PENDING = 0xFF8B98A5.toInt()
        private const val COLOR_TICK_PARTIAL = 0xFF5CFFC8.toInt()
        private const val COLOR_TICK_DELIVERED = 0xFF3FA88B.toInt()
        private const val COLOR_TICK_FAILED = 0xFFFF5252.toInt()
        private const val COLOR_TICK_READ = 0xFF5CFFC8.toInt()
        private const val COLOR_ACCENT = 0xFF5CFFC8.toInt()
        private const val COLOR_STATUS_OK = 0xFF5CFFC8.toInt()
        private const val COLOR_STATUS_UNSTABLE = 0xFFFFA726.toInt()
        private const val COLOR_STATUS_OFFLINE = 0xFF54606C.toInt()
        private const val COLOR_REACT_FILL = 0xFF243240.toInt()
        private const val COLOR_REACT_BORDER = 0xFF2E4055.toInt()

        private val REACTION_EMOJIS = arrayOf("👍", "❤️", "😂", "😮", "😢", "👎", "🔥", "👏")
        private val AVATAR_PALETTE = intArrayOf(
            0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(),
            0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt(),
            0xFFF06292.toInt(), 0xFF9575CD.toInt())
    }
}