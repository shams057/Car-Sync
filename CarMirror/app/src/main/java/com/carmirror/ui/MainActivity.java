package com.carmirror.ui;

import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.carmirror.R;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvIpAddress;
    private TextView tvWifiName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on — this is a car unit
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Full screen, hide system bars for automotive use
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvWifiName  = findViewById(R.id.tvWifiName);

        // Show network info
        loadNetworkInfo();

        // iOS mirror button
        findViewById(R.id.cardIOS).setOnClickListener(v -> {
            animateCardTap(v);
            startActivity(new Intent(this, iOSMirrorActivity.class));
        });

        // Android mirror button
        findViewById(R.id.cardAndroid).setOnClickListener(v -> {
            animateCardTap(v);
            startActivity(new Intent(this, AndroidMirrorActivity.class));
        });
    }

    private void animateCardTap(View v) {
        v.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(80)
            .withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            ).start();
    }

    private void loadNetworkInfo() {
        new Thread(() -> {
            String ip = getLocalIpAddress();
            String ssid = getWifiSsid();

            runOnUiThread(() -> {
                tvIpAddress.setText("IP  " + (ip != null ? ip : "not connected"));
                if (ssid != null && !ssid.isEmpty()) {
                    tvWifiName.setText("● " + ssid);
                    tvWifiName.setTextColor(getColor(R.color.accent_green));
                } else {
                    tvWifiName.setText("○ no wifi");
                    tvWifiName.setTextColor(getColor(R.color.text_muted));
                }
            });
        }).start();
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface iface : interfaces) {
                List<InetAddress> addrs = Collections.list(iface.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String hostAddr = addr.getHostAddress();
                        boolean isIPv4 = hostAddr != null && !hostAddr.contains(":");
                        if (isIPv4) return hostAddr;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getWifiSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) return ssid.replace("\"", "");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadNetworkInfo();
    }
}
