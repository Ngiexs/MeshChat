
package com.bitzlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_CAM = 1002;
    private static final String QR_PREFIX = "MESHCHAT2";
    private static final String NEW_GROUP_LABEL = "+ Create new group…";

    private SessionStore session;
    private GroupRegistry registry;

    private EditText    nameIn;
    private EditText    groupNameIn;
    private EditText    passphraseIn;
    private EditText    onionIn;
    private RadioButton hostRadio;
    private RadioButton joinRadio;
    private CheckBox    backupBox;
    private TextView    welcome;
    private TextView    modeHint;
    private TextView    backupHint;
    private Spinner     groupSpinner;
    private Button      newGroupBtn;
    private Button      deleteGroupBtn;

    private boolean suppressSpinnerCallback = false;

    // Set while a scanned payload is being applied so the spinner's
    // async callback doesn't wipe the fields we just filled in.
    private String  pendingGroupName = null;

    private static final String KEEP_ALIVE_TEXT =
            "MeshChat runs as a foreground service, so it survives "
            + "screen-off, memory pressure, swipe from Recents, and "
            + "reboot.\n\n"
            + "It cannot survive a Force stop from Settings.\n\n"
            + "For reliable operation on Samsung, Xiaomi, OnePlus, "
            + "Huawei and similar devices, also allow MeshChat to run "
            + "in the background in your phone's battery settings.";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        registry = new GroupRegistry(this);

        // Auto-reconnect to the last active group if we have a session.
        if (registry.getActiveGroup().length() > 0) {
            SessionStore s = new SessionStore(this);
            if (s.hasSession() && s.shouldAutoConnect()) {
                autoReconnect();
                return;
            }
        }

        boolean firstLaunch = !new SessionStore(this).hasSeenChecklist();
        if (firstLaunch) new SessionStore(this).setChecklistSeen(true);

        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        1001);
            }
        }

        requestBatteryExemption();

        nameIn       = (EditText)    findViewById(R.id.nameInput);
        groupNameIn  = (EditText)    findViewById(R.id.groupNameInput);
        passphraseIn = (EditText)    findViewById(R.id.passphraseInput);
        onionIn      = (EditText)    findViewById(R.id.onionInput);
        hostRadio    = (RadioButton) findViewById(R.id.hostRadio);
        joinRadio    = (RadioButton) findViewById(R.id.joinRadio);
        backupBox    = (CheckBox)    findViewById(R.id.backupCheck);
        welcome      = (TextView)    findViewById(R.id.welcomeText);
        modeHint     = (TextView)    findViewById(R.id.modeHint);
        backupHint   = (TextView)    findViewById(R.id.backupHint);
        groupSpinner = (Spinner)     findViewById(R.id.groupSpinner);
        newGroupBtn  = (Button)      findViewById(R.id.newGroupBtn);
        deleteGroupBtn = (Button)    findViewById(R.id.deleteGroupBtn);

        Button startBtn   = (Button) findViewById(R.id.startBtn);
        Button pasteCreds = (Button) findViewById(R.id.pasteCredsBtn);
        Button scanQrBtn  = (Button) findViewById(R.id.scanQrBtn);

        View.OnClickListener radioListener = new View.OnClickListener() {
            public void onClick(View v) { applyModeState(); }
        };
        hostRadio.setOnClickListener(radioListener);
        joinRadio.setOnClickListener(radioListener);

        ((android.widget.RadioGroup) findViewById(R.id.modeGroup))
                .setOnCheckedChangeListener(
                        new android.widget.RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(
                    android.widget.RadioGroup group, int checkedId) {
                applyModeState();
            }
        });

        startBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onConnectClicked(); }
        });

        pasteCreds.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pasteFromClipboard(); }
        });

        scanQrBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scanGroupQr(); }
        });

        newGroupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startNewGroup(); }
        });

        deleteGroupBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDeleteGroup(); }
        });

        if (welcome != null) {
            welcome.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    showKeepAliveDialog();
                    return true;
                }
            });
        }

        rebuildSpinnerAdapter();

        if (registry.getGroupNames().isEmpty()) {
            showFirstRunChooser();
        } else {
            List<String> groups = registry.getGroupNames();
            String active = registry.getActiveGroup();
            if (active.length() == 0) active = groups.get(0);
            registry.setActiveGroup(active);
            selectSpinnerEntry(active);
            loadGroupIntoForm(active);
        }
    }

    // ── First-run chooser ────────────────────────────────────────────

    private void showFirstRunChooser() {
        new AlertDialog.Builder(this)
                .setTitle("Welcome to MeshChat")
                .setMessage("Encrypted group chat over Tor.\n\n"
                        + "No accounts. No servers. No phone number.\n\n"
                        + "Create a new group, or join one that a friend "
                        + "invited you to.")
                .setPositiveButton("Create a group",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startNewGroupInternal();
                    }
                })
                .setNegativeButton("Join a group",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startJoinGroup();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void startJoinGroup() {
        session = new SessionStore(this);
        nameIn.setText("");
        groupNameIn.setText("");
        passphraseIn.setText("");
        onionIn.setText("");
        backupBox.setChecked(false);
        hostRadio.setChecked(false);
        joinRadio.setChecked(true);
        if (welcome != null) welcome.setText("Join a group");
        deleteGroupBtn.setVisibility(View.GONE);

        suppressSpinnerCallback = true;
        groupSpinner.setSelection(groupSpinner.getCount() - 1);
        suppressSpinnerCallback = false;

        applyModeState();
    }

    // ── Spinner management ───────────────────────────────────────────

    /**
     * Rebuilds the spinner adapter without touching the form. Safe to
     * call from anywhere, including after registering a new group from
     * a scan, because it never calls loadGroupIntoForm.
     */
    private void rebuildSpinnerAdapter() {
        List<String> groups = registry.getGroupNames();
        List<String> display = new ArrayList<String>();
        display.addAll(groups);
        display.add(NEW_GROUP_LABEL);

        ArrayAdapter<String> ad = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, display);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        suppressSpinnerCallback = true;
        groupSpinner.setAdapter(ad);
        suppressSpinnerCallback = false;

        groupSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v,
                                       int pos, long id) {
                if (suppressSpinnerCallback) return;
                if (pendingGroupName != null) return;   // scan in progress
                List<String> g = registry.getGroupNames();
                if (pos >= 0 && pos < g.size()) {
                    String chosen = g.get(pos);
                    registry.setActiveGroup(chosen);
                    loadGroupIntoForm(chosen);
                } else {
                    startNewGroup();
                }
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    /**
     * Moves the spinner selection to the named group without firing
     * the callback. Callers that want the form updated must call
     * loadGroupIntoForm explicitly afterward.
     */
    private void selectSpinnerEntry(String groupName) {
        List<String> groups = registry.getGroupNames();
        int idx = groups.indexOf(groupName);
        if (idx < 0) return;
        suppressSpinnerCallback = true;
        groupSpinner.setSelection(idx);
        suppressSpinnerCallback = false;
    }

    private void loadGroupIntoForm(String groupName) {
        session = new SessionStore(this, groupName);
        groupNameIn.setText(session.getGroupName().length() > 0
                ? session.getGroupName() : groupName);
        passphraseIn.setText(session.getGroupPassphrase());
        nameIn.setText(session.getName());
        onionIn.setText(session.getPeer());
        backupBox.setChecked(session.isBackup());

        if (session.isHost()) hostRadio.setChecked(true);
        else                  joinRadio.setChecked(true);

        if (welcome != null) welcome.setText("MeshChat");
        deleteGroupBtn.setVisibility(View.VISIBLE);
        applyModeState();
    }

    private void startNewGroup() {
        startNewGroupInternal();
    }

    private void startNewGroupInternal() {
        session = new SessionStore(this);
        nameIn.setText("");
        groupNameIn.setText("");
        passphraseIn.setText("");
        onionIn.setText("");
        backupBox.setChecked(false);
        hostRadio.setChecked(true);
        joinRadio.setChecked(false);
        if (welcome != null) welcome.setText("New group");
        deleteGroupBtn.setVisibility(View.GONE);

        suppressSpinnerCallback = true;
        groupSpinner.setSelection(groupSpinner.getCount() - 1);
        suppressSpinnerCallback = false;

        applyModeState();
    }

    private void confirmDeleteGroup() {
        final String active = registry.getActiveGroup();
        if (active.length() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete group '" + active + "'?")
                .setMessage("Removes the group from this device, "
                        + "including its message history and any onion "
                        + "keys stored locally. Other devices in the "
                        + "group are unaffected.")
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doDeleteGroup(active);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDeleteGroup(String groupName) {
        registry.removeGroup(groupName);
        new SessionStore(this, groupName).clear();
        registry.setActiveGroup("");
        Toast.makeText(this, "Deleted " + groupName,
                Toast.LENGTH_SHORT).show();

        rebuildSpinnerAdapter();
        List<String> groups = registry.getGroupNames();
        if (groups.isEmpty()) {
            showFirstRunChooser();
        } else {
            String next = groups.get(0);
            registry.setActiveGroup(next);
            selectSpinnerEntry(next);
            loadGroupIntoForm(next);
        }
    }

    // ── Onion normalisation ──────────────────────────────────────────

    private String normalizeOnion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        if (s.startsWith("http://"))  s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);

        if (s.contains("\u2026") || s.contains("...")) return null;

        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return null;
        }

        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            String tail = s.substring(colon + 1);
            boolean numeric = tail.length() > 0;
            for (int i = 0; i < tail.length(); i++) {
                if (!Character.isDigit(tail.charAt(i))) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) s = s.substring(0, colon);
        }

        if (!s.endsWith(".onion")) s = s + ".onion";

        if (s.length() < 22) return null;
        if (s.length() > 80) return null;

        int stop = s.length() - ".onion".length();
        for (int i = 0; i < stop; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                      || (c >= '2' && c <= '7');
            if (!ok) return null;
        }

        return s;
    }

    private void showOnionFormatError() {
        new AlertDialog.Builder(this)
                .setTitle("Bad onion address")
                .setMessage(
                    "The host onion field doesn't look like a valid "
                    + ".onion address.\n\n"
                    + "The address must be the FULL 56 characters, "
                    + "ending in .onion. Don't type the shortened form "
                    + "that the drawer shows with an ellipsis (…).\n\n"
                    + "The reliable way to get the address:\n\n"
                    + "1. On the host phone, open the drawer (☰)\n"
                    + "2. Tap Copy onion address\n"
                    + "3. On this phone, long-press the field and paste\n\n"
                    + "Or just tap Scan QR on the host's screen.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ── QR / paste handling ──────────────────────────────────────────

    private void pasteFromClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard empty",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (cs == null) return;
        Log.i("MeshTrace", "Clipboard paste: " + cs.length() + " chars");
        applyPayload(cs.toString());
    }

    private void scanGroupQr() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.CAMERA},
                    REQ_CAM);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        try {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("Point at the host's QR");
            integrator.setBeepEnabled(false);
            integrator.setOrientationLocked(false);
            integrator.setCaptureActivity(
                    com.journeyapps.barcodescanner.CaptureActivity.class);
            integrator.initiateScan();
        } catch (Exception e) {
            Log.e("MeshTrace", "QR scanner launch failed", e);
            Toast.makeText(this, "Scanner unavailable: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAM) {
            if (results.length > 0
                    && results[0]
                       == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchScanner();
            } else {
                Toast.makeText(this, "Camera permission required",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i("MeshTrace", "onActivityResult rc=" + requestCode
                + " result=" + resultCode);

        IntentResult result = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (result == null) {
            Log.w("MeshTrace", "Not a zxing result");
            return;
        }
        String contents = result.getContents();
        if (contents == null) {
            Log.w("MeshTrace", "Scan cancelled or empty");
            Toast.makeText(this, "Scan cancelled",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i("MeshTrace", "Scanned " + contents.length() + " chars");
        applyPayload(contents);
    }

    private void applyPayload(String rawPayload) {
        if (rawPayload == null) return;
        String payload = rawPayload.trim()
                .replace("\n", "").replace("\r", "");

        Log.i("MeshTrace", "applyPayload len=" + payload.length());

        // Case 1: a bare onion address with no pipes.
        if (payload.indexOf('|') < 0) {
            String onion = normalizeOnion(payload);
            if (onion != null) {
                onionIn.setText(onion);
                joinRadio.setChecked(true);
                hostRadio.setChecked(false);
                applyModeState();
                Toast.makeText(this, "Onion set: " + shortOnion(onion),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        // Case 2: a full MESHCHAT2 credential string.
        try {
            String[] parts = payload.split("\\|", -1);
            Log.i("MeshTrace", "Payload parts=" + parts.length
                    + " first=" + (parts.length > 0 ? parts[0] : "?"));

            if (parts.length < 3 || !QR_PREFIX.equals(parts[0])) {
                Toast.makeText(this,
                        "Not a MeshChat credential string (prefix "
                        + (parts.length > 0 ? parts[0] : "?") + ")",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String group = decodeField(parts.length > 1 ? parts[1] : "");
            String pass  = decodeField(parts.length > 2 ? parts[2] : "");
            String onion = decodeField(parts.length > 3 ? parts[3] : "");
            String sec   = parts.length > 4 ? parts[4] : "";
            String pub   = parts.length > 5 ? parts[5] : "";

            if (group.length() == 0) {
                Toast.makeText(this, "Payload has no group name",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String norm = normalizeOnion(onion);
            if (norm != null) onion = norm;

            Log.i("MeshTrace", "Parsed group=" + group
                    + " pass=" + (pass.length() > 0)
                    + " onion=" + shortOnion(onion)
                    + " hasSecret=" + (sec.length() > 0)
                    + " hasPub=" + (pub.length() > 0));

            // Register and activate the group.
            registry.addGroup(group);
            registry.setActiveGroup(group);

            // Import the hidden-service keys if present.
            if (onion.length() > 0 && sec.length() > 0 && pub.length() > 0) {
                boolean ok = TorManager.get(this)
                        .importHsBundle(group, onion, sec, pub);
                Log.i("MeshTrace", "importHsBundle=" + ok);
            }

            // CRITICAL: write the session to disk BEFORE rebuilding
            // the spinner, so the spinner's async onItemSelected
            // callback reads the correct values.
            SessionStore gs = new SessionStore(this, group);
            String existingName = gs.getName();
            gs.setGroupName(group);
            gs.setGroupPassphrase(pass);
            gs.setGroupEstablished(false);
            gs.setAutoConnect(false);
            gs.save(existingName, onion, false, false);
            Log.i("MeshTrace", "Session saved for group=" + group);

            pendingGroupName = group;

            rebuildSpinnerAdapter();
            selectSpinnerEntry(group);
            loadGroupIntoForm(group);

            groupNameIn.setText(group);
            passphraseIn.setText(pass);
            onionIn.setText(onion);
            joinRadio.setChecked(true);
            hostRadio.setChecked(false);
            applyModeState();

            pendingGroupName = null;

            if (welcome != null) welcome.setText("Joining " + group);

            StringBuilder msg = new StringBuilder();
            msg.append("Loaded group: ").append(group);
            if (onion.length() > 0)
                msg.append("\nHost: ").append(shortOnion(onion));
            if (sec.length() > 0)
                msg.append("\nFull identity (backup-capable)");
            else if (onion.length() > 0)
                msg.append("\nAddress only (client-only)");
            Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e("MeshTrace", "Bad payload", e);
            Toast.makeText(this, "Bad payload: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String decodeField(String b64) {
        if (b64 == null || b64.length() == 0) return "";
        try {
            return new String(Base64.decode(
                    b64, Base64.NO_WRAP | Base64.URL_SAFE));
        } catch (Exception e) {
            Log.w("MeshTrace", "decodeField failed: " + e.getMessage());
            return "";
        }
    }

    private String shortOnion(String o) {
        if (o == null) return "?";
        if (o.length() <= 16) return o;
        return o.substring(0, 8) + "..." + o.substring(o.length() - 4);
    }

    private void showKeepAliveDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Keeping MeshChat running")
                .setMessage(KEEP_ALIVE_TEXT)
                .setPositiveButton("OK", null)
                .setNeutralButton("Battery settings",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openBatterySettings();
                    }
                })
                .show();
    }

    /**
     * Opens the general battery optimization settings page. We can't
     * use the direct ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * intent because Google Play restricts that permission to a
     * whitelist of app categories.
     */
    private void openBatterySettings() {
        try {
            Intent intent = new Intent();
            intent.setAction(android.provider.Settings
                    .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        } catch (Exception ignored) {
            try {
                Intent fallback = new Intent(
                        android.provider.Settings.ACTION_SETTINGS);
                startActivity(fallback);
            } catch (Exception ignored2) { }
        }
    }

    private void autoReconnect() {
        SessionStore store = new SessionStore(this);
        String name = store.getName();
        if (name == null || name.length() == 0) {
            store.setAutoConnect(false);
            return;
        }
        final boolean isHost = store.isHost();
        String peer = store.getPeer();
        if (peer == null) peer = "";

        Intent svc = new Intent(this, MeshService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("name", name);
        i.putExtra("peer", peer);
        i.putExtra("isHost", isHost);
        i.putExtra("isBackup", store.isBackup());
        startActivity(i);
        finish();
    }

    private void applyModeState() {
        boolean joining = joinRadio.isChecked();
        onionIn.setVisibility(joining ? View.VISIBLE : View.GONE);

        if (modeHint != null) {
            if (joining) {
                modeHint.setText("Tap Scan QR if a friend showed you a "
                        + "QR code, or Paste credentials if they sent "
                        + "you a text string.");
            } else {
                modeHint.setText("You'll be the first member of a new "
                        + "group. After connecting, open ☰ and tap "
                        + "Show QR to invite others.");
            }
        }

        if (!joining) {
            backupBox.setEnabled(false);
            backupBox.setChecked(false);
            backupBox.setText("Backup node (not applicable as host)");
            if (backupHint != null) {
                backupHint.setText("The host is the primary. Backups "
                        + "only apply to joiners.");
            }
        } else {
            backupBox.setEnabled(true);
            backupBox.setText("Become a backup node");
            if (backupHint != null) {
                backupHint.setText("Backups take over automatically "
                        + "if the host disconnects.");
            }
        }
    }

    private void onConnectClicked() {
        String name = nameIn.getText().toString().trim();
        if (name.length() == 0) {
            Toast.makeText(this, "Please enter a name",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String groupName  = groupNameIn.getText().toString().trim();
        String passphrase = passphraseIn.getText().toString();
        if (groupName.length() == 0) groupName = "MeshChat";

        final boolean isHost = hostRadio.isChecked();
        String onion = "";

        if (!isHost) {
            String raw = onionIn.getText().toString();
            onion = normalizeOnion(raw);
            if (onion == null) {
                showOnionFormatError();
                return;
            }
            onionIn.setText(onion);
        }

        registry.addGroup(groupName);
        registry.setActiveGroup(groupName);

        SessionStore gs = new SessionStore(this, groupName);
        gs.setGroupName(groupName);
        gs.setGroupPassphrase(passphrase);
        gs.setGroupKey("");
        gs.setGroupEstablished(true);
        gs.setAutoConnect(true);
        gs.save(name, isHost ? "" : onion, isHost, backupBox.isChecked());

        Intent svc = new Intent(this, MeshService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("name", name);
        i.putExtra("peer", isHost ? "" : onion);
        i.putExtra("isHost", isHost);
        i.putExtra("isBackup", backupBox.isChecked());
        startActivity(i);
    }

    /**
     * On first launch, show the general battery settings so the user
     * can whitelist MeshChat. We deliberately do NOT use the direct
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent because
     * Google Play rejects apps that request that permission.
     */
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

            // Only show the hint dialog once. Don't force the user
            // into settings on the first screen.
            new AlertDialog.Builder(this)
                    .setTitle("Allow MeshChat to run in the background")
                    .setMessage("MeshChat is a foreground service, so it "
                            + "keeps running while your screen is off.\n\n"
                            + "For reliable message delivery, set "
                            + "MeshChat to 'Don't optimize' or 'Unrestricted' "
                            + "in your phone's battery settings.\n\n"
                            + "You can also find this later by long-pressing "
                            + "the MeshChat title on this screen.")
                    .setPositiveButton("Open battery settings",
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            openBatterySettings();
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Exception ignored) { }
    }
}