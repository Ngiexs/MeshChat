package com.bitzlink

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import java.util.Locale

class MainActivity : Activity() {

    private var session: SessionStore? = null
    private lateinit var registry: GroupRegistry

    private lateinit var nameIn: EditText
    private lateinit var groupNameIn: EditText
    private lateinit var passphraseIn: EditText
    private lateinit var onionIn: EditText
    private lateinit var hostRadio: RadioButton
    private lateinit var joinRadio: RadioButton
    private lateinit var backupBox: CheckBox
    private var welcome: TextView? = null
    private var modeHint: TextView? = null
    private var backupHint: TextView? = null
    private lateinit var groupSpinner: Spinner
    private lateinit var newGroupBtn: Button
    private lateinit var deleteGroupBtn: Button

    private var suppressSpinnerCallback = false

    /** Set while a scanned payload is being applied so the spinner's
     *  async callback doesn't wipe the fields we just filled in. */
    private var pendingGroupName: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        registry = GroupRegistry(this)

        // Auto-reconnect to the last active group if we have a session.
        if (registry.getActiveGroup().isNotEmpty()) {
            val s = SessionStore(this)
            if (s.hasSession() && s.shouldAutoConnect()) {
                autoReconnect()
                return
            }
        }

        val firstLaunch = !SessionStore(this).hasSeenChecklist()
        if (firstLaunch) SessionStore(this).setChecklistSeen(true)

        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf("android.permission.POST_NOTIFICATIONS"), 1001)
            }
        }

        requestBatteryExemption()

        nameIn = findViewById(R.id.nameInput)
        groupNameIn = findViewById(R.id.groupNameInput)
        passphraseIn = findViewById(R.id.passphraseInput)
        onionIn = findViewById(R.id.onionInput)
        hostRadio = findViewById(R.id.hostRadio)
        joinRadio = findViewById(R.id.joinRadio)
        backupBox = findViewById(R.id.backupCheck)
        welcome = findViewById(R.id.welcomeText)
        modeHint = findViewById(R.id.modeHint)
        backupHint = findViewById(R.id.backupHint)
        groupSpinner = findViewById(R.id.groupSpinner)
        newGroupBtn = findViewById(R.id.newGroupBtn)
        deleteGroupBtn = findViewById(R.id.deleteGroupBtn)

        val startBtn: Button = findViewById(R.id.startBtn)
        val pasteCreds: Button = findViewById(R.id.pasteCredsBtn)
        val scanQrBtn: Button = findViewById(R.id.scanQrBtn)

        val radioListener = View.OnClickListener { applyModeState() }
        hostRadio.setOnClickListener(radioListener)
        joinRadio.setOnClickListener(radioListener)

        (findViewById<RadioGroup>(R.id.modeGroup))
            .setOnCheckedChangeListener { _, _ -> applyModeState() }

        startBtn.setOnClickListener { onConnectClicked() }
        pasteCreds.setOnClickListener { pasteFromClipboard() }
        scanQrBtn.setOnClickListener { scanGroupQr() }
        newGroupBtn.setOnClickListener { startNewGroup() }
        deleteGroupBtn.setOnClickListener { confirmDeleteGroup() }

        welcome?.setOnLongClickListener {
            showKeepAliveDialog()
            true
        }

        rebuildSpinnerAdapter()

        val groups = registry.getGroupNames()
        if (groups.isEmpty()) {
            showFirstRunChooser()
        } else {
            var active = registry.getActiveGroup()
            if (active.isEmpty()) active = groups[0]
            registry.setActiveGroup(active)
            selectSpinnerEntry(active)
            loadGroupIntoForm(active)
        }
    }

    // ── First-run chooser ────────────────────────────────────────────

    private fun showFirstRunChooser() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to MeshChat")
            .setMessage("Encrypted group chat over Tor.\n\n" +
                "No accounts. No servers. No phone number.\n\n" +
                "Create a new group, or join one that a friend " +
                "invited you to.")
            .setPositiveButton("Create a group") { _, _ -> startNewGroupInternal() }
            .setNegativeButton("Join a group") { _, _ -> startJoinGroup() }
            .setCancelable(false)
            .show()
    }

    private fun startJoinGroup() {
        session = SessionStore(this)
        nameIn.setText("")
        groupNameIn.setText("")
        passphraseIn.setText("")
        onionIn.setText("")
        backupBox.isChecked = false
        hostRadio.isChecked = false
        joinRadio.isChecked = true
        welcome?.text = "Join a group"
        deleteGroupBtn.visibility = View.GONE

        suppressSpinnerCallback = true
        groupSpinner.setSelection(groupSpinner.count - 1)
        suppressSpinnerCallback = false

        applyModeState()
    }

    // ── Spinner management ───────────────────────────────────────────

    private fun rebuildSpinnerAdapter() {
        val groups = registry.getGroupNames()
        val display = ArrayList<String>(groups)
        display.add(NEW_GROUP_LABEL)

        val ad = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, display)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerCallback = true
        groupSpinner.adapter = ad
        suppressSpinnerCallback = false

        groupSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?,
                                            pos: Int, id: Long) {
                    if (suppressSpinnerCallback) return
                    if (pendingGroupName != null) return
                    val g = registry.getGroupNames()
                    if (pos in g.indices) {
                        val chosen = g[pos]
                        registry.setActiveGroup(chosen)
                        loadGroupIntoForm(chosen)
                    } else {
                        startNewGroup()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun selectSpinnerEntry(groupName: String) {
        val groups = registry.getGroupNames()
        val idx = groups.indexOf(groupName)
        if (idx < 0) return
        suppressSpinnerCallback = true
        groupSpinner.setSelection(idx)
        suppressSpinnerCallback = false
    }

    private fun loadGroupIntoForm(groupName: String) {
        val s = SessionStore(this, groupName)
        session = s
        groupNameIn.setText(if (s.getGroupName().isNotEmpty()) s.getGroupName() else groupName)
        passphraseIn.setText(s.getGroupPassphrase())
        nameIn.setText(s.getName())
        onionIn.setText(s.getPeer())
        backupBox.isChecked = s.isBackup()

        if (s.isHost()) hostRadio.isChecked = true
        else joinRadio.isChecked = true

        welcome?.text = "MeshChat"
        deleteGroupBtn.visibility = View.VISIBLE
        applyModeState()
    }

    private fun startNewGroup() { startNewGroupInternal() }

    private fun startNewGroupInternal() {
        session = SessionStore(this)
        nameIn.setText("")
        groupNameIn.setText("")
        passphraseIn.setText("")
        onionIn.setText("")
        backupBox.isChecked = false
        hostRadio.isChecked = true
        joinRadio.isChecked = false
        welcome?.text = "New group"
        deleteGroupBtn.visibility = View.GONE

        suppressSpinnerCallback = true
        groupSpinner.setSelection(groupSpinner.count - 1)
        suppressSpinnerCallback = false

        applyModeState()
    }

    private fun confirmDeleteGroup() {
        val active = registry.getActiveGroup()
        if (active.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete group '$active'?")
            .setMessage("Removes the group from this device, " +
                "including its message history and any onion " +
                "keys stored locally. Other devices in the " +
                "group are unaffected.")
            .setPositiveButton("Delete") { _, _ -> doDeleteGroup(active) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDeleteGroup(groupName: String) {
        registry.removeGroup(groupName)
        SessionStore(this, groupName).clear()
        registry.setActiveGroup("")
        Toast.makeText(this, "Deleted $groupName", Toast.LENGTH_SHORT).show()

        rebuildSpinnerAdapter()
        val groups = registry.getGroupNames()
        if (groups.isEmpty()) {
            showFirstRunChooser()
        } else {
            val next = groups[0]
            registry.setActiveGroup(next)
            selectSpinnerEntry(next)
            loadGroupIntoForm(next)
        }
    }

    // ── Onion normalisation ──────────────────────────────────────────

    private fun normalizeOnion(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()

        if (s.startsWith("http://")) s = s.substring(7)
        if (s.startsWith("https://")) s = s.substring(8)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)

        if (s.contains("\u2026") || s.contains("...")) return null

        for (c in s) if (c.isWhitespace()) return null

        val colon = s.lastIndexOf(':')
        if (colon >= 0) {
            val tail = s.substring(colon + 1)
            var numeric = tail.isNotEmpty()
            for (c in tail) if (!c.isDigit()) { numeric = false; break }
            if (numeric) s = s.substring(0, colon)
        }

        if (!s.endsWith(".onion")) s += ".onion"

        if (s.length < 22) return null
        if (s.length > 80) return null

        val stop = s.length - ".onion".length
        for (i in 0 until stop) {
            val c = s[i]
            val ok = (c in 'a'..'z') || (c in '2'..'7')
            if (!ok) return null
        }
        return s
    }

    private fun showOnionFormatError() {
        AlertDialog.Builder(this)
            .setTitle("Bad onion address")
            .setMessage(
                "The host onion field doesn't look like a valid " +
                ".onion address.\n\n" +
                "The address must be the FULL 56 characters, " +
                "ending in .onion. Don't type the shortened form " +
                "that the drawer shows with an ellipsis (…).\n\n" +
                "The reliable way to get the address:\n\n" +
                "1. On the host phone, open the drawer (☰)\n" +
                "2. Tap Copy onion address\n" +
                "3. On this phone, long-press the field and paste\n\n" +
                "Or just tap Scan QR on the host's screen.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ── QR / paste handling ──────────────────────────────────────────

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
        if (!cm.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            return
        }
        val cs = cm.primaryClip?.getItemAt(0)?.coerceToText(this) ?: return
        Log.i("MeshTrace", "Clipboard paste: ${cs.length} chars")
        applyPayload(cs.toString())
    }

    private fun scanGroupQr() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        try {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            integrator.setPrompt("Point at the host's QR")
            integrator.setBeepEnabled(false)
            integrator.setOrientationLocked(false)
            integrator.setCaptureActivity(
                com.journeyapps.barcodescanner.CaptureActivity::class.java)
            integrator.initiateScan()
        } catch (e: Exception) {
            Log.e("MeshTrace", "QR scanner launch failed", e)
            Toast.makeText(this, "Scanner unavailable: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_CAM) {
            if (results.isNotEmpty() &&
                results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                launchScanner()
            } else {
                Toast.makeText(this, "Camera permission required",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Android, kept for API 21 compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i("MeshTrace", "onActivityResult rc=$requestCode result=$resultCode")

        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            Log.w("MeshTrace", "Not a zxing result")
            return
        }
        val contents = result.contents
        if (contents == null) {
            Log.w("MeshTrace", "Scan cancelled or empty")
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i("MeshTrace", "Scanned ${contents.length} chars")
        applyPayload(contents)
    }

    private fun applyPayload(rawPayload: String?) {
        if (rawPayload == null) return
        val payload = rawPayload.trim().replace("\n", "").replace("\r", "")

        Log.i("MeshTrace", "applyPayload len=${payload.length}")

        // Case 1: bare onion address.
        if (payload.indexOf('|') < 0) {
            val onion = normalizeOnion(payload)
            if (onion != null) {
                onionIn.setText(onion)
                joinRadio.isChecked = true
                hostRadio.isChecked = false
                applyModeState()
                Toast.makeText(this, "Onion set: ${shortOnion(onion)}",
                    Toast.LENGTH_LONG).show()
                return
            }
        }

        // Case 2: MESHCHAT2 credential string.
        try {
            // Literal split. "|".toRegex() would be regex alternation and
            // split into individual characters, breaking the prefix check.
            val parts = payload.split("|").toTypedArray()
            Log.i("MeshTrace", "Payload parts=${parts.size} first=${parts.getOrNull(0) ?: "?"}")

            if (parts.size < 3 || QR_PREFIX != parts[0]) {
                Toast.makeText(this,
                    "Not a MeshChat credential string (prefix ${parts.getOrNull(0) ?: "?"})",
                    Toast.LENGTH_LONG).show()
                return
            }

            val group = decodeField(parts.getOrNull(1) ?: "")
            val pass  = decodeField(parts.getOrNull(2) ?: "")
            var onion = decodeField(parts.getOrNull(3) ?: "")
            val sec   = parts.getOrNull(4) ?: ""
            val pub   = parts.getOrNull(5) ?: ""

            if (group.isEmpty()) {
                Toast.makeText(this, "Payload has no group name",
                    Toast.LENGTH_LONG).show()
                return
            }

            val norm = normalizeOnion(onion)
            if (norm != null) onion = norm

            Log.i("MeshTrace", "Parsed group=$group pass=${pass.isNotEmpty()} " +
                "onion=${shortOnion(onion)} hasSecret=${sec.isNotEmpty()} " +
                "hasPub=${pub.isNotEmpty()}")

            registry.addGroup(group)
            registry.setActiveGroup(group)

            if (onion.isNotEmpty() && sec.isNotEmpty() && pub.isNotEmpty()) {
                val ok = TorManager.get(this).importHsBundle(group, onion, sec, pub)
                Log.i("MeshTrace", "importHsBundle=$ok")
            }

            val gs = SessionStore(this, group)
            val existingName = gs.getName()
            gs.setGroupName(group)
            gs.setGroupPassphrase(pass)
            gs.setGroupEstablished(false)
            gs.setAutoConnect(false)
            gs.save(existingName, onion, false, false)
            Log.i("MeshTrace", "Session saved for group=$group")

            pendingGroupName = group

            rebuildSpinnerAdapter()
            selectSpinnerEntry(group)
            loadGroupIntoForm(group)

            groupNameIn.setText(group)
            passphraseIn.setText(pass)
            onionIn.setText(onion)
            joinRadio.isChecked = true
            hostRadio.isChecked = false
            applyModeState()

            pendingGroupName = null

            welcome?.text = "Joining $group"

            val msg = StringBuilder()
            msg.append("Loaded group: ").append(group)
            if (onion.isNotEmpty()) msg.append("\nHost: ").append(shortOnion(onion))
            if (sec.isNotEmpty()) msg.append("\nFull identity (backup-capable)")
            else if (onion.isNotEmpty()) msg.append("\nAddress only (client-only)")
            Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e("MeshTrace", "Bad payload", e)
            Toast.makeText(this, "Bad payload: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeField(b64: String?): String {
        if (b64.isNullOrEmpty()) return ""
        return try {
            String(Base64.decode(b64, Base64.NO_WRAP or Base64.URL_SAFE),
                Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("MeshTrace", "decodeField failed: ${e.message}")
            ""
        }
    }

    private fun shortOnion(o: String?): String {
        if (o == null) return "?"
        if (o.length <= 16) return o
        return o.substring(0, 8) + "..." + o.substring(o.length - 4)
    }

    private fun showKeepAliveDialog() {
        AlertDialog.Builder(this)
            .setTitle("Keeping MeshChat running")
            .setMessage(KEEP_ALIVE_TEXT)
            .setPositiveButton("OK", null)
            .setNeutralButton("Battery settings") { _, _ -> openBatterySettings() }
            .show()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun autoReconnect() {
        val store = SessionStore(this)
        val name = store.getName()
        if (name.isEmpty()) {
            store.setAutoConnect(false)
            return
        }
        val isHost = store.isHost()
        val peer = store.getPeer().ifEmpty { "" }

        val svc = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc)
        else startService(svc)

        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("name", name)
            putExtra("peer", peer)
            putExtra("isHost", isHost)
            putExtra("isBackup", store.isBackup())
        })
        finish()
    }

    private fun applyModeState() {
        val joining = joinRadio.isChecked
        onionIn.visibility = if (joining) View.VISIBLE else View.GONE

        modeHint?.text = if (joining) {
            "Tap Scan QR if a friend showed you a " +
            "QR code, or Paste credentials if they sent " +
            "you a text string."
        } else {
            "You'll be the first member of a new " +
            "group. After connecting, open ☰ and tap " +
            "Show QR to invite others."
        }

        if (!joining) {
            backupBox.isEnabled = false
            backupBox.isChecked = false
            backupBox.text = "Backup node (not applicable as host)"
            backupHint?.text = "The host is the primary. Backups " +
                "only apply to joiners."
        } else {
            backupBox.isEnabled = true
            backupBox.text = "Become a backup node"
            backupHint?.text = "Backups take over automatically " +
                "if the host disconnects."
        }
    }

    private fun onConnectClicked() {
        var name = nameIn.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a name",
                Toast.LENGTH_SHORT).show()
            return
        }
        var groupName = groupNameIn.text.toString().trim()
        val passphrase = passphraseIn.text.toString()
        if (groupName.isEmpty()) groupName = "MeshChat"

        val isHost = hostRadio.isChecked
        var onion = ""

        if (!isHost) {
            val raw = onionIn.text.toString()
            val o = normalizeOnion(raw)
            if (o == null) { showOnionFormatError(); return }
            onion = o
            onionIn.setText(onion)
        }

        registry.addGroup(groupName)
        registry.setActiveGroup(groupName)

        val gs = SessionStore(this, groupName)
        gs.setGroupName(groupName)
        gs.setGroupPassphrase(passphrase)
        gs.setGroupKey("")
        gs.setGroupEstablished(true)
        gs.setAutoConnect(true)
        gs.save(name, if (isHost) "" else onion, isHost, backupBox.isChecked)

        val svc = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc)
        else startService(svc)

        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra("name", name)
            putExtra("peer", if (isHost) "" else onion)
            putExtra("isHost", isHost)
            putExtra("isBackup", backupBox.isChecked)
        })
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            if (pm.isIgnoringBatteryOptimizations(packageName)) return

            AlertDialog.Builder(this)
                .setTitle("Allow MeshChat to run in the background")
                .setMessage("MeshChat is a foreground service, so it " +
                    "keeps running while your screen is off.\n\n" +
                    "For reliable message delivery, set " +
                    "MeshChat to 'Don't optimize' or 'Unrestricted' " +
                    "in your phone's battery settings.\n\n" +
                    "You can also find this later by long-pressing " +
                    "the MeshChat title on this screen.")
                .setPositiveButton("Open battery settings") { _: DialogInterface?, _: Int ->
                    openBatterySettings()
                }
                .setNegativeButton("Not now", null)
                .show()
        } catch (_: Exception) {}
    }

    companion object {
        private const val REQ_CAM = 1002
        private const val QR_PREFIX = "MESHCHAT2"
        private const val NEW_GROUP_LABEL = "+ Create new group…"

        private val KEEP_ALIVE_TEXT = """
            MeshChat runs as a foreground service, so it survives screen-off, memory pressure, swipe from Recents, and reboot.

            It cannot survive a Force stop from Settings.

            For reliable operation on Samsung, Xiaomi, OnePlus, Huawei and similar devices, also allow MeshChat to run in the background in your phone's battery settings.
        """.trimIndent()
    }
}