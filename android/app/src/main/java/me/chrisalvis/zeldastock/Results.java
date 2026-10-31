package me.chrisalvis.zeldastock;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stored results, settings, and restock notifications. */
public final class Results {
    private Results() { }

    public static final String CH_ALERTS = "restock_alarm";
    public static final String CH_WATCH = "watch";
    public static final String CH_UPDATES = "updates";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("zs", Context.MODE_PRIVATE);
    }

    public static JSONObject row(String retailer, String product, String where,
                                 String status, String detail, String url) {
        JSONObject o = new JSONObject();
        try {
            o.put("retailer", retailer);
            o.put("product", product);
            o.put("where", where);
            o.put("status", status);
            o.put("detail", detail);
            o.put("url", url);
        } catch (Exception ignored) { }
        return o;
    }

    private static String key(JSONObject r) {
        return r.optString("retailer") + "|" + r.optString("product") + "|" + r.optString("where");
    }

    public static List<JSONObject> load(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("last_rows", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * Saves a finished run and posts a notification for every IN STOCK row that
     * was not in stock on the previous run. Returns the number of new rows.
     */
    public static int saveAndNotify(Context c, List<JSONObject> rows, String zip) {
        SharedPreferences p = prefs(c);
        Set<String> before = new HashSet<>(p.getStringSet("in_stock_keys", new HashSet<>()));
        Set<String> now = new HashSet<>();
        List<JSONObject> fresh = new ArrayList<>();
        for (JSONObject r : rows) {
            if (!"IN STOCK".equals(r.optString("status"))) continue;
            String k = key(r);
            now.add(k);
            if (!before.contains(k)) fresh.add(r);
        }
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        p.edit().putString("last_rows", a.toString())
                .putLong("last_checked", System.currentTimeMillis())
                .putString("last_zip", zip)
                .putStringSet("in_stock_keys", now)
                .apply();
        for (JSONObject r : fresh) notifyInStock(c, r);
        return fresh.size();
    }

    public static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("alerts"); // v1 channel, replaced by the louder one below
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "In-stock alarm",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Loud, repeating alert when the Zelda 40th Anniversary console shows up in stock");
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        alerts.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alerts);
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Watcher running",
                NotificationManager.IMPORTANCE_MIN);
        watch.setDescription("Shown while the background watcher is on");
        nm.createNotificationChannel(watch);
        NotificationChannel upd = new NotificationChannel(CH_UPDATES, "App updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(upd);
    }

    public static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Fires a sample alert so the sound, vibration and tap-through can be checked. */
    public static void testAlert(Context c) {
        notifyInStock(c, row("Test", "Zelda 40th Anniversary console (test alert)", "this is only a test",
                "IN STOCK", "Tap to open the Best Buy page. Real alerts look like this.",
                "https://www.bestbuy.com/site/searchpage.jsp?st=nintendo+switch+2+zelda+40th+anniversary+edition"));
    }

    private static void notifyInStock(Context c, JSONObject r) {
        if (!canNotify(c)) return;
        createChannels(c);
        String url = r.optString("url");
        Intent open = url.isEmpty()
                ? new Intent(c, MainActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, key(r).hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = "In stock at " + r.optString("retailer") + ": " + r.optString("where");
        String text = r.optString("product") + "\n" + r.optString("detail");
        Notification.Builder b = new Notification.Builder(c, CH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(r.optString("product"))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // keeps ringing until you open or dismiss it
        c.getSystemService(NotificationManager.class).notify(key(r).hashCode(), n);
    }
}
