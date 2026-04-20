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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * ScrcpyBridge
 *
 * Manages launching and communicating with the scrcpy-server process.
 *
 * How scrcpy works on Android (car unit as RECEIVER):
 *   - The car unit acts as an ADB host (requires root or Android Debug Bridge enabled)
 *   - scrcpy-server.jar is pushed to the source device via ADB
 *   - The server streams H.264 video + audio over a local socket
 *   - We decode and render to a SurfaceView
 *
 * For non-rooted car units: we use `adb connect` over TCP/IP or USB.
 * The user installs "Wireless ADB" on their phone, or enables USB debugging.
 */
public class ScrcpyBridge {

    private static final String TAG = "ScrcpyBridge";

    // scrcpy-server version bundled in assets/
    private static final String SERVER_FILENAME = "scrcpy-server-v3.1";
    // Device path where server is pushed
    private static final String DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";
    // scrcpy control socket port
    private static final int SCRCPY_PORT = 27183;

    public interface Callback {
        void onConnected(String deviceSerial);
        void onDisconnected(String reason);
        void onError(String error);
        void onFpsUpdate(int fps);
    }

    private final Context context;
    private Process adbProcess;
    private Process scrcpyProcess;
    private boolean running = false;
    private Callback callback;
    private Surface outputSurface;

    public ScrcpyBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
    }

    /**
     * Connect to an Android device over WiFi using ADB TCP.
     * Device must have wireless debugging or USB debugging + tcpip mode enabled.
     *
     * @param ipAddress  IP of the source phone (e.g. "192.168.1.42")
     */
    public void connectWifi(String ipAddress) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String target = ipAddress + ":5555";
                log("Connecting ADB to " + target);

                // 1. adb connect
                String result = runAdb("connect", target);
                log("ADB connect result: " + result);

                if (!result.contains("connected")) {
                    notifyError("ADB connect failed: " + result);
                    return;
                }

                // 2. Push scrcpy server to device
                pushServer(target);

                // 3. Start scrcpy server on device
                startMirror(target);

            } catch (Exception e) {
                notifyError("WiFi connection error: " + e.getMessage());
                Log.e(TAG, "WiFi connect error", e);
            }
        });
    }

    /**
     * Connect to a USB-attached Android device.
     * Device must have USB debugging enabled.
     */
    public void connectUsb() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                log("Looking for USB-attached device...");

                // Get first attached device
                String devices = runAdb("devices");
                String serial = parseFirstDevice(devices);

                if (serial == null) {
                    notifyError("No USB device found. Enable USB debugging on your phone.");
                    return;
                }

                log("Found device: " + serial);

                pushServer(serial);
                startMirror(serial);

            } catch (Exception e) {
                notifyError("USB connection error: " + e.getMessage());
                Log.e(TAG, "USB connect error", e);
            }
        });
    }

    /**
     * Push the scrcpy server JAR to the target device.
     */
    private void pushServer(String target) throws IOException, InterruptedException {
        File serverFile = extractServerFromAssets();
        log("Pushing server to " + target);

        String result;
        if (target.contains(":")) {
            // WiFi — use adb -s host:port push
            result = runAdb("-s", target, "push", serverFile.getAbsolutePath(), DEVICE_SERVER_PATH);
        } else {
            result = runAdb("-s", target, "push", serverFile.getAbsolutePath(), DEVICE_SERVER_PATH);
        }
        log("Push result: " + result);
    }

    /**
     * Start the scrcpy server on device and begin video stream.
     * This uses scrcpy's native protocol over adb forward + local socket.
     */
    private void startMirror(String target) throws IOException {
        running = true;

        // Forward scrcpy socket port
        runAdb("-s", target, "forward", "tcp:" + SCRCPY_PORT, "localabstract:scrcpy");

        // Launch server on device via adb shell
        List<String> shellCmd = new ArrayList<>();
        shellCmd.add("adb");
        if (target != null) { shellCmd.add("-s"); shellCmd.add(target); }
        shellCmd.addAll(List.of(
            "shell",
            "CLASSPATH=" + DEVICE_SERVER_PATH,
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            "3.1",              // version
            "video_bit_rate=4000000",
            "max_fps=60",
            "video_codec=h264",
            "audio=false",      // disable audio for simplicity
            "control=true",
            "tunnel_forward=true",
            "send_device_meta=false",
            "send_dummy_byte=true",
            "raw_video_stream=true"
        ));

        ProcessBuilder pb = new ProcessBuilder(shellCmd);
        pb.redirectErrorStream(true);
        scrcpyProcess = pb.start();

        if (callback != null) {
            callback.onConnected(target);
        }

        // Read server output for logging / error detection
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(scrcpyProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log("SERVER: " + line);
                    if (line.contains("ERROR") || line.contains("error")) {
                        notifyError(line);
                    }
                }
            } catch (IOException e) {
                log("Server output read ended: " + e.getMessage());
            }

            // Process exited
            if (callback != null && running) {
                callback.onDisconnected("Server process ended");
            }
            running = false;
        });

        // Start video decoder thread
        // NOTE: Full H.264 decoding via MediaCodec is implemented in ScrcpyDecoder
        if (outputSurface != null) {
            ScrcpyDecoder decoder = new ScrcpyDecoder("127.0.0.1", SCRCPY_PORT, outputSurface);
            decoder.setFpsCallback(fps -> {
                if (callback != null) callback.onFpsUpdate(fps);
            });
            Executors.newSingleThreadExecutor().execute(decoder::start);
        }
    }

    /**
     * Disconnect and clean up.
     */
    public void disconnect() {
        running = false;
        if (scrcpyProcess != null) {
            scrcpyProcess.destroy();
            scrcpyProcess = null;
        }
        if (adbProcess != null) {
            adbProcess.destroy();
            adbProcess = null;
        }
    }

    /**
     * Run an ADB command and return stdout as a string.
     */
    private String runAdb(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        for (String a : args) cmd.add(a);

        Process p = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        return sb.toString().trim();
    }

    /**
     * Extract scrcpy-server.jar from app assets to internal storage.
     */
    private File extractServerFromAssets() throws IOException {
        File outFile = new File(context.getFilesDir(), SERVER_FILENAME);
        if (outFile.exists()) return outFile; // already extracted

        try (InputStream in = context.getAssets().open(SERVER_FILENAME);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }
        return outFile;
    }

    /**
     * Parse the first online device serial from `adb devices` output.
     */
    private String parseFirstDevice(String devicesOutput) {
        String[] lines = devicesOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List") || line.startsWith("*")) continue;
            if (line.contains("\tdevice")) {
                return line.split("\t")[0].trim();
            }
        }
        return null;
    }

    /**
     * List all connected ADB devices. Returns array of "serial (state)" strings.
     */
    public void listDevices(DeviceListCallback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String output = runAdb("devices");
                List<String> devices = new ArrayList<>();
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("List") && line.contains("\t")) {
                        devices.add(line.replace("\t", "  "));
                    }
                }
                cb.onResult(devices);
            } catch (Exception e) {
                cb.onResult(new ArrayList<>());
            }
        });
    }

    public interface DeviceListCallback {
        void onResult(List<String> devices);
    }

    private void notifyError(String msg) {
        if (callback != null) {
            callback.onError(msg);
        }
        log("ERROR: " + msg);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
