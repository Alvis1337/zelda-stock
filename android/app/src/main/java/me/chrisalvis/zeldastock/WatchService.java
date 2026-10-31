package me.chrisalvis.zeldastock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Background watcher. Runs a full check every N minutes (an inexact alarm that
 * still fires in Doze), posts an alert for each newly in-stock result, and keeps
 * a quiet ongoing notification while it is on.
 */
public class WatchService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_CHECK = "check";
    public static final String ACTION_STOP = "stop";
    private static final int ONGOING_ID = 1;
    private static final long UPDATE_EVERY_MS = 6 * 60 * 60 * 1000L;

    /** Lets an open MainActivity refresh when a background run finishes. */
    public static volatile Runnable onRunFinished;

    private StockChecker checker;
    private PowerManager.WakeLock wake;

    public static void start(Context c) {
        Results.prefs(c).edit().putBoolean("watching", true).apply();
        c.startForegroundService(new Intent(c, WatchService.class).setAction(ACTION_START));
    }

    public static void stop(Context c) {
        Results.prefs(c).edit().putBoolean("watching", false).apply();
        c.startService(new Intent(c, WatchService.class).setAction(ACTION_STOP));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Results.createChannels(this);
        goForeground("Starting…");
        String action = intent == null ? ACTION_START : String.valueOf(intent.getAction());
        if (ACTION_STOP.equals(action) || !Results.prefs(this).getBoolean("watching", false)) {
            cancelAlarm();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        runCheck();
        return START_STICKY;
    }

    private int intervalMin() {
        return Math.max(5, Results.prefs(this).getInt("interval_min", 10));
    }

    private void goForeground(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, Results.CH_WATCH)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Watching for the Zelda 40th Anniversary console")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ONGOING_ID, n);
        }
    }

    private void updateOngoing(String text) {
        if (!Results.prefs(this).getBoolean("watching", false)) return;
        goForeground(text);
    }

    private void runCheck() {
        if (checker != null && checker.isRunning()) return;
        if (wake == null) {
            wake = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeldastock:check");
        }
        wake.acquire(4 * 60 * 1000L);
        scheduleNext();
        updateOngoing("Checking now…");

        SharedPreferences p = Results.prefs(this);
        if (System.currentTimeMillis() - p.getLong("last_update_check", 0) > UPDATE_EVERY_MS) {
            p.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            Updater.notifyIfAvailable(this);
        }

        String savedZip = p.getString("zip", "45701");
        if (p.getBoolean("use_location", true) && LocationHelper.hasPermission(this)) {
            LocationHelper.currentZip(this, new LocationHelper.Callback() {
                @Override public void onZip(String zip) {
                    p.edit().putString("zip", zip).apply();
                    doCheck(zip);
                }
                @Override public void onError(String message) { doCheck(savedZip); }
            });
        } else {
            doCheck(savedZip);
        }
    }

    private void doCheck(String zip) {
        if (checker == null) checker = new StockChecker(this, true);
        int radius = Results.prefs(this).getInt("radius", 50);
        checker.run(zip, radius, new StockChecker.Listener() {
            @Override public void onProgress(String retailer) { updateOngoing("Checking " + retailer + "…"); }
            @Override public void onDone(List<JSONObject> rows) {
                int fresh = Results.saveAndNotify(WatchService.this, rows, zip);
                int hits = 0;
                for (JSONObject r : rows) if ("IN STOCK".equals(r.optString("status"))) hits++;
                String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
                updateOngoing((hits > 0 ? hits + " in stock" : "None in stock") + " near " + zip
                        + " · checked " + when + " · every " + intervalMin() + " min");
                Runnable r = onRunFinished;
                if (r != null) r.run();
                if (wake != null && wake.isHeld()) wake.release();
            }
        });
    }

    private PendingIntent alarmIntent() {
        return PendingIntent.getForegroundService(this, 1,
                new Intent(this, WatchService.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void scheduleNext() {
        AlarmManager am = getSystemService(AlarmManager.class);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMin() * 60_000L, alarmIntent());
    }

    private void cancelAlarm() {
        getSystemService(AlarmManager.class).cancel(alarmIntent());
    }

    @Override
    public void onDestroy() {
        if (checker != null) checker.destroy();
        checker = null;
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
