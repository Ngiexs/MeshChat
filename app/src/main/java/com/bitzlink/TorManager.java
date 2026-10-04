package com.bitzlink;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TorManager {

    private static final String TRACE = "MeshTrace";
    public static final int DEFAULT_SOCKS_PORT = 9050;

    public interface Listener {
        void onTorReady(int socksPort, String onionAddress);
        void onError(String err);
        // Optional: bootstrap progress. Default no-op via wrapper below.
        void onProgress(int percent);
    }

    // Convenience base class so callers can implement just the two
    // methods they care about.
    public static abstract class SimpleListener implements Listener {
        public void onProgress(int percent) { }
    }

    private static volatile TorManager sInstance;

    public static TorManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (TorManager.class) {
                if (sInstance == null) {
                    sInstance = new TorManager(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context ctx;
    private final Object lock = new Object();

    private volatile boolean running         = false;
    private volatile boolean runningHostMode = false;
    private volatile boolean ready           = false;
    private volatile Process torProcess;
    private volatile String  onionAddress;
    private volatile int     socksPort       = 0;
    private volatile String  currentGroup    = "";

    private final List<Listener> pendingListeners = new ArrayList<Listener>();
    private final AtomicLong generation = new AtomicLong(0);

    private TorManager(Context ctx) {
        this.ctx = ctx;
    }

    public int getSocksPort() {
        int p = socksPort;
        return p > 0 ? p : DEFAULT_SOCKS_PORT;
    }

    public String getCurrentGroup() {
        return currentGroup == null ? "" : currentGroup;
    }

    public void setActiveGroup(String groupName) {
        this.currentGroup = GroupRegistry.sanitize(groupName);
    }

    private File getHsDir(String safeGroup) {
        File filesDir = ctx.getFilesDir();
        if (safeGroup == null || safeGroup.length() == 0) {
            return new File(filesDir, "tor_hs");
        }
        return new File(filesDir, "tor_hs" + File.separator + safeGroup);
    }

    private int pickFreePort() {
        try {
            ServerSocket s = new ServerSocket(0);
            int port = s.getLocalPort();
            s.close();
            return port;
        } catch (Exception e) {
            Log.w(TRACE, "TorManager: pickFreePort failed, using default");
            return DEFAULT_SOCKS_PORT;
        }
    }

    public void start(boolean publishHiddenService, Listener listener) {
        start("", publishHiddenService, listener);
    }

    public void start(String groupName, boolean publishHiddenService,
                      Listener listener) {
        boolean notifyNow = false;
        int portToSend = 0;
        String onionToSend = null;

        String safeGroup = GroupRegistry.sanitize(groupName);

        synchronized (lock) {
            boolean sameMode  = (runningHostMode == publishHiddenService);
            boolean sameGroup = safeGroup.equals(
                    currentGroup == null ? "" : currentGroup);

            if (running && sameMode && sameGroup) {
                if (ready) {
                    notifyNow = true;
                    portToSend = getSocksPort();
                    onionToSend = onionAddress;
                } else if (listener != null) {
                    pendingListeners.add(listener);
                }
            } else {
                if (running) {
                    Log.i(TRACE, "TorManager: switching group '"
                            + currentGroup + "' -> '" + safeGroup
                            + "' (host=" + publishHiddenService + ")");
                    stopLocked();
                }
                final long myGen = generation.incrementAndGet();
                running         = true;
                runningHostMode = publishHiddenService;
                currentGroup    = safeGroup;
                ready           = false;
                onionAddress    = null;
                if (listener != null) pendingListeners.add(listener);

                final String fGroup = safeGroup;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        runTor(fGroup, publishHiddenService, myGen);
                    }
                }, "tor-manager");
                t.setDaemon(true);
                t.start();
            }
        }

        if (notifyNow && listener != null) {
            try { listener.onTorReady(portToSend, onionToSend); }
            catch (Exception ignored) { }
        }
    }

    public void stop() {
        synchronized (lock) {
            generation.incrementAndGet();
            stopLocked();
        }
    }

    public boolean isRunning()  { return running; }
    public boolean isReady()    { return ready; }
    public boolean isHostMode() { return runningHostMode; }

    public String getOnionAddress() {
        String cached = onionAddress;
        if (cached != null && cached.length() > 0) return cached;

        try {
            File hsDir = getHsDir(currentGroup);
            File h  = new File(hsDir, "hostname");
            if (!h.exists()) return null;
            BufferedReader r = new BufferedReader(new FileReader(h));
            String line = r.readLine();
            r.close();
            if (line != null) {
                line = line.trim();
                onionAddress = line;
                return line;
            }
        } catch (Exception e) {
            Log.w(TRACE, "TorManager: read hostname failed: " + e.getMessage());
        }
        return null;
    }

    public String exportHsBundle() {
        return exportHsBundle(currentGroup);
    }

    public String exportHsBundle(String groupName) {
        try {
            File hsDir = getHsDir(GroupRegistry.sanitize(groupName));
            File hostnameFile = new File(hsDir, "hostname");
            File secretFile   = new File(hsDir, "hs_ed25519_secret_key");
            File publicFile   = new File(hsDir, "hs_ed25519_public_key");

            if (!hostnameFile.exists() || !secretFile.exists()
                    || !publicFile.exists()) {
                return null;
            }

            String onion = readTextFile(hostnameFile).trim();
            String secret = Base64.encodeToString(
                    readBytesFile(secretFile),
                    Base64.NO_WRAP | Base64.URL_SAFE);
            String pub = Base64.encodeToString(
                    readBytesFile(publicFile),
                    Base64.NO_WRAP | Base64.URL_SAFE);

            if (onion.length() == 0) return null;
            return onion + "|" + secret + "|" + pub;
        } catch (Exception e) {
            Log.w(TRACE, "exportHsBundle failed: " + e.getMessage());
            return null;
        }
    }

    public boolean importHsBundle(String onion,
                                  String secretB64,
                                  String publicB64) {
        return importHsBundle(currentGroup, onion, secretB64, publicB64);
    }

    public boolean importHsBundle(String groupName,
                                  String onion,
                                  String secretB64,
                                  String publicB64) {
        if (onion == null || onion.length() == 0) return false;
        if (secretB64 == null || secretB64.length() == 0) return false;
        if (publicB64 == null || publicB64.length() == 0) return false;
        try {
            String safe = GroupRegistry.sanitize(groupName);
            File hsDir = getHsDir(safe);
            if (!hsDir.exists() && !hsDir.mkdirs()) return false;

            File hostnameFile = new File(hsDir, "hostname");
            File secretFile   = new File(hsDir, "hs_ed25519_secret_key");
            File publicFile   = new File(hsDir, "hs_ed25519_public_key");

            hostnameFile.delete();
            secretFile.delete();
            publicFile.delete();

            writeTextFile(hostnameFile, onion);
            writeBytesFile(secretFile,
                    Base64.decode(secretB64,
                            Base64.NO_WRAP | Base64.URL_SAFE));
            writeBytesFile(publicFile,
                    Base64.decode(publicB64,
                            Base64.NO_WRAP | Base64.URL_SAFE));

            secretFile.setReadable(true, true);
            secretFile.setWritable(true, true);
            secretFile.setExecutable(false, false);
            publicFile.setReadable(true, true);
            publicFile.setWritable(true, true);
            hostnameFile.setReadable(true, true);
            hostnameFile.setWritable(true, true);

            if (safe.equals(currentGroup == null ? "" : currentGroup)) {
                onionAddress = onion;
            }
            Log.i(TRACE, "TorManager: imported HS bundle for group '"
                    + safe + "' " + shortOnion(onion));
            return true;
        } catch (Exception e) {
            Log.w(TRACE, "importHsBundle failed: " + e.getMessage());
            return false;
        }
    }

    private String shortOnion(String o) {
        if (o == null) return "?";
        if (o.length() <= 16) return o;
        return o.substring(0, 8) + "..." + o.substring(o.length() - 4);
    }

    private String readTextFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            try { r.close(); } catch (Exception ignored) { }
        }
    }

    private byte[] readBytesFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return buf;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private void writeTextFile(File f, String s) throws Exception {
        FileWriter w = new FileWriter(f);
        try { w.write(s); }
        finally { try { w.close(); } catch (Exception ignored) { } }
    }

    private void writeBytesFile(File f, byte[] b) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(b); }
        finally { try { out.close(); } catch (Exception ignored) { } }
    }

    private void stopLocked() {
        running = false;
        ready   = false;

        Process p = torProcess;
        torProcess = null;

        if (p != null) {
            Log.i(TRACE, "TorManager: stopping tor process");
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        socksPort = 0;
        pendingListeners.clear();
    }

    private void runTor(final String safeGroup,
                        final boolean publishHiddenService,
                        final long myGen) {
        try {
            final File filesDir   = ctx.getFilesDir();
            final File torDataDir = new File(filesDir, "tor_data");
            final File hsDir      = getHsDir(safeGroup);

            if (!torDataDir.exists() && !torDataDir.mkdirs()) {
                fail("could not create tor_data", myGen);
                return;
            }

            if (publishHiddenService) {
                if (!hsDir.exists() && !hsDir.mkdirs()) {
                    fail("could not create tor_hs", myGen);
                    return;
                }
            }

            int myPort = pickFreePort();
            synchronized (lock) {
                if (generation.get() != myGen || !running) return;
                socksPort = myPort;
            }
            Log.i(TRACE, "TorManager: picked SOCKS port " + myPort
                    + " for group '" + safeGroup + "'");

            File torrcFile = new File(filesDir, "torrc");
            FileWriter w = new FileWriter(torrcFile);
            w.write("DataDirectory " + torDataDir.getAbsolutePath() + "\n");
            w.write("SocksPort 127.0.0.1:" + myPort + "\n");
            w.write("Log notice stdout\n");
            w.write("RunAsDaemon 0\n");
            w.write("ClientUseIPv4 1\n");
            w.write("ClientUseIPv6 0\n");
            if (publishHiddenService) {
                w.write("HiddenServiceDir "  + hsDir.getAbsolutePath() + "\n");
                w.write("HiddenServicePort 8888 127.0.0.1:8888\n");
            }
            w.close();

            String torBinaryPath = ctx.getApplicationInfo().nativeLibraryDir
                    + "/libtor.so";
            File torBin = new File(torBinaryPath);
            Log.i(TRACE, "TorManager: tor binary at " + torBinaryPath);

            if (!torBin.exists()) {
                fail("libtor.so not found", myGen);
                return;
            }
            if (!torBin.canExecute()) {
                torBin.setExecutable(true, false);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    torBinaryPath, "-f", torrcFile.getAbsolutePath());
            pb.directory(filesDir);
            pb.redirectErrorStream(true);

            Process proc = pb.start();

            synchronized (lock) {
                if (generation.get() != myGen || !running) {
                    proc.destroyForcibly();
                    return;
                }
                torProcess = proc;
            }

            Log.i(TRACE, "TorManager: tor process started pid-alive="
                    + proc.isAlive());

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line;
            boolean announced = false;
            int lastPct = -1;
            while ((line = reader.readLine()) != null) {
                Log.i(TRACE, "tor: " + line);

                if (line.contains("Bootstrapped ")) {
                    int idx = line.indexOf("Bootstrapped ");
                    if (idx >= 0) {
                        int end = line.indexOf('%', idx);
                        if (end > idx) {
                            try {
                                int pct = Integer.parseInt(
                                        line.substring(idx + 13, end).trim());
                                if (pct != lastPct && !announced) {
                                    lastPct = pct;
                                    notifyProgress(pct);
                                }
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                }

                if (!announced && line.contains("Bootstrapped 100%")) {
                    announced = true;
                    Log.i(TRACE, "TorManager: bootstrapped for group '"
                            + safeGroup + "'");

                    String onion = null;
                    if (publishHiddenService) {
                        File hostname = new File(hsDir, "hostname");
                        for (int i = 0; i < 15 && !hostname.exists(); i++) {
                            try { Thread.sleep(1000); }
                            catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        if (hostname.exists()) {
                            BufferedReader hr = new BufferedReader(
                                    new FileReader(hostname));
                            onion = hr.readLine();
                            hr.close();
                            if (onion != null) onion = onion.trim();
                        }
                    }
                    notifyReady(onion, myGen);
                }
            }
            Log.i(TRACE, "TorManager: tor process exited");

        } catch (Exception e) {
            if (generation.get() == myGen) {
                Log.e(TRACE, "TorManager failed: " + e.getMessage(), e);
                fail(e.getMessage(), myGen);
            }
        } finally {
            synchronized (lock) {
                if (generation.get() == myGen) {
                    running    = false;
                    ready      = false;
                    torProcess = null;
                }
            }
        }
    }

    private void notifyProgress(int pct) {
        List<Listener> toNotify;
        synchronized (lock) {
            if (!running) return;
            toNotify = new ArrayList<Listener>(pendingListeners);
        }
        for (Listener l : toNotify) {
            try { l.onProgress(pct); }
            catch (Exception e) { }
        }
    }

    private void notifyReady(String onion, long myGen) {
        List<Listener> toNotify;
        int portAtReady;
        synchronized (lock) {
            if (generation.get() != myGen) return;
            if (!running) return;
            onionAddress = onion;
            ready = true;
            portAtReady = getSocksPort();
            toNotify = new ArrayList<Listener>(pendingListeners);
            pendingListeners.clear();
        }
        for (Listener l : toNotify) {
            try { l.onTorReady(portAtReady, onion); }
            catch (Exception e) {
                Log.w(TRACE, "TorManager: listener threw: " + e.getMessage());
            }
        }
    }

    private void fail(String err, long myGen) {
        List<Listener> toNotify;
        synchronized (lock) {
            if (generation.get() != myGen) return;
            running  = false;
            ready    = false;
            toNotify = new ArrayList<Listener>(pendingListeners);
            pendingListeners.clear();
        }
        for (Listener l : toNotify) {
            try { l.onError(err); }
            catch (Exception ignored) { }
        }
    }
}