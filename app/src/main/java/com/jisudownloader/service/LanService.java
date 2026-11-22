package com.jisudownloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.jisudownloader.net.LanHub;
import com.jisudownloader.ui.LanActivity;

public class LanService extends Service {

    public static final String CH_ID = "jisu_lan";
    public static final String ACTION_START = "com.jisudownloader.lan.START";
    public static final String ACTION_STOP = "com.jisudownloader.lan.STOP";
    private static final int NOTIF_ID = 1002;

    private static volatile LanHub hub;
    public static volatile String lastError = "";

    public static LanHub hub() {
        return hub;
    }

    public static boolean isOn() {
        return hub != null && hub.isRunning();
    }

    public static void start(Context c, int codeLen) {
        Intent i = new Intent(c, LanService.class).setAction(ACTION_START).putExtra("len", codeLen);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, LanService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotif());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "局域网互传", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif() {
        LanHub h = hub;
        String title = h == null ? "局域网互传" : "房间 " + h.code();
        String text = h == null ? "正在准备…" : h.url() + " · 口令 " + h.code();
        Intent open = new Intent(this, LanActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(h != null)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            if (hub != null) {
                hub.stop();
                hub = null;
            }
            lastError = "";
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int len = intent == null ? 4 : intent.getIntExtra("len", 4);
        if (len < 4) len = 4;
        if (len > 8) len = 8;
        if (hub != null) {
            hub.stop();
            hub = null;
        }
        try {
            LanHub h = new LanHub(this, len);
            h.start();
            hub = h;
            lastError = "";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif());
        } catch (Exception e) {
            lastError = e.getMessage() == null ? "开启失败" : e.getMessage();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (hub != null) {
            hub.stop();
            hub = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
