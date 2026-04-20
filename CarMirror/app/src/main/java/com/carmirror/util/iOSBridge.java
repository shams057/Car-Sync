package com.carmirror.util;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;

/**
 * iOSBridge
 *
 * Handles mirroring an iPhone/iPad to this Android car unit.
 *
 * scrcpy v3.x added iOS support via the "ios" option.
 * It uses libimobiledevice under the hood to stream the screen
 * over a USB connection (Lightning or USB-C).
 *
 * Prerequisites on the car unit (Android):
 *   - scrcpy binary compiled for Android ARM with iOS support
 *     OR run scrcpy on a companion Linux process
 *   - The scrcpy-server binary is bundled in assets as "scrcpy-ios"
 *   - iPhone must "Trust This Computer" when connected
 *
 * Alternative approach (more compatible):
 *   Uses `scrcpy --video-source=camera` with the iOS screen mirroring
 *   trick, or runs scrcpy in server mode with iOS transport.
 *
 * For car infotainment Android units that support termux/chroot:
 *   This bridge launches a native scrcpy process from the internal Linux env.
 */
public class iOSBridge {

    private static final String TAG = "iOSBridge";
    private static final int SCRCPY_IOS_PORT = 27184;

    public interface Callback {
        void onDeviceDetected(String deviceName);
        void onConnected();
        void onDisconnected(String reason);
        void onError(String error);
        void onWaitingForTrust();
        void onFps(int fps);
    }

    private final Context context;
    private Process scrcpyProcess;
    private ScrcpyDecoder decoder;
    private Surface outputSurface;
    private Callback callback;
    private volatile boolean running = false;

    public iOSBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback cb) { this.callback = cb; }
    public void setOutputSurface(Surface s) { this.outputSurface = s; }

    /**
     * Start iOS mirroring.
     * Assumes iPhone is connected via USB.
     *
     * scrcpy command:
     *   scrcpy --video-source=display --otg  (for newer scrcpy with iOS)
     *   or the bundled scrcpy-server in iOS mode
     */
    public void startMirror() {
        running = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Step 1: Check for connected iOS device
                log("Checking for iOS device...");
                notifyStatus("Detecting iOS device...");

                String deviceName = detectiOSDevice();
                if (deviceName == null) {
                    notifyError("No iOS device detected.\n" +
                        "Make sure your iPhone is connected via USB and you've tapped 'Trust'.");
                    return;
                }

                log("iOS device found: " + deviceName);
                if (callback != null) callback.onDeviceDetected(deviceName);

                // Step 2: Launch scrcpy with iOS transport
                launchScrcpyIos();

                // Step 3: Connect decoder to video socket
                Thread.sleep(800);
                if (outputSurface != null && running) {
                    decoder = new ScrcpyDecoder("127.0.0.1", SCRCPY_IOS_PORT, outputSurface);
                    decoder.setFpsCallback(fps -> {
                        if (callback != null) callback.onFps(fps);
                    });
                    Executors.newSingleThreadExecutor().execute(decoder::start);
                    if (callback != null) callback.onConnected();
                }

            } catch (Exception e) {
                if (running) {
                    notifyError("iOS mirror error: " + e.getMessage());
                    Log.e(TAG, "iOS mirror failed", e);
                }
            }
        });
    }

    /**
     * Detect connected iOS device using idevice_id (libimobiledevice).
     * Returns device name or null if not found.
     *
     * On systems where libimobiledevice is available (Termux, rooted Android):
     *   `idevice_id -l` lists paired device UDIDs
     *   `ideviceinfo -k DeviceName` gets the device name
     *
     * On stock Android car units without libimobiledevice:
     *   We detect via USB host API (UsbManager) — iPhone presents
     *   as Apple Mobile Device USB Driver when trusted.
     */
    private String detectiOSDevice() {
        // First try libimobiledevice path
        try {
            Process p = new ProcessBuilder("idevice_id", "-l")
                .redirectErrorStream(true)
                .start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String udid = br.readLine();
            p.waitFor();

            if (udid != null && !udid.isEmpty()) {
                // Get friendly name
                Process p2 = new ProcessBuilder("ideviceinfo", "-u", udid.trim(), "-k", "DeviceName")
                    .redirectErrorStream(true)
                    .start();
                BufferedReader br2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
                String name = br2.readLine();
                p2.waitFor();
                return (name != null && !name.isEmpty()) ? name.trim() : "iPhone (" + udid.trim() + ")";
            }
        } catch (Exception e) {
            log("libimobiledevice not available: " + e.getMessage());
        }

        // Fallback: try scrcpy's own iOS detection
        try {
            File scrcpyBin = extractScrcpyBinary();
            Process p = new ProcessBuilder(scrcpyBin.getAbsolutePath(), "--list-devices")
                .redirectErrorStream(true)
                .start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("iPhone") || line.contains("iPad") || line.contains("iOS")) {
                    return line.trim();
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log("scrcpy device list failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Launch scrcpy in iOS mode.
     *
     * scrcpy v3.1+ command for iOS:
     *   scrcpy --video-source=display \
     *          --video-codec=h264 \
     *          --video-bit-rate=4M \
     *          --max-fps=60 \
     *          --no-audio \
     *          --tunnel-host=127.0.0.1 \
     *          --tunnel-port=27184
     */
    private void launchScrcpyIos() throws IOException {
        File scrcpyBin = extractScrcpyBinary();

        ProcessBuilder pb = new ProcessBuilder(
            scrcpyBin.getAbsolutePath(),
            "--video-codec=h264",
            "--video-bit-rate=4M",
            "--max-fps=60",
            "--no-audio",
            "--tunnel-host=127.0.0.1",
            "--tunnel-port=" + SCRCPY_IOS_PORT
        );
        pb.redirectErrorStream(true);
        scrcpyProcess = pb.start();

        // Monitor process output
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(scrcpyProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log("scrcpy: " + line);
                    parseScrcpyOutput(line);
                }
            } catch (IOException e) {
                log("scrcpy output ended");
            }
            if (running && callback != null) {
                callback.onDisconnected("scrcpy process exited");
            }
        });
    }

    private void parseScrcpyOutput(String line) {
        if (line.contains("Trust")) {
            if (callback != null) callback.onWaitingForTrust();
        } else if (line.contains("Connected") || line.contains("connected")) {
            if (callback != null) callback.onConnected();
        } else if (line.contains("ERROR") || line.contains("error")) {
            if (running) notifyError(line);
        }
    }

    /**
     * Extract scrcpy binary from assets to executable location.
     * The binary must be compiled for the car unit's ABI (arm64-v8a typically).
     */
    private File extractScrcpyBinary() throws IOException {
        File bin = new File(context.getFilesDir(), "scrcpy");
        if (!bin.exists()) {
            try (InputStream in = context.getAssets().open("scrcpy");
                 FileOutputStream out = new FileOutputStream(bin)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }
            bin.setExecutable(true);
        }
        return bin;
    }

    public void stop() {
        running = false;
        if (decoder != null) { decoder.stop(); decoder = null; }
        if (scrcpyProcess != null) { scrcpyProcess.destroy(); scrcpyProcess = null; }
    }

    private void notifyError(String msg) {
        if (callback != null) callback.onError(msg);
    }

    private void notifyStatus(String msg) {
        // Used internally for progress updates
        log(msg);
    }

    private void log(String msg) { Log.d(TAG, msg); }
}
