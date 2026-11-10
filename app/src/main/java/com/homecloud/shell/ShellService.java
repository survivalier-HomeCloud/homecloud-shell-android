package com.homecloud.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Foreground service: keeps the process (and so the open shell connections) alive while the app is in the background,
 * and shows one notification with a "close all" action. The sessions themselves live in SessionManager.
 */
public class ShellService extends Service implements SessionManager.Listener {
    static final String ACTION_CLOSE_ALL = "com.homecloud.shell.CLOSE_ALL";
    private static final String CHANNEL = "sessions";
    private static final int NOTIF_ID = 1;
    /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34), as a literal so older compile targets build too. */
    private static final int TYPE_SPECIAL_USE = 0x40000000;

    private PowerManager.WakeLock wakeLock;

    /** Starts the service when sessions exist, stops it when none is left. */
    static void sync(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, ShellService.class);
        if (SessionManager.get().count() > 0) app.startForegroundService(i);
        else app.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Sessions ouvertes", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Garde les sessions shell connectées quand l'application est en arrière-plan");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        SessionManager.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLOSE_ALL.equals(intent.getAction())) {
            startForegroundCompat();            // a service started with startForegroundService must always call it
            SessionManager.get().closeAll(this);
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (SessionManager.get().count() == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateWakeLock();
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    @Override
    public void onSessionsChanged() {
        List<Session> all = SessionManager.get().list();
        if (all.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        updateWakeLock();
    }

    private Notification buildNotification() {
        List<Session> all = SessionManager.get().list();
        int n = all.size();
        String text;
        Intent open = new Intent(this, TerminalActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (n == 0) {
            text = "Aucune session";
        } else {
            Session last = all.get(n - 1);
            open.putExtra(TerminalActivity.EXTRA_SESSION, last.id);
            text = n == 1 ? last.profile.user + "@" + last.profile.host : n + " sessions ouvertes";
        }
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, piFlags);
        Intent close = new Intent(this, ShellService.class).setAction(ACTION_CLOSE_ALL);
        PendingIntent closeAll = PendingIntent.getService(this, 1, close, piFlags);
        Notification.Action stop = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification), "Tout fermer", closeAll).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("HomeCloud Shell")
                .setContentText(text)
                .setContentIntent(content)
                .addAction(stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateWakeLock() {
        boolean want = new ProfileStore(this).wakeLock() && SessionManager.get().count() > 0;
        if (want && wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homecloudshell:sessions");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } else if (!want && wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        SessionManager.get().removeListener(this);
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
