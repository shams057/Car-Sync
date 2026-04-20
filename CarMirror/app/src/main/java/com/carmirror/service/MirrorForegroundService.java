package com.carmirror.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.carmirror.R;
import com.carmirror.ui.MainActivity;

/**
 * MirrorForegroundService
 *
 * Keeps the mirror session alive in a foreground service so:
 *   - Android doesn't kill it when the car unit's launcher comes to focus
 *   - Screen stays on and streaming even if the app is backgrounded
 *   - Works across car infotainment auto-start scenarios
 */
public class MirrorForegroundService extends Service {

    private static final String CHANNEL_ID   = "carmirror_channel";
    private static final int    NOTIF_ID     = 1001;

    public static final String ACTION_START  = "com.carmirror.START";
    public static final String ACTION_STOP   = "com.carmirror.STOP";
    public static final String EXTRA_MODE    = "mirror_mode"; // "ios" or "android"

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // ACTION_START — promote to foreground
        String mode = intent.getStringExtra(EXTRA_MODE);
        startForeground(NOTIF_ID, buildNotification(mode));

        return START_STICKY;
    }

    private Notification buildNotification(String mode) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, MirrorForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String modeLabel = "ios".equals(mode) ? "iOS" : "Android";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarMirror — " + modeLabel + " mirroring")
            .setContentText("Screen mirror session is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}
