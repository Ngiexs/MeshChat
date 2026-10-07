package com.bitzlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;

public class ChatActivity extends Activity {

    private static final int  AUTO_SCROLL_THRESHOLD_PX = 120;
    private static final long BACK_HINT_DEBOUNCE_MS    = 2000;
    private static final long REPLAY_NOTIFY_SUPPRESS_MS = 3000;
    private static final long TIMESTAMP_TICK_MS         = 10000;
    private static final long EXPIRY_TICK_MS            = 3000;

    private static final long POLL_FAST_MS              = 1000L;
    private static final long POLL_SLOW_MS              = 10000L;

    private static final long GROUP_WINDOW_MS           = 60000;
    private static final long UNSTABLE_AGE_MS           = 30000;

    private static final int COLOR_MINE_BUBBLE     = 0xFF1F6F5C;
    private static final int COLOR_MINE_TEXT       = 0xFFE7E9EA;
    private static final int COLOR_THEIRS_BUBBLE   = 0xFF1E2732;
    private static final int COLOR_THEIRS_TEXT     = 0xFFE7E9EA;
    private static final int COLOR_TIME            = 0xFF8B98A5;
    private static final int COLOR_NAME            = 0xFF8B98A5;
    private static final int COLOR_TICK_PENDING    = 0xFF8B98A5;
    private static final int COLOR_TICK_PARTIAL    = 0xFF5CFFC8;
    private static final int COLOR_TICK_DELIVERED  = 0xFF3FA88B;
    private static final int COLOR_TICK_FAILED     = 0xFFFF5252;
    private static final int COLOR_ACCENT          = 0xFF5CFFC8;
    private static final int COLOR_STATUS_OK       = 0xFF5CFFC8;
    private static final int COLOR_STATUS_UNSTABLE = 0xFFFFA726;

    private static final int[] AVATAR_PALETTE = {
            0xFFE57373, 0xFF81C784, 0xFF64B5F6, 0xFFFFB74D,
            0xFFBA68C8, 0xFF4DB6AC, 0xFFF06292, 0xFF9575CD
    };

    private static class RowMeta {
        String searchText;
        String msgId;
        String signature;
        String sender;
        boolean mine;
        long ts;
        long expiresAt;
    }

    private MeshService service;
    private LinearLayout messagesBox;
    private ScrollView   scroller;
    private TextView     typingBar;
    private Button       scrollBtn;
    private Button       searchBtn;
    private EditText     input;
    private ImageButton  sendBtn;

    private LinearLayout searchBar;
    private EditText     searchInput;
    private Button       searchClose;
    private boolean      searchActive = false;

    private LinearLayout replyBar;
    private TextView     replyWho;
    private TextView     replyPreview;
    private Button       replyCancel;

    private Button       menuBtn;
    private ScrollView   drawerScroll;
    private LinearLayout drawerPanel;
    private View         drawerScrim;
    private TextView     headerGroup;
    private TextView     drawerGroup;
    private TextView     drawerName;
    private TextView     drawerType;
    private TextView     drawerStatus;
    private TextView     drawerOnion;
    private TextView     drawerMembers;
    private TextView     drawerNames;
    private TextView     drawerBackups;
    private TextView     drawerGroupName;
    private TextView     drawerPassphrase;
    private TextView     versionLabel;
    private Button       muteBtn;
    private Button       soundBtn;
    private Button       quietBtn;
    private Button       leaveBtn;
    private Button       clearHistoryBtn;
    private Button       copyOnionBtn;
    private Button       shareCredsBtn;
    private Button       showQrBtn;
    private Button       ttlBtn;
    private Button       switchGroupBtn;
    private Button       exportLogBtn;
    private boolean      drawerOpen = false;

    private String  name;
    private String  peer;
    private boolean isHost;
    private boolean isBackup;
    private String  activeGroup = "";
    private GroupKeyHolder keyHolder;
    private long    currentTtlMs = 0L;
    private long    lastTypingSent = 0;
    private Handler handler;

    private String typeLabel = "Client";
    private String fullOnion = null;

    private Runnable typingClearRunnable;
    private boolean userAtBottom = true;

    private long lastBackPress = 0;
    private android.window.OnBackInvokedCallback backCallback;

    private boolean isForeground = false;
    private long    lastClearHistoryAt = 0;

    private String  lastSender = null;
    private long    lastMessageTs = 0;
    private int     lastDividerDay = -1;

    private boolean currentlyUnstable = false;
    private PeerState.ChatMessage replyTo = null;

    private long    unreadThresholdTs = 0;
    private boolean unreadDividerDrawn = false;
    private long    maxRenderedTs = 0;

    private Set<String> deletedSigs = new HashSet<String>();
    private final Set<String> renderedSigs = new HashSet<String>();
    private final Set<String> failedSends = new HashSet<String>();

    private boolean networkStarted = false;

    private final Map<String, TextView> pendingTicks =
            new HashMap<String, TextView>();
    private final Map<String, Set<String>> ackedBy =
            new HashMap<String, Set<String>>();
    private final List<Object[]> timeEntries = new ArrayList<Object[]>();

    private View emptyStateView;

    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFmt =
            new SimpleDateFormat("d MMM", Locale.getDefault());

    private final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((MeshService.LocalBinder) b).get();
            tryStartNetwork();
            refreshOnion();
            refreshDrawerSnapshot();
        }
        public void onServiceDisconnected(ComponentName n) {
            service = null;
        }
    };

    private final Runnable networkPoll = new Runnable() {
        public void run() {
            if (service != null) {
                service.tickHeartbeat();
                if (service.getNode() != null) {
                    service.getNode().sendPing();
                    int pending = service.getNode().getPendingCount();
                    if (pending > 0) setStatus(pending + " queued (offline)");
                }
                refreshOnion();
                refreshDrawerSnapshot();
                updateSendBtn();
            }
            if (handler != null) handler.postDelayed(this, nextPollDelay());
        }
    };

    private long nextPollDelay() {
        boolean onionMissing = (fullOnion == null || fullOnion.length() == 0);
        return onionMissing ? POLL_FAST_MS : POLL_SLOW_MS;
    }

    private final Runnable timestampTicker = new Runnable() {
        public void run() {
            refreshTimestamps();
            checkStability();
            if (handler != null) handler.postDelayed(this, TIMESTAMP_TICK_MS);
        }
    };

    private final Runnable expiryTicker = new Runnable() {
        public void run() {
            sweepExpired();
            if (handler != null) handler.postDelayed(this, EXPIRY_TICK_MS);
        }
    };

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int avatarColor(String who) {
        if (who == null) who = "?";
        int idx = Math.abs(who.hashCode()) % AVATAR_PALETTE.length;
        return AVATAR_PALETTE[idx];
    }

    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_chat);

        handler = new Handler(Looper.getMainLooper());

        messagesBox  = (LinearLayout) findViewById(R.id.messagesContainer);
        scroller     = (ScrollView)   findViewById(R.id.scrollView);
        typingBar    = (TextView)     findViewById(R.id.typingBar);
        scrollBtn    = (Button)       findViewById(R.id.scrollBtn);
        searchBtn    = (Button)       findViewById(R.id.searchBtn);
        input        = (EditText)     findViewById(R.id.msgInput);
        sendBtn      = (ImageButton)  findViewById(R.id.sendBtn);

        searchBar    = (LinearLayout) findViewById(R.id.searchBar);
        searchInput  = (EditText)     findViewById(R.id.searchInput);
        searchClose  = (Button)       findViewById(R.id.searchClose);

        replyBar     = (LinearLayout) findViewById(R.id.replyBar);
        replyWho     = (TextView)     findViewById(R.id.replyWho);
        replyPreview = (TextView)     findViewById(R.id.replyPreview);
        replyCancel  = (Button)       findViewById(R.id.replyCancel);

        menuBtn      = (Button)       findViewById(R.id.menuBtn);
        headerGroup  = (TextView)     findViewById(R.id.headerGroup);
        drawerScroll = (ScrollView)   findViewById(R.id.drawerScroll);
        drawerPanel  = (LinearLayout) findViewById(R.id.drawerPanel);
        drawerScrim  = (View)         findViewById(R.id.drawerScrim);
        drawerGroup  = (TextView)     findViewById(R.id.drawerGroup);
        drawerName   = (TextView)     findViewById(R.id.drawerName);
        drawerType   = (TextView)     findViewById(R.id.drawerType);
        drawerStatus = (TextView)     findViewById(R.id.drawerStatus);
        drawerOnion  = (TextView)     findViewById(R.id.drawerOnion);
        drawerMembers = (TextView)    findViewById(R.id.drawerMembers);
        drawerNames   = (TextView)    findViewById(R.id.drawerNames);
        drawerBackups = (TextView)    findViewById(R.id.drawerBackups);
        drawerGroupName = (TextView)  findViewById(R.id.drawerGroupName);
        drawerPassphrase = (TextView) findViewById(R.id.drawerPassphrase);
        versionLabel  = (TextView)    findViewById(R.id.versionLabel);
        muteBtn       = (Button)      findViewById(R.id.muteBtn);
        soundBtn      = (Button)      findViewById(R.id.soundBtn);
        quietBtn      = (Button)      findViewById(R.id.quietBtn);
        leaveBtn      = (Button)      findViewById(R.id.leaveBtn);
        clearHistoryBtn = (Button)    findViewById(R.id.clearHistoryBtn);
        copyOnionBtn  = (Button)      findViewById(R.id.copyOnionBtn);
        shareCredsBtn = (Button)      findViewById(R.id.shareCredsBtn);
        showQrBtn     = (Button)      findViewById(R.id.showQrBtn);
        ttlBtn        = (Button)      findViewById(R.id.ttlBtn);
        switchGroupBtn = (Button)     findViewById(R.id.switchGroupBtn);
        exportLogBtn  = (Button)      findViewById(R.id.exportLogBtn);

        if (versionLabel != null) {
            versionLabel.setText("version " + AppConfig.VERSION_LABEL);
        }

        activeGroup = new GroupRegistry(this).getActiveGroup();
        SessionStore session = new SessionStore(this);
        currentTtlMs = session.getMessageTtlMs();

        // ── Launch extras, with SessionStore fallback ────────────────
        // The intent may be built by the service (normal launch) or by
        // a notification tap after the service has been killed. In both
        // cases we prefer the intent, but if any field is missing we
        // fall back to the on-disk session so name / peer / role are
        // never null going into the network init path.
        name     = getIntent().getStringExtra("name");
        peer     = getIntent().getStringExtra("peer");
        if (name == null || name.length() == 0) {
            String sn = session.getName();
            if (sn != null && sn.length() > 0) name = sn;
        }
        if (peer == null || peer.length() == 0) {
            String sp = session.getPeer();
            if (sp != null && sp.length() > 0) peer = sp;
        }
        if (peer == null) peer = "";

        isHost = getIntent().hasExtra("isHost")
                ? getIntent().getBooleanExtra("isHost", false)
                : session.isHost();
        isBackup = getIntent().hasExtra("isBackup")
                ? getIntent().getBooleanExtra("isBackup", false)
                : session.isBackup();

        Log.i("MeshTrace", "ChatActivity launch: name=" + name
                + " peer=" + (peer.length() > 0 ? "yes" : "no")
                + " isHost=" + isHost + " isBackup=" + isBackup);

        typeLabel = computeTypeLabel();

        String cachedKey  = session.getGroupKey();
        String passphrase = session.getGroupPassphrase();
        String groupName  = session.getGroupName();
        if (groupName == null || groupName.length() == 0) groupName = activeGroup;

        if (cachedKey != null && cachedKey.length() > 0) {
            keyHolder = new GroupKeyHolder(null);
            keyHolder.setFromBase64(cachedKey);
        } else if (passphrase != null && passphrase.length() > 0) {
            setStatus("Deriving key…");
            final String fPass  = passphrase;
            final String fGroup = (groupName != null && groupName.length() > 0)
                    ? groupName : "MeshChat";
            final SessionStore fSession = session;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        SecretKey derived = CryptoUtils.deriveKey(
                                fPass,
                                AppConfig.GROUP_SALT_PREFIX + fGroup);
                        final String b64 = Base64.encodeToString(
                                derived.getEncoded(), Base64.NO_WRAP);
                        fSession.setGroupKey(b64);
                        keyHolder = new GroupKeyHolder(derived);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setStatus("Key ready");
                                updateSendBtn();
                                tryStartNetwork();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setStatus("Key derivation failed");
                            }
                        });
                    }
                }
            }, "key-derive").start();
            keyHolder = new GroupKeyHolder(null);
        } else {
            try {
                SecretKey testKey = CryptoUtils.deriveKeyB64(
                        AppConfig.TEST_PASSPHRASE,
                        AppConfig.TEST_SALT_B64);
                keyHolder = new GroupKeyHolder(testKey);
            } catch (Exception e) {
                keyHolder = new GroupKeyHolder(null);
            }
        }

        deletedSigs = session.getDeletedSigs();
        unreadThresholdTs = session.getLastReadTs();
        if (unreadThresholdTs == 0) unreadDividerDrawn = true;

        drawerGroup.setText((groupName != null && groupName.length() > 0)
                ? groupName : "MeshChat");
        if (headerGroup != null) {
            headerGroup.setText((groupName != null && groupName.length() > 0)
                    ? groupName : "MeshChat");
        }
        drawerName.setText(name != null ? name : "");
        drawerType.setText(typeLabel);
        drawerType.setTextColor(typeColor());

        String displayGroup = (groupName == null || groupName.length() == 0)
                ? "MeshChat" : groupName;
        drawerGroupName.setText(displayGroup);
        if (passphrase == null || passphrase.length() == 0) {
            drawerPassphrase.setText("(blank — using test key)");
            drawerPassphrase.setTextColor(0xFFFFA726);
        } else {
            StringBuilder stars = new StringBuilder();
            for (int i = 0; i < Math.min(passphrase.length(), 12); i++) {
                stars.append("•");
            }
            drawerPassphrase.setText(stars + "  (long-press to reveal)");
            final String fPass2 = passphrase;
            drawerPassphrase.setOnLongClickListener(
                    new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    drawerPassphrase.setText(fPass2);
                    return true;
                }
            });
        }

        drawerOnion.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                copyOnionToClipboard();
                return true;
            }
        });

        setStatus("Connecting...");
        showEmptyStateIfNeeded();
        updateMuteBtn();
        updateSoundBtn();
        updateQuietBtn();
        updateTtlBtn();

        scroller.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                View child = scroller.getChildAt(0);
                if (child == null) { userAtBottom = true; updateScrollBtn(); return; }
                int distanceFromBottom = child.getBottom()
                        - (scroller.getHeight() + scroller.getScrollY());
                userAtBottom = distanceFromBottom <= AUTO_SCROLL_THRESHOLD_PX;
                updateScrollBtn();
            }
        });

        scrollBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View child = scroller.getChildAt(0);
                if (child != null) scroller.smoothScrollTo(0, child.getBottom());
            }
        });

        menuBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleDrawer(); }
        });
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });

        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSearch(); }
        });
        searchClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeSearch(); }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                applySearchFilter(s.toString());
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });

        replyCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearReply(); }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = new android.window.OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() { handleBack(); }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback);
        }

        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMuteDialog(); }
        });
        soundBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SessionStore ss = new SessionStore(ChatActivity.this);
                ss.setSoundEnabled(!ss.isSoundEnabled());
                updateSoundBtn();
            }
        });
        quietBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showQuietDialog(); }
        });
        clearHistoryBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showClearHistoryConfirm(); }
        });
        copyOnionBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyOnionToClipboard(); }
        });
        shareCredsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareCredentials(); }
        });
        showQrBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showGroupQr(); }
        });
        ttlBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTtlDialog(); }
        });
        switchGroupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchGroup(); }
        });
        if (exportLogBtn != null) {
            exportLogBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { exportLog(); }
            });
        }

        leaveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(ChatActivity.this)
                        .setTitle("Leave group?")
                        .setMessage("This stops the MeshChat service on "
                                + "this device. You can rejoin at any time.")
                        .setPositiveButton("Leave",
                                new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                doLeave();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        bindService(new Intent(this, MeshService.class), conn,
                Context.BIND_AUTO_CREATE);

        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() == 0) return;
                if (keyHolder == null || !keyHolder.hasKey()) {
                    Toast.makeText(ChatActivity.this,
                            "Waiting for group key…", Toast.LENGTH_SHORT).show();
                    return;
                }
                input.setText("");

                long ts = System.currentTimeMillis();
                String replySender = (replyTo != null) ? replyTo.sender : null;
                String replyBody   = (replyTo != null) ? replyTo.body   : null;
                long ttl = currentTtlMs;
                long expiresAt = ttl > 0 ? ts + ttl : 0L;

                if (service != null && service.amHost()
                        && service.getServer() != null) {
                    String msgId = UUID.randomUUID().toString();
                    String plain;
                    if (replySender != null) {
                        String safeReplyBody = replyBody == null ? ""
                                : replyBody.replace('\u0001', ' ');
                        plain = "MSG\u0001" + name
                                + "\u0001" + ts
                                + "\u0001" + replySender
                                + "\u0001" + safeReplyBody
                                + "\u0001" + text
                                + "\u0001" + ttl;
                    } else {
                        plain = "MSG\u0001" + name
                                + "\u0001" + ts
                                + "\u0001" + text
                                + "\u0001" + ttl;
                    }
                    boolean sent = sendFromHost(plain, msgId);
                    addBubble(new PeerState.ChatMessage(
                            name, text, true, ts, replySender, replyBody,
                            expiresAt), msgId);
                    if (sent) {
                        markAcked(msgId, "_host");
                    }
                } else if (service != null && service.getNode() != null
                        && !service.getNode().isFatalError()) {
                    String msgId = UUID.randomUUID().toString();
                    service.getNode().sendChat(text, msgId,
                            replySender, replyBody, ttl);
                    addBubble(new PeerState.ChatMessage(
                            name, text, true, ts, replySender, replyBody,
                            expiresAt), msgId);
                } else {
                    Toast.makeText(ChatActivity.this,
                            "Not connected — message not sent",
                            Toast.LENGTH_SHORT).show();
                }
                clearReply();
            }
        });

        updateSendBtn();

        input.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                updateSendBtn();
                long now = System.currentTimeMillis();
                if (now - lastTypingSent > 2000) {
                    lastTypingSent = now;
                    if (!isHost && service != null
                            && service.getNode() != null) {
                        service.getNode().sendTyping();
                    }
                }
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // A notification tap while we're already alive lands here. The
        // service may have been restarted between the notification
        // being posted and this delivery, so re-read the extras and
        // refresh any state that could have drifted.
        String nName = intent.getStringExtra("name");
        String nPeer = intent.getStringExtra("peer");
        boolean nHost = intent.hasExtra("isHost")
                ? intent.getBooleanExtra("isHost", false) : isHost;
        boolean nBackup = intent.hasExtra("isBackup")
                ? intent.getBooleanExtra("isBackup", false) : isBackup;

        boolean changed = false;
        if (nName != null && nName.length() > 0
                && (name == null || !nName.equals(name))) {
            name = nName;
            if (drawerName != null) drawerName.setText(name);
            changed = true;
        }
        if (nPeer != null && nPeer.length() > 0
                && (peer == null || !nPeer.equals(peer))) {
            peer = nPeer;
            changed = true;
        }
        if (nHost != isHost) {
            isHost = nHost;
            typeLabel = computeTypeLabel();
            if (drawerType != null) {
                drawerType.setText(typeLabel);
                drawerType.setTextColor(typeColor());
            }
            changed = true;
        }
        if (nBackup != isBackup) {
            isBackup = nBackup;
            typeLabel = computeTypeLabel();
            if (drawerType != null) {
                drawerType.setText(typeLabel);
                drawerType.setTextColor(typeColor());
            }
            changed = true;
        }

        if (changed) {
            Log.i("MeshTrace", "ChatActivity onNewIntent: refreshed state"
                    + " name=" + name + " isHost=" + isHost
                    + " isBackup=" + isBackup);
        }

        // Always refresh display in case the service reconnected.
        refreshOnion();
        refreshDrawerSnapshot();
        updateSendBtn();
    }

    private void exportLog() {
        Replica r = service != null ? service.getReplica() : null;
        if (r == null) {
            Toast.makeText(this, "No history to export",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> lines = r.snapshot();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        ClipboardManager cm = (ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(
                    "meshchat-log", sb.toString()));
            Toast.makeText(this,
                    "Log copied (" + lines.size() + " entries)",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void markAcked(String msgId, String acker) {
        if (msgId == null) return;
        failedSends.remove(msgId);
        Set<String> set = ackedBy.get(msgId);
        if (set == null) {
            set = new HashSet<String>();
            ackedBy.put(msgId, set);
        }
        set.add(acker);
        updateTick(msgId);
    }

    private void handleSendFailed(String msgId, String reason) {
        if (msgId != null) {
            failedSends.add(msgId);
            updateTick(msgId);
        }
        if (isFinishing()) return;
        Toast.makeText(ChatActivity.this,
                "Message not delivered — " + reason,
                Toast.LENGTH_LONG).show();
    }

    private void switchGroup() {
        new AlertDialog.Builder(this)
                .setTitle("Switch group?")
                .setMessage("This disconnects from the current group "
                        + "and returns to the group picker.")
                .setPositiveButton("Switch",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new SessionStore(ChatActivity.this).setAutoConnect(false);
                        try { if (service != null) service.stopAll(); }
                        catch (Exception e) { }
                        try {
                            stopService(new Intent(ChatActivity.this,
                                    MeshService.class));
                        } catch (Exception e) { }
                        Intent i = new Intent(ChatActivity.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleHostAlreadyLive() {
        if (isFinishing()) return;

        new SessionStore(ChatActivity.this).setAutoConnect(false);

        new AlertDialog.Builder(ChatActivity.this)
                .setTitle("Group already live")
                .setMessage("Another device is already hosting this "
                        + "group on this onion address.\n\n"
                        + "If you host here too, both devices will "
                        + "publish the same hidden service and messages "
                        + "will split into two disconnected groups.\n\n"
                        + "Tap OK to go back, then choose Join instead "
                        + "if you want to connect to the existing host.")
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            if (service != null) service.stopAll();
                        } catch (Exception e) { }
                        try {
                            stopService(new Intent(ChatActivity.this,
                                    MeshService.class));
                        } catch (Exception e) { }
                        Intent i = new Intent(ChatActivity.this,
                                MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void updateTtlBtn() {
        if (ttlBtn == null) return;
        String label = AppConfig.ttlLabel(currentTtlMs);
        ttlBtn.setText("Disappearing: " + label);
        ttlBtn.setTextColor(currentTtlMs > 0 ? COLOR_ACCENT : 0xFF8B98A5);
    }

    private void showTtlDialog() {
        final long[] values = {
                AppConfig.TTL_OFF,
                AppConfig.TTL_30S,
                AppConfig.TTL_5M,
                AppConfig.TTL_1H,
                AppConfig.TTL_1D
        };
        final String[] labels = {
                "Off — keep messages",
                "30 seconds",
                "5 minutes",
                "1 hour",
                "1 day"
        };
        new AlertDialog.Builder(this)
                .setTitle("Disappearing messages")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        currentTtlMs = values[which];
                        new SessionStore(ChatActivity.this)
                                .setMessageTtlMs(currentTtlMs);
                        updateTtlBtn();
                    }
                })
                .show();
    }

    private void sweepExpired() {
        if (messagesBox == null) return;
        long now = System.currentTimeMillis();
        Replica replica = service != null ? service.getReplica() : null;

        for (int i = messagesBox.getChildCount() - 1; i >= 0; i--) {
            View v = messagesBox.getChildAt(i);
            Object tag = v.getTag();
            if (!(tag instanceof RowMeta)) continue;
            RowMeta meta = (RowMeta) tag;
            if (meta.expiresAt <= 0) continue;
            if (now < meta.expiresAt) continue;

            messagesBox.removeViewAt(i);
            renderedSigs.remove(meta.signature);
            if (meta.msgId != null && replica != null) {
                try { replica.removeById(meta.msgId); }
                catch (Exception e) { }
            }
            if (meta.msgId != null) {
                pendingTicks.remove(meta.msgId);
                ackedBy.remove(meta.msgId);
                failedSends.remove(meta.msgId);
            }
        }
    }

    private void sweepReplicaExpiry(Replica replica, SecretKey k) {
        if (replica == null || k == null) return;
        long now = System.currentTimeMillis();
        List<String> lines = replica.snapshot();
        for (String line : lines) {
            try {
                String[] p = Protocol.unpack(line);
                if (p.length < 2 || !Protocol.MSG.equals(p[0])) continue;
                String msgId  = (p.length >= 3) ? p[1] : null;
                String cipher = (p.length >= 3) ? p[2] : p[1];
                String plain  = CryptoUtils.decrypt(cipher, k);
                String[] parts = plain.split("\u0001", -1);
                long ts = 0L, ttl = 0L;
                if (parts.length >= 7) {
                    try { ts = Long.parseLong(parts[2]); } catch (Exception e) { }
                    try { ttl = Long.parseLong(parts[6]); } catch (Exception e) { }
                } else if (parts.length == 5) {
                    try { ts = Long.parseLong(parts[2]); } catch (Exception e) { }
                    try { ttl = Long.parseLong(parts[4]); } catch (Exception e) { }
                }
                if (ttl > 0 && ts > 0 && now >= ts + ttl && msgId != null) {
                    replica.removeById(msgId);
                }
            } catch (Exception e) { }
        }
    }

    private void tryStartNetwork() {
        if (networkStarted) return;
        if (service == null) return;
        if (keyHolder == null || !keyHolder.hasKey()) return;
        networkStarted = true;
        initNetwork();
    }

    private String computeTypeLabel() {
        if (isHost) return "Host";
        if (isBackup) return "Backup node";
        return "Client";
    }

    private int typeColor() {
        if (isHost) return 0xFFFFB300;
        if (isBackup) return 0xFF5CFFC8;
        return 0xFF8B98A5;
    }

    private void copyOnionToClipboard() {
        String value = fullOnion;
        if (value == null || value.length() == 0) value = peer;
        if (value == null || value.length() == 0) {
            Toast.makeText(this, "No onion yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("onion", value));
            Toast.makeText(this, "Onion copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCredentials() {
        SessionStore s = new SessionStore(this);
        String group = s.getGroupName();
        if (group == null) group = "";
        String pass = s.getGroupPassphrase();
        if (pass == null) pass = "";

        String onion = "", secret = "", pub = "";
        String bundle = TorManager.get(this).exportHsBundle();
        if (bundle != null) {
            String[] parts = bundle.split("\\|", -1);
            if (parts.length >= 3) {
                onion  = parts[0] == null ? "" : parts[0];
                secret = parts[1] == null ? "" : parts[1];
                pub    = parts[2] == null ? "" : parts[2];
            }
        }
        if (onion.length() == 0 && peer != null) onion = peer;

        String b64g = Base64.encodeToString(group.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);
        String b64p = Base64.encodeToString(pass.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);
        String b64o = Base64.encodeToString(onion.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);

        String payload = "MESHCHAT2|" + b64g + "|" + b64p + "|" + b64o
                + "|" + secret + "|" + pub;

        ClipboardManager cm = (ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("creds", payload));
        }

        String note = (secret.length() > 0)
                ? "Full identity copied (with onion keys)."
                : "Group + onion copied (client-only).";

        new AlertDialog.Builder(this)
                .setTitle("Group credentials copied")
                .setMessage(note)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showGroupQr() {
        SessionStore s = new SessionStore(this);
        String group = s.getGroupName();
        if (group == null) group = "";
        String pass = s.getGroupPassphrase();
        if (pass == null) pass = "";

        if (group.length() == 0) {
            Toast.makeText(this, "No group name set",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String onion = "", secret = "", pub = "";
        String bundle = TorManager.get(this).exportHsBundle();
        if (bundle != null) {
            String[] parts = bundle.split("\\|", -1);
            if (parts.length >= 3) {
                onion  = parts[0] == null ? "" : parts[0];
                secret = parts[1] == null ? "" : parts[1];
                pub    = parts[2] == null ? "" : parts[2];
            }
        }
        if (onion.length() == 0 && peer != null) onion = peer;

        if (onion.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Not ready yet")
                    .setMessage(
                        "Tor hasn't finished bootstrapping this group's "
                        + "hidden service. Wait until the ONION field "
                        + "in the drawer shows an address, then tap "
                        + "Show QR again.\n\n"
                        + "First run takes 30 to 90 seconds.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String b64g = Base64.encodeToString(group.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);
        String b64p = Base64.encodeToString(pass.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);
        String b64o = Base64.encodeToString(onion.getBytes(),
                Base64.NO_WRAP | Base64.URL_SAFE);
        final String payload = "MESHCHAT2|" + b64g + "|" + b64p + "|"
                + b64o + "|" + secret + "|" + pub;

        Bitmap bmp;
        try {
            Map<EncodeHintType, Object> hints =
                    new java.util.HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);

            BarcodeEncoder enc = new BarcodeEncoder();
            bmp = enc.encodeBitmap(payload, BarcodeFormat.QR_CODE,
                    1200, 1200, hints);
        } catch (Exception e) {
            Log.e("MeshTrace", "QR generation failed", e);
            Toast.makeText(this, "Couldn't generate QR: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackgroundColor(Color.WHITE);
        wrap.setPadding(24, 24, 24, 24);

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = (int) (300 * getResources().getDisplayMetrics().density);
        wrap.addView(iv, new LinearLayout.LayoutParams(px, px));

        TextView cap = new TextView(this);
        cap.setText("Group: " + group
                + "\nHost: " + shortenOnion(onion)
                + "\n\nIf the camera won't scan, tap Copy text "
                + "and paste on the other phone.");
        cap.setTextColor(Color.DKGRAY);
        cap.setTextSize(12);
        cap.setGravity(Gravity.CENTER);
        cap.setPadding(0, 16, 0, 0);
        wrap.addView(cap);

        new AlertDialog.Builder(this)
                .setTitle("Scan on the other phone")
                .setView(wrap)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy text",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText(
                                    "creds", payload));
                            Toast.makeText(ChatActivity.this,
                                    "Credentials copied",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showClearHistoryConfirm() {
        new AlertDialog.Builder(this)
                .setTitle("Clear chat history?")
                .setMessage("This removes all messages on this device.")
                .setPositiveButton("Clear",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        clearLocalHistory();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearLocalHistory() {
        if (service != null && service.getReplica() != null) {
            try { service.getReplica().clear(); } catch (Exception e) { }
        }
        messagesBox.removeAllViews();
        pendingTicks.clear();
        ackedBy.clear();
        timeEntries.clear();
        renderedSigs.clear();
        failedSends.clear();
        lastSender = null;
        lastMessageTs = 0;
        lastDividerDay = -1;
        unreadDividerDrawn = true;
        emptyStateView = null;
        showEmptyStateIfNeeded();
    }

    private void loadHistoryFromReplica() {
        if (service == null || service.getReplica() == null) return;
        if (keyHolder == null || !keyHolder.hasKey()) return;
        SecretKey k = keyHolder.get();

        sweepReplicaExpiry(service.getReplica(), k);

        List<String> lines = service.getReplica().snapshot();
        if (lines.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (String line : lines) {
            try {
                String[] p = Protocol.unpack(line);
                if (p.length < 2 || !Protocol.MSG.equals(p[0])) continue;

                String msgId  = (p.length >= 3) ? p[1] : null;
                String cipher = (p.length >= 3) ? p[2] : p[1];
                String plain  = CryptoUtils.decrypt(cipher, k);
                String[] parts = plain.split("\u0001", -1);

                PeerState.ChatMessage m;
                if (parts.length >= 7) {
                    long ts;
                    try { ts = Long.parseLong(parts[2]); }
                    catch (NumberFormatException e) { ts = now; }
                    long ttl;
                    try { ttl = Long.parseLong(parts[6]); }
                    catch (NumberFormatException e) { ttl = 0L; }
                    long exp = ttl > 0 ? ts + ttl : 0L;
                    String rs = parts[3].isEmpty() ? null : parts[3];
                    String rb = parts[4].isEmpty() ? null : parts[4];
                    boolean mine = name != null && name.equals(parts[1]);
                    m = new PeerState.ChatMessage(parts[1], parts[5],
                            mine, ts, rs, rb, exp);
                } else if (parts.length == 6) {
                    long ts;
                    try { ts = Long.parseLong(parts[2]); }
                    catch (NumberFormatException e) { ts = now; }
                    String rs = parts[3].isEmpty() ? null : parts[3];
                    String rb = parts[4].isEmpty() ? null : parts[4];
                    boolean mine = name != null && name.equals(parts[1]);
                    m = new PeerState.ChatMessage(parts[1], parts[5],
                            mine, ts, rs, rb, 0L);
                } else if (parts.length == 5) {
                    long ts;
                    try { ts = Long.parseLong(parts[2]); }
                    catch (NumberFormatException e) { ts = now; }
                    long ttl;
                    try { ttl = Long.parseLong(parts[4]); }
                    catch (NumberFormatException e) { ttl = 0L; }
                    long exp = ttl > 0 ? ts + ttl : 0L;
                    boolean mine = name != null && name.equals(parts[1]);
                    m = new PeerState.ChatMessage(parts[1], parts[3],
                            mine, ts, null, null, exp);
                } else if (parts.length >= 4) {
                    long ts;
                    try { ts = Long.parseLong(parts[2]); }
                    catch (NumberFormatException e) { ts = now; }
                    boolean mine = name != null && name.equals(parts[1]);
                    m = new PeerState.ChatMessage(parts[1], parts[3],
                            mine, ts);
                } else if (parts.length >= 3) {
                    boolean mine = name != null && name.equals(parts[1]);
                    m = new PeerState.ChatMessage(parts[1], parts[2], mine);
                } else continue;

                if (m.expiresAt > 0 && now >= m.expiresAt) continue;
                addBubble(m, msgId);

                if (m.mine && msgId != null) {
                    markAcked(msgId, "_host");
                }
            } catch (Exception e) { }
        }

        if (!renderedSigs.isEmpty()) {
            scroller.post(new Runnable() {
                public void run() { scroller.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private void refreshDrawerSnapshot() {
        if (service == null) return;
        MeshServer srv = service.getServer();
        if (srv != null) {
            int c = srv.getClientCount();
            int b = srv.getBackupCount();
            if (drawerMembers != null && c > 0)
                drawerMembers.setText(c + " / " + MeshServer.MAX_CLIENTS);
            if (drawerBackups != null && b > 0)
                drawerBackups.setText(b + " / " + MeshServer.MAX_BACKUPS);
        } else {
            int cc = service.getCachedClientCount();
            int cb = service.getCachedBackupCount();
            if (drawerMembers != null && cc > 0)
                drawerMembers.setText(cc + " / " + MeshServer.MAX_CLIENTS);
            if (drawerBackups != null && cb > 0)
                drawerBackups.setText(cb + " / " + MeshServer.MAX_BACKUPS);
        }
    }

    private void updateSoundBtn() {
        if (soundBtn == null) return;
        SessionStore s = new SessionStore(this);
        soundBtn.setText("Sound: " + (s.isSoundEnabled() ? "ON" : "OFF"));
        soundBtn.setTextColor(s.isSoundEnabled() ? 0xFF8B98A5 : 0xFFFFA726);
    }

    private void updateQuietBtn() {
        if (quietBtn == null) return;
        SessionStore s = new SessionStore(this);
        if (!s.isQuietEnabled()) {
            quietBtn.setText("Quiet hours: OFF");
            quietBtn.setTextColor(0xFF8B98A5);
            return;
        }
        quietBtn.setText("Quiet: " + fmtTime(s.getQuietStart())
                + "–" + fmtTime(s.getQuietEnd()));
        quietBtn.setTextColor(s.isQuietNow() ? 0xFFFFA726 : 0xFF8B98A5);
    }

    private String fmtTime(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private void showQuietDialog() {
        final String[] options = {
                "Off", "22:00 – 07:00", "23:00 – 06:00", "00:00 – 08:00"
        };
        final int[][] ranges = {
                {0, 0}, {22 * 60, 7 * 60}, {23 * 60, 6 * 60}, {0, 8 * 60}
        };
        new AlertDialog.Builder(this)
                .setTitle("Quiet hours")
                .setItems(options, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        SessionStore s = new SessionStore(ChatActivity.this);
                        if (which == 0) s.setQuietEnabled(false);
                        else {
                            s.setQuietEnabled(true);
                            s.setQuietStart(ranges[which][0]);
                            s.setQuietEnd(ranges[which][1]);
                        }
                        updateQuietBtn();
                    }
                })
                .show();
    }

    private void openSearch() {
        searchActive = true;
        searchBar.setVisibility(View.VISIBLE);
        searchInput.setText("");
        searchInput.requestFocus();
    }

    private void closeSearch() {
        searchActive = false;
        searchBar.setVisibility(View.GONE);
        searchInput.setText("");
        for (int i = 0; i < messagesBox.getChildCount(); i++)
            messagesBox.getChildAt(i).setVisibility(View.VISIBLE);
    }

    private void applySearchFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (int i = 0; i < messagesBox.getChildCount(); i++) {
            View v = messagesBox.getChildAt(i);
            if (q.length() == 0) { v.setVisibility(View.VISIBLE); continue; }
            Object tag = v.getTag();
            if (tag instanceof RowMeta) {
                RowMeta m = (RowMeta) tag;
                v.setVisibility(m.searchText.contains(q)
                        ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void setReplyTo(PeerState.ChatMessage m) {
        replyTo = m;
        if (replyWho != null) replyWho.setText(m.sender);
        if (replyPreview != null) replyPreview.setText(
                m.body.length() > 80 ? m.body.substring(0, 80) + "…" : m.body);
        if (replyBar != null) replyBar.setVisibility(View.VISIBLE);
        if (input != null) input.requestFocus();
    }

    private void clearReply() {
        replyTo = null;
        if (replyBar != null) replyBar.setVisibility(View.GONE);
    }

    private void updateMuteBtn() {
        if (muteBtn == null) return;
        SessionStore s = new SessionStore(this);
        if (s.isMuted()) {
            long until = s.getMutedUntil();
            long mins = Math.max(1,
                    (until - System.currentTimeMillis()) / 60000);
            muteBtn.setText("Muted (" + mins + "m left)");
            muteBtn.setTextColor(0xFFFFA726);
        } else {
            muteBtn.setText("Mute notifications");
            muteBtn.setTextColor(0xFF8B98A5);
        }
    }

    private void showMuteDialog() {
        final String[] options = {
                "1 hour", "8 hours", "1 day", "Forever", "Unmute"
        };
        new AlertDialog.Builder(this)
                .setTitle("Mute notifications")
                .setItems(options, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        SessionStore s = new SessionStore(ChatActivity.this);
                        long now = System.currentTimeMillis();
                        switch (which) {
                            case 0: s.setMutedUntil(now + 60L * 60L * 1000L); break;
                            case 1: s.setMutedUntil(now + 8L * 60L * 60L * 1000L); break;
                            case 2: s.setMutedUntil(now + 24L * 60L * 60L * 1000L); break;
                            case 3: s.setMutedUntil(Long.MAX_VALUE); break;
                            case 4: s.setMutedUntil(0L); break;
                        }
                        updateMuteBtn();
                    }
                })
                .show();
    }

    private void checkStability() {
        if (service == null || service.getHeartbeat() == null) return;
        String target = isHost ? "_host_self" : peer;
        if (target == null) return;
        long age = service.getHeartbeat().ageMs(target);
        boolean unstable = (age != Long.MAX_VALUE) && age > UNSTABLE_AGE_MS;
        if (unstable != currentlyUnstable) {
            currentlyUnstable = unstable;
            if (drawerStatus != null) {
                drawerStatus.setTextColor(unstable
                        ? COLOR_STATUS_UNSTABLE : COLOR_STATUS_OK);
            }
        }
    }

    private void updateSendBtn() {
        if (sendBtn == null || input == null) return;
        boolean hasText = input.getText().toString().trim().length() > 0;
        boolean keyReady = keyHolder != null && keyHolder.hasKey();
        boolean enabled = hasText && keyReady;

        sendBtn.setEnabled(enabled);

        float targetAlpha = enabled ? 1f : 0f;
        float targetScale = enabled ? 1f : 0.7f;

        if (Math.abs(sendBtn.getAlpha() - targetAlpha) > 0.01f) {
            sendBtn.animate()
                    .alpha(targetAlpha)
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(160)
                    .start();
        }
    }

    private void showEmptyStateIfNeeded() {
        if (emptyStateView != null) return;
        if (messagesBox.getChildCount() > 0) return;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(24), dp(48), dp(24), dp(48));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText("💬"); icon.setTextSize(48);
        icon.setGravity(Gravity.CENTER); wrap.addView(icon);

        TextView line1 = new TextView(this);
        line1.setText("No messages yet"); line1.setTextSize(16);
        line1.setTextColor(0xFF8B98A5); line1.setGravity(Gravity.CENTER);
        line1.setPadding(0, dp(12), 0, dp(4)); wrap.addView(line1);

        TextView line2 = new TextView(this);
        line2.setText("Say hi to start the conversation");
        line2.setTextSize(13); line2.setTextColor(0xFF5B6A75);
        line2.setGravity(Gravity.CENTER); wrap.addView(line2);

        emptyStateView = wrap;
        messagesBox.addView(wrap);
    }

    private void hideEmptyState() {
        if (emptyStateView != null) {
            messagesBox.removeView(emptyStateView);
            emptyStateView = null;
        }
    }

    private String formatRelative(long ts, long now) {
        long diff = now - ts; if (diff < 0) diff = 0;
        long sec = diff / 1000;
        if (sec < 10) return "just now";
        if (sec < 60) return sec + "s ago";
        long min = sec / 60;
        if (min < 60) return min + "m ago";
        long hr = min / 60;
        if (hr < 24) return hr + "h ago";
        if (hr < 48) return "Yesterday " + timeFmt.format(new Date(ts));
        return dayFmt.format(new Date(ts)) + " " + timeFmt.format(new Date(ts));
    }

    private void refreshTimestamps() {
        long now = System.currentTimeMillis();
        for (Object[] e : timeEntries) {
            TextView tv = (TextView) e[0];
            long ts = ((Long) e[1]).longValue();
            tv.setText(formatRelative(ts, now));
        }
    }

    private int dayOfYear(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private String dayLabel(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }
        Calendar yest = Calendar.getInstance();
        yest.add(Calendar.DAY_OF_YEAR, -1);
        if (yest.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && yest.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        return dayFmt.format(new Date(ts));
    }

    private void maybeAddDateDivider(long ts) {
        int day = dayOfYear(ts);
        if (day == lastDividerDay) return;
        lastDividerDay = day;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(8), dp(14), dp(8), dp(6));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView pill = new TextView(this);
        pill.setText(dayLabel(ts)); pill.setTextSize(11);
        pill.setTextColor(0xFF8B98A5);
        pill.setPadding(dp(12), dp(4), dp(12), dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12)); bg.setColor(0xFF1E2732);
        pill.setBackground(bg);

        wrap.addView(pill); messagesBox.addView(wrap);
    }

    private void maybeAddUnreadDivider(long ts) {
        if (unreadDividerDrawn) return;
        if (ts <= unreadThresholdTs) return;
        unreadDividerDrawn = true;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(8), dp(10), dp(8), dp(6));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View line1 = new View(this);
        line1.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1f));
        line1.setBackgroundColor(COLOR_ACCENT); wrap.addView(line1);

        TextView label = new TextView(this);
        label.setText("  New messages  "); label.setTextSize(11);
        label.setTextColor(COLOR_ACCENT);
        label.setTypeface(null, Typeface.BOLD); wrap.addView(label);

        View line2 = new View(this);
        line2.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1f));
        line2.setBackgroundColor(COLOR_ACCENT); wrap.addView(line2);

        messagesBox.addView(wrap);
    }

    private GradientDrawable bubbleDrawable(boolean isMine) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(isMine ? COLOR_MINE_BUBBLE : COLOR_THEIRS_BUBBLE);
        float r = dp(18); float small = dp(4);
        if (isMine) d.setCornerRadii(new float[]{ r, r,  r, r,  small, small,  r, r });
        else        d.setCornerRadii(new float[]{ r, r,  r, r,  r, r,  small, small });
        return d;
    }

    private TextView makeAvatar(String who) {
        int size = dp(32);
        TextView av = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        av.setLayoutParams(lp); av.setGravity(Gravity.CENTER);
        av.setTextSize(14); av.setTextColor(0xFFFFFFFF);
        av.setTypeface(null, Typeface.BOLD);
        String letter = (who == null || who.isEmpty())
                ? "?" : who.substring(0, 1).toUpperCase(Locale.getDefault());
        av.setText(letter);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(avatarColor(who)); av.setBackground(bg);
        return av;
    }

    private void updateTick(String msgId) {
        if (msgId == null) return;
        TextView tick = pendingTicks.get(msgId);
        if (tick == null) return;

        if (failedSends.contains(msgId)) {
            tick.setText("!");
            tick.setTextColor(COLOR_TICK_FAILED);
            return;
        }

        Set<String> ackers = ackedBy.get(msgId);
        int n = (ackers == null) ? 0 : ackers.size();
        if (n == 0) { tick.setText("◯"); tick.setTextColor(COLOR_TICK_PENDING); }
        else if (n == 1) { tick.setText("✓ 1"); tick.setTextColor(COLOR_TICK_PARTIAL); }
        else { tick.setText("✓✓ " + n); tick.setTextColor(COLOR_TICK_DELIVERED); }
    }

    private String signatureOf(PeerState.ChatMessage m) {
        return m.sender + "|" + m.timestampMs;
    }

    private void addBubble(final PeerState.ChatMessage m, String msgId) {
        String sig = signatureOf(m);
        if (deletedSigs.contains(sig)) return;
        if (renderedSigs.contains(sig)) return;
        renderedSigs.add(sig);

        boolean outOfOrder = m.timestampMs < maxRenderedTs;

        hideEmptyState();
        if (!outOfOrder) {
            maybeAddDateDivider(m.timestampMs);
            if (!m.mine) maybeAddUnreadDivider(m.timestampMs);
        }

        final boolean isMine = m.mine;
        boolean sameRun = !outOfOrder
                && (m.sender != null && m.sender.equals(lastSender))
                && (m.timestampMs - lastMessageTs) < GROUP_WINDOW_MS;
        boolean showAvatar = !isMine && !sameRun;
        boolean showSenderName = !isMine && !sameRun;

        if (!outOfOrder) {
            lastSender = m.sender;
            lastMessageTs = m.timestampMs;
        }

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), sameRun ? dp(1) : dp(6), dp(8),
                sameRun ? dp(1) : dp(6));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.setGravity(isMine ? Gravity.END : Gravity.START);

        if (showSenderName) {
            TextView senderTv = new TextView(this);
            senderTv.setText(m.sender); senderTv.setTextSize(11);
            senderTv.setTextColor(COLOR_NAME);
            senderTv.setTypeface(null, Typeface.BOLD);
            senderTv.setPadding(dp(8), 0, dp(8), dp(2));
            content.addView(senderTv);
        }

        if (m.replyToSender != null && m.replyToBody != null) {
            int maxQuoteW = (int) (getResources()
                    .getDisplayMetrics().widthPixels * 0.72f);

            LinearLayout quote = new LinearLayout(this);
            quote.setOrientation(LinearLayout.VERTICAL);
            quote.setPadding(dp(10), dp(6), dp(10), dp(6));

            GradientDrawable qbg = new GradientDrawable();
            qbg.setShape(GradientDrawable.RECTANGLE);
            qbg.setCornerRadius(dp(8)); qbg.setColor(0x33000000);
            quote.setBackground(qbg);

            TextView qWho = new TextView(this);
            qWho.setText(m.replyToSender); qWho.setTextSize(10);
            qWho.setTextColor(COLOR_ACCENT);
            qWho.setTypeface(null, Typeface.BOLD);
            qWho.setPadding(0, 0, 0, dp(2));
            qWho.setMaxWidth(maxQuoteW);

            TextView qBody = new TextView(this);
            qBody.setText(m.replyToBody); qBody.setTextSize(12);
            qBody.setTextColor(0xFF8B98A5);
            qBody.setMaxLines(2); qBody.setEllipsize(TextUtils.TruncateAt.END);
            qBody.setMaxWidth(maxQuoteW);

            quote.addView(qWho); quote.addView(qBody);

            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            qlp.setMargins(dp(4), 0, dp(4), dp(4));
            quote.setLayoutParams(qlp);

            content.addView(quote);
        }

        final TextView body = new TextView(this);
        body.setText(m.body); body.setTextSize(15);
        body.setPadding(dp(14), dp(9), dp(14), dp(9));
        body.setTextColor(isMine ? COLOR_MINE_TEXT : COLOR_THEIRS_TEXT);
        body.setBackground(bubbleDrawable(isMine));
        body.setMaxWidth((int) (getResources()
                .getDisplayMetrics().widthPixels * 0.72f));
        body.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showBubbleMenu(m, msgId, row);
                return true;
            }
        });
        content.addView(body);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(isMine ? Gravity.END : Gravity.START);
        meta.setPadding(dp(6), dp(2), dp(6), 0);

        TextView time = new TextView(this);
        time.setText(formatRelative(m.timestampMs, System.currentTimeMillis()));
        time.setTextSize(10); time.setTextColor(COLOR_TIME);
        timeEntries.add(new Object[]{time, Long.valueOf(m.timestampMs)});
        meta.addView(time);

        if (isMine) {
            TextView tick = new TextView(this);
            tick.setTextSize(11);
            tick.setPadding(dp(4), 0, 0, 0);
            if (msgId == null) {
                tick.setText("✓ 1");
                tick.setTextColor(COLOR_TICK_DELIVERED);
            } else {
                tick.setText("◯");
                tick.setTextColor(COLOR_TICK_PENDING);
                pendingTicks.put(msgId, tick);
                if (!ackedBy.containsKey(msgId))
                    ackedBy.put(msgId, new HashSet<String>());
                updateTick(msgId);
            }
            meta.addView(tick);
        }
        content.addView(meta);

        if (isMine) {
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(spacer); row.addView(content);
        } else {
            if (showAvatar) {
                LinearLayout avatarWrap = new LinearLayout(this);
                avatarWrap.setOrientation(LinearLayout.HORIZONTAL);
                avatarWrap.setLayoutParams(new LinearLayout.LayoutParams(
                        dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
                avatarWrap.setGravity(Gravity.TOP);
                avatarWrap.addView(makeAvatar(m.sender));
                row.addView(avatarWrap);
            } else {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(32), 1));
                row.addView(spacer);
            }

            LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp2.leftMargin = dp(8);
            content.setLayoutParams(cp2);

            row.addView(content);
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(spacer);
        }

        RowMeta tag = new RowMeta();
        tag.searchText = m.body.toLowerCase(Locale.US);
        tag.msgId = msgId;
        tag.signature = signatureOf(m);
        tag.sender = m.sender;
        tag.mine = isMine;
        tag.ts = m.timestampMs;
        tag.expiresAt = m.expiresAt;
        row.setTag(tag);

        if (outOfOrder) {
            int insertAt = messagesBox.getChildCount();
            for (int i = 0; i < messagesBox.getChildCount(); i++) {
                View v = messagesBox.getChildAt(i);
                Object t = v.getTag();
                if (t instanceof RowMeta) {
                    RowMeta existing = (RowMeta) t;
                    if (m.timestampMs < existing.ts) {
                        insertAt = i;
                        break;
                    }
                }
            }
            messagesBox.addView(row, insertAt);
        } else {
            messagesBox.addView(row);
        }

        if (m.timestampMs > maxRenderedTs) maxRenderedTs = m.timestampMs;

        if (userAtBottom && !outOfOrder) {
            scroller.post(new Runnable() {
                public void run() { scroller.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private void showBubbleMenu(final PeerState.ChatMessage m,
                                final String msgId,
                                final View row) {
        List<String> items = new ArrayList<String>();
        items.add("Copy"); items.add("Reply");
        if (m.mine && msgId != null) items.add("Who's seen this");
        items.add("Delete for me");

        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setItems(arr, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String pick = arr[which];
                        if ("Copy".equals(pick)) {
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText(
                                        "message", m.body));
                                Toast.makeText(ChatActivity.this, "Copied",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else if ("Reply".equals(pick)) {
                            setReplyTo(m);
                        } else if ("Who's seen this".equals(pick)) {
                            showSeenBy(msgId);
                        } else if ("Delete for me".equals(pick)) {
                            deleteForMe(m, row);
                        }
                    }
                })
                .show();
    }

    private void showSeenBy(String msgId) {
        Set<String> set = ackedBy.get(msgId);
        if (set == null || set.isEmpty()) {
            Toast.makeText(this, "Nobody has acknowledged this yet",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(s.equals("_host") ? "the host" : s);
        }
        new AlertDialog.Builder(this)
                .setTitle("Seen by").setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    private void deleteForMe(final PeerState.ChatMessage m, final View row) {
        new AlertDialog.Builder(this)
                .setTitle("Delete for me?")
                .setMessage("This removes the message on this device only.")
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String sig = signatureOf(m);
                        new SessionStore(ChatActivity.this).addDeletedSig(sig);
                        deletedSigs.add(sig);
                        messagesBox.removeView(row);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateScrollBtn() {
        if (scrollBtn == null) return;
        boolean show = !userAtBottom && messagesBox.getChildCount() > 0;
        if (show && scrollBtn.getVisibility() != View.VISIBLE) {
            scrollBtn.setVisibility(View.VISIBLE);
            scrollBtn.setAlpha(0f);
            scrollBtn.animate().alpha(1f).setDuration(150).start();
        } else if (!show && scrollBtn.getVisibility() == View.VISIBLE) {
            scrollBtn.animate().alpha(0f).setDuration(150)
                    .withEndAction(new Runnable() {
                        public void run() { scrollBtn.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer(); else openDrawer();
    }

    private void openDrawer() {
        drawerOpen = true;
        drawerScroll.setVisibility(View.VISIBLE);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        int w = (int) (300 * getResources().getDisplayMetrics().density);
        drawerScroll.setTranslationX(-w);
        drawerScroll.animate().translationX(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        drawerScrim.animate().alpha(1f).setDuration(220).start();
        manualRefreshQuiet();
    }

    private void manualRefreshQuiet() {
        if (service == null) return;
        refreshOnion();
        refreshDrawerSnapshot();
        updateSendBtn();
    }

    private void closeDrawer() {
        drawerOpen = false;
        int w = (int) (300 * getResources().getDisplayMetrics().density);
        drawerScroll.animate().translationX(-w).setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() { drawerScroll.setVisibility(View.GONE); }
                }).start();
        drawerScrim.animate().alpha(0f).setDuration(180)
                .withEndAction(new Runnable() {
                    public void run() { drawerScrim.setVisibility(View.GONE); }
                }).start();
    }

    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (drawerOpen) { closeDrawer(); return; }
        if (searchActive) { closeSearch(); return; }
        if (replyTo != null) { clearReply(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBackPress > BACK_HINT_DEBOUNCE_MS) {
            lastBackPress = now;
            Toast.makeText(ChatActivity.this,
                    "Use ☰ → Leave group to exit",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void doLeave() {
        new SessionStore(ChatActivity.this).setAutoConnect(false);
        final boolean wasHost = service != null && service.amHost()
                && service.getServer() != null;
        if (wasHost) {
            try { service.getServer().announceLeaderGone(name); }
            catch (Exception e) { }
        }
        Runnable teardown = new Runnable() {
            public void run() {
                try { if (service != null) service.stopAll(); }
                catch (Exception e) { }
                try { stopService(new Intent(ChatActivity.this, MeshService.class)); }
                catch (Exception e) { }
                Intent i = new Intent(ChatActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i); finish();
            }
        };
        if (wasHost) {
            new Handler(Looper.getMainLooper()).postDelayed(teardown, 500);
        } else {
            teardown.run();
        }
    }

    private void setStatus(String text) {
        if (drawerStatus != null) {
            drawerStatus.setText(text);
            drawerStatus.setTextColor(currentlyUnstable
                    ? COLOR_STATUS_UNSTABLE : COLOR_STATUS_OK);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        updateSendBtn();
        if (handler != null) {
            handler.removeCallbacks(timestampTicker);
            handler.post(timestampTicker);
            handler.removeCallbacks(networkPoll);
            handler.post(networkPoll);
            handler.removeCallbacks(expiryTicker);
            handler.post(expiryTicker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        if (maxRenderedTs > 0) {
            new SessionStore(this).setLastReadTs(maxRenderedTs);
        }
        if (handler != null) {
            handler.removeCallbacks(timestampTicker);
            handler.removeCallbacks(networkPoll);
            handler.removeCallbacks(expiryTicker);
        }
    }

    private void maybeNotify(final PeerState.ChatMessage m) {
        if (m.mine) return;
        if (isForeground) return;
        if (service == null) return;
        long now = System.currentTimeMillis();
        if (now - lastClearHistoryAt < REPLAY_NOTIFY_SUPPRESS_MS) return;
        SessionStore s = new SessionStore(this);
        if (s.isMuted()) return;
        if (s.isQuietNow()) return;
        service.showChatNotification(m.sender, m.body);
    }

    private void initNetwork() {
        if (keyHolder == null) return;
        if (handler == null) handler = new Handler(Looper.getMainLooper());

        handler.removeCallbacks(networkPoll);
        handler.post(networkPoll);
        handler.removeCallbacks(timestampTicker);
        handler.postDelayed(timestampTicker, TIMESTAMP_TICK_MS);
        handler.removeCallbacks(expiryTicker);
        handler.post(expiryTicker);

        if (isHost) {
            service.startAsServer(name, keyHolder, AppConfig.DEFAULT_PORT,
                    new MeshServer.Listener() {
                public void onMessage(final PeerState.ChatMessage m) {
                    runOnUiThread(new Runnable() {
                        public void run() { addBubble(m, null); }
                    });
                    maybeNotify(m);
                }
                public void onStatus(final String s) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (MeshService.STATUS_HOST_ALREADY_LIVE.equals(s)) {
                                handleHostAlreadyLive();
                                return;
                            }
                            setStatus(s);
                        }
                    });
                }
                public void onTyping(final String who) { showTyping(who); }
                public void onBackupsChanged(final List<String> backups) { }
                public void onRoster(final int clients, final int maxClients,
                                     final int backups, final int maxBackups) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (drawerMembers != null)
                                drawerMembers.setText(clients + " / " + maxClients);
                            if (drawerBackups != null)
                                drawerBackups.setText(backups + " / " + maxBackups);
                        }
                    });
                }
                public void onNames(final List<String> names) {
                    updateNames(names);
                }
            });
        } else {
            service.startAsClient(name, keyHolder, peer, AppConfig.DEFAULT_PORT,
                    new MeshNode.Listener() {
                public void onMessage(final PeerState.ChatMessage m) {
                    runOnUiThread(new Runnable() {
                        public void run() { addBubble(m, null); }
                    });
                    maybeNotify(m);
                }
                public void onStatus(final String s) {
                    runOnUiThread(new Runnable() {
                        public void run() { setStatus(s); }
                    });
                }
                public void onTyping(final String who) { showTyping(who); }
                public void onClearHistory() {
                    lastClearHistoryAt = System.currentTimeMillis();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            messagesBox.removeAllViews();
                            pendingTicks.clear();
                            ackedBy.clear();
                            timeEntries.clear();
                            renderedSigs.clear();
                            failedSends.clear();
                            lastSender = null;
                            lastMessageTs = 0;
                            lastDividerDay = -1;
                            emptyStateView = null;
                            showEmptyStateIfNeeded();
                        }
                    });
                }
                public void onRoster(final int clients, final int maxClients,
                                     final int backups, final int maxBackups) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (drawerMembers != null)
                                drawerMembers.setText(clients + " / " + maxClients);
                            if (drawerBackups != null)
                                drawerBackups.setText(backups + " / " + maxBackups);
                        }
                    });
                    new SessionStore(ChatActivity.this)
                            .setBackupsFull(backups >= maxBackups);
                }
                public void onNames(final List<String> names) {
                    updateNames(names);
                }
                public void onAck(final String msgId, final String acker) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            markAcked(msgId, acker);
                        }
                    });
                }
                public void onSendFailed(final String msgId, final String reason) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            handleSendFailed(msgId, reason);
                        }
                    });
                }
            }, isBackup);
        }

        handler.postDelayed(new Runnable() {
            public void run() { loadHistoryFromReplica(); }
        }, 200);
    }

    private void updateNames(final List<String> names) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (drawerNames == null) return;
                if (names == null || names.isEmpty()) {
                    drawerNames.setText("—"); return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(names.get(i));
                }
                drawerNames.setText(sb.toString());
            }
        });
    }

    private void refreshOnion() {
        if (isHost) {
            String onion = service != null ? service.getOnionAddress() : null;
            if (onion == null || onion.length() == 0) {
                if (drawerOnion != null) drawerOnion.setText("Loading...");
                return;
            }
            fullOnion = onion;
            if (drawerOnion != null) drawerOnion.setText(shortenOnion(onion));
            return;
        }
        if (isBackup) {
            if (peer != null && peer.length() > 0) {
                fullOnion = peer;
                if (drawerOnion != null)
                    drawerOnion.setText(shortenOnion(peer));
            }
            return;
        }
        fullOnion = null;
        if (drawerOnion != null) drawerOnion.setText("—");
    }

    private String shortenOnion(String onion) {
        if (onion == null || onion.length() == 0) return "—";
        if (onion.length() <= 24) return onion;
        return onion.substring(0, 6) + "…"
                + onion.substring(onion.length() - 10);
    }

    private void showTyping(final String who) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (typingBar != null)
                    typingBar.setText("✏️ " + who + " is typing...");
                if (handler != null) {
                    if (typingClearRunnable != null)
                        handler.removeCallbacks(typingClearRunnable);
                    typingClearRunnable = new Runnable() {
                        public void run() {
                            if (typingBar != null) typingBar.setText("");
                        }
                    };
                    handler.postDelayed(typingClearRunnable, 3000);
                }
            }
        });
    }

    private boolean sendFromHost(String plain, String msgId) {
        SecretKey k = keyHolder != null ? keyHolder.get() : null;
        if (k == null) {
            if (msgId != null) handleSendFailed(msgId, "No group key");
            return false;
        }
        try {
            String cipher = CryptoUtils.encrypt(plain, k);
            String line = msgId != null
                    ? Protocol.pack(Protocol.MSG, msgId, cipher)
                    : Protocol.pack(Protocol.MSG, cipher);
            if (service == null || service.getServer() == null) {
                if (msgId != null) handleSendFailed(msgId, "Host not ready");
                return false;
            }
            service.getServer().sendFromHost(line);
            return true;
        } catch (Exception e) {
            if (msgId != null) handleSendFailed(msgId, e.getMessage());
            return false;
        }
    }

    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try {
                getOnBackInvokedDispatcher()
                        .unregisterOnBackInvokedCallback(backCallback);
            } catch (Exception ignored) { }
            backCallback = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (service != null) unbindService(conn);
        super.onDestroy();
    }
}