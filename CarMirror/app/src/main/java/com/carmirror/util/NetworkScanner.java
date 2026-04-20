package com.carmirror.util;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetworkScanner
 *
 * Rapidly scans the local /24 subnet for devices with ADB port 5555 open.
 * Uses a thread pool for parallel scanning (254 hosts, ~2s total).
 */
public class NetworkScanner {

    private static final String TAG = "NetworkScanner";
    private static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 300;  // per host
    private static final int THREAD_COUNT = 50;

    public interface ScanCallback {
        void onDeviceFound(String ip);
        void onScanComplete(List<String> allFound);
        void onProgress(int scanned, int total);
    }

    private final Context context;
    private ExecutorService executor;
    private volatile boolean scanning = false;

    public NetworkScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start scanning the local subnet for ADB devices (port 5555 open).
     */
    public void scan(ScanCallback callback) {
        if (scanning) return;
        scanning = true;

        String subnetBase = getSubnetBase();
        if (subnetBase == null) {
            Log.w(TAG, "Could not determine subnet — not on WiFi?");
            callback.onScanComplete(new ArrayList<>());
            return;
        }

        Log.d(TAG, "Scanning " + subnetBase + ".1-254 for ADB port " + ADB_PORT);

        executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>();
        List<String> found = new ArrayList<>();
        AtomicInteger scanned = new AtomicInteger(0);
        int total = 254;

        for (int i = 1; i <= 254; i++) {
            final String ip = subnetBase + "." + i;
            futures.add(executor.submit(() -> {
                if (!scanning) return;
                if (isAdbPortOpen(ip)) {
                    synchronized (found) { found.add(ip); }
                    callback.onDeviceFound(ip);
                    Log.d(TAG, "ADB device found: " + ip);
                }
                int done = scanned.incrementAndGet();
                if (done % 20 == 0) callback.onProgress(done, total);
            }));
        }

        // Wait for all and report done
        Executors.newSingleThreadExecutor().execute(() -> {
            for (Future<?> f : futures) {
                try { f.get(5, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
            scanning = false;
            executor.shutdown();
            callback.onScanComplete(found);
            Log.d(TAG, "Scan complete. Found " + found.size() + " device(s).");
        });
    }

    public void stopScan() {
        scanning = false;
        if (executor != null) executor.shutdownNow();
    }

    private boolean isAdbPortOpen(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns "192.168.1" style base from current WiFi connection,
     * or null if not connected.
     */
    private String getSubnetBase() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;

            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;

            int ipInt = info.getIpAddress();
            // WifiInfo.getIpAddress() is little-endian
            String ip = String.format("%d.%d.%d.%d",
                (ipInt & 0xFF),
                (ipInt >> 8 & 0xFF),
                (ipInt >> 16 & 0xFF),
                (ipInt >> 24 & 0xFF));

            // Return /24 base (first 3 octets)
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2];
            }
        } catch (Exception e) {
            Log.e(TAG, "getSubnetBase error", e);
        }
        return null;
    }
}
