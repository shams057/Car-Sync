package com.carmirror.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.carmirror.R;
import com.carmirror.util.iOSBridge;
import com.google.android.material.button.MaterialButton;

public class iOSMirrorActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private iOSBridge bridge;
    private SurfaceView surfaceMirror;
    private LinearLayout layoutLoading;
    private LinearLayout layoutHud;
    private TextView tvStatus;
    private TextView tvSubStatus;
    private TextView tvConnectionStatus;
    private TextView tvStep1, tvStep2, tvStep3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean surfaceReady = false;

    // Step completion tracking
    private boolean step1Done = false;
    private boolean step2Done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive();
        setContentView(R.layout.activity_ios_mirror);

        surfaceMirror       = findViewById(R.id.surfaceMirror);
        layoutLoading       = findViewById(R.id.layoutLoading);
        layoutHud           = findViewById(R.id.layoutHud);
        tvStatus            = findViewById(R.id.tvStatus);
        tvSubStatus         = findViewById(R.id.tvSubStatus);
        tvConnectionStatus  = findViewById(R.id.tvConnectionStatus);
        tvStep1             = findViewById(R.id.tvStep1);
        tvStep2             = findViewById(R.id.tvStep2);
        tvStep3             = findViewById(R.id.tvStep3);

        MaterialButton btnStart = findViewById(R.id.btnStart);
        MaterialButton btnBack  = findViewById(R.id.btnBack);
        TextView btnStopMirror  = findViewById(R.id.btnStopMirror);

        surfaceMirror.getHolder().addCallback(this);

        bridge = new iOSBridge(this);
        bridge.setCallback(new iOSBridge.Callback() {
            @Override
            public void onDeviceDetected(String deviceName) {
                mainHandler.post(() -> {
                    step1Done = true;
                    markStepDone(tvStep1, "1  iPhone detected: " + deviceName);
                    tvConnectionStatus.setText("● device found — waiting for trust…");
                    tvConnectionStatus.setTextColor(getColor(R.color.accent_blue));
                });
            }

            @Override
            public void onWaitingForTrust() {
                mainHandler.post(() -> {
                    step2Done = false;
                    tvConnectionStatus.setText("⚠ tap 'Trust' on your iPhone");
                    tvConnectionStatus.setTextColor(getColor(R.color.accent_amber));
                    Toast.makeText(iOSMirrorActivity.this,
                        "Check your iPhone — tap 'Trust This Computer'",
                        Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onConnected() {
                mainHandler.post(() -> {
                    step2Done = true;
                    markStepDone(tvStep2, "2  iPhone trusted");
                    markStepDone(tvStep3, "3  Mirroring active");
                    showMirroringState();
                });
            }

            @Override
            public void onDisconnected(String reason) {
                mainHandler.post(() -> {
                    showSetupPanel();
                    tvConnectionStatus.setText("○ disconnected — " + reason);
                    tvConnectionStatus.setTextColor(getColor(R.color.text_muted));
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    showSetupPanel();
                    tvConnectionStatus.setText("✕ " + error);
                    tvConnectionStatus.setTextColor(getColor(R.color.status_error));
                    Toast.makeText(iOSMirrorActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFps(int fps) {
                // FPS not shown on iOS HUD to keep it minimal
            }
        });

        btnStart.setOnClickListener(v -> {
            tvConnectionStatus.setText("◌ scanning for iPhone…");
            tvConnectionStatus.setTextColor(getColor(R.color.text_muted));
            if (surfaceReady) {
                bridge.setOutputSurface(surfaceMirror.getHolder().getSurface());
            }
            bridge.startMirror();
        });

        btnBack.setOnClickListener(v -> finish());

        btnStopMirror.setOnClickListener(v -> {
            bridge.stop();
            showSetupPanel();
        });
    }

    private void markStepDone(TextView tv, String text) {
        tv.setText("✓  " + text.substring(text.indexOf(" ") + 1).trim());
        tv.setTextColor(getColor(R.color.accent_green));
    }

    private void showMirroringState() {
        layoutLoading.setVisibility(View.GONE);
        layoutHud.setVisibility(View.VISIBLE);
        surfaceMirror.setVisibility(View.VISIBLE);
    }

    private void showSetupPanel() {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutHud.setVisibility(View.GONE);
        surfaceMirror.setVisibility(View.GONE);

        // Reset step colors
        tvStep1.setTextColor(getColor(R.color.text_primary));
        tvStep2.setTextColor(getColor(R.color.text_primary));
        tvStep3.setTextColor(getColor(R.color.text_primary));
        tvStep1.setText("1  Connect iPhone to this unit via USB");
        tvStep2.setText("2  Trust this computer on your iPhone");
        tvStep3.setText("3  Tap START below — scrcpy will handle the rest");
        step1Done = false;
        step2Done = false;
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
        bridge.stop();
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
