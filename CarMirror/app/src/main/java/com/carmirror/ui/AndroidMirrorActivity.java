package com.carmirror.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carmirror.R;
import com.carmirror.util.ScrcpyBridge;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class AndroidMirrorActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private ScrcpyBridge bridge;
    private SurfaceView surfaceMirror;
    private LinearLayout layoutLoading;
    private LinearLayout layoutHud;
    private LinearLayout layoutConnectPanel;
    private TextView tvStatus;
    private TextView tvSubStatus;
    private TextView tvHudFps;
    private TextView tvDeviceList;
    private EditText etDeviceIp;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean surfaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive();
        setContentView(R.layout.activity_android_mirror);

        surfaceMirror     = findViewById(R.id.surfaceMirror);
        layoutLoading     = findViewById(R.id.layoutLoading);
        layoutHud         = findViewById(R.id.layoutHud);
        layoutConnectPanel = findViewById(R.id.layoutConnectPanel);
        tvStatus          = findViewById(R.id.tvStatus);
        tvSubStatus       = findViewById(R.id.tvSubStatus);
        tvHudFps          = findViewById(R.id.tvHudFps);
        tvDeviceList      = findViewById(R.id.tvDeviceList);
        etDeviceIp        = findViewById(R.id.etDeviceIp);

        MaterialButton btnConnectWifi = findViewById(R.id.btnConnectWifi);
        MaterialButton btnConnectUsb  = findViewById(R.id.btnConnectUsb);
        TextView btnDisconnect        = findViewById(R.id.btnDisconnect);

        surfaceMirror.getHolder().addCallback(this);

        bridge = new ScrcpyBridge(this);
        bridge.setCallback(new ScrcpyBridge.Callback() {
            @Override
            public void onConnected(String deviceSerial) {
                mainHandler.post(() -> showMirroringState(deviceSerial));
            }

            @Override
            public void onDisconnected(String reason) {
                mainHandler.post(() -> {
                    showConnectPanel("Disconnected: " + reason);
                    Toast.makeText(AndroidMirrorActivity.this,
                        "Mirror ended: " + reason, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    showConnectPanel("Error — try again");
                    tvSubStatus.setText(error);
                    Toast.makeText(AndroidMirrorActivity.this,
                        error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFpsUpdate(int fps) {
                mainHandler.post(() -> tvHudFps.setText(fps + " fps"));
            }
        });

        btnConnectWifi.setOnClickListener(v -> {
            String ip = etDeviceIp.getText() != null
                ? etDeviceIp.getText().toString().trim() : "";
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter device IP address", Toast.LENGTH_SHORT).show();
                return;
            }
            showLoadingState("Connecting via WiFi…", "Ensure USB debugging is enabled on " + ip);
            if (surfaceReady) {
                bridge.setOutputSurface(surfaceMirror.getHolder().getSurface());
            }
            bridge.connectWifi(ip);
        });

        btnConnectUsb.setOnClickListener(v -> {
            showLoadingState("Connecting via USB…", "Make sure USB debugging is enabled on your phone");
            if (surfaceReady) {
                bridge.setOutputSurface(surfaceMirror.getHolder().getSurface());
            }
            bridge.connectUsb();
        });

        btnDisconnect.setOnClickListener(v -> {
            bridge.disconnect();
            showConnectPanel("Disconnected");
        });

        // Auto-scan for devices on the local network
        scanForDevices();
    }

    private void scanForDevices() {
        bridge.listDevices(devices -> mainHandler.post(() -> {
            if (devices.isEmpty()) {
                tvDeviceList.setText("No ADB devices found  —  connect via USB or enter IP above");
            } else {
                StringBuilder sb = new StringBuilder("Found: ");
                for (int i = 0; i < devices.size(); i++) {
                    sb.append(devices.get(i));
                    if (i < devices.size() - 1) sb.append(",  ");
                }
                tvDeviceList.setText(sb.toString());

                // Auto-fill first device IP if it looks like a TCP device
                for (String d : devices) {
                    if (d.contains(":5555")) {
                        String ip = d.split(":")[0].trim();
                        etDeviceIp.setText(ip);
                        break;
                    }
                }
            }
        }));
    }

    private void showLoadingState(String title, String sub) {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutConnectPanel.setVisibility(View.GONE);
        layoutHud.setVisibility(View.GONE);
        surfaceMirror.setVisibility(View.GONE);
        tvStatus.setText(title);
        tvSubStatus.setText(sub);
    }

    private void showMirroringState(String serial) {
        layoutLoading.setVisibility(View.GONE);
        layoutConnectPanel.setVisibility(View.GONE);
        layoutHud.setVisibility(View.VISIBLE);
        surfaceMirror.setVisibility(View.VISIBLE);

        TextView tvHudTitle = findViewById(R.id.tvHudTitle);
        tvHudTitle.setText("● Android — " + serial);
    }

    private void showConnectPanel(String statusMsg) {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutConnectPanel.setVisibility(View.VISIBLE);
        layoutHud.setVisibility(View.GONE);
        surfaceMirror.setVisibility(View.GONE);
        tvStatus.setText(statusMsg);
        tvSubStatus.setText("Enter IP or plug in USB cable");
        scanForDevices();
    }

    // ─── SurfaceHolder.Callback ─────────────────────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        bridge.setOutputSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bridge.disconnect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep mirror alive — car unit may dim but not destroy
    }

    private void setImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }
}
