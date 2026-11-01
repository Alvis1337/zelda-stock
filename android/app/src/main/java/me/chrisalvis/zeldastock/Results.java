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
import java.util.List;

/** Stored server results, settings, and the notifications for server pushes. */
public final class Results {
    private Results() { }

    public static final String CH_ALERTS = "restock_alarm";
    public static final String CH_SERVER = "server";
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

    /** The last /api/status answer, so the main screen has something to show offline. */
    public static void saveStatus(Context c, JSONObject status) {
        prefs(c).edit().putString("last_status", status.toString())
                .putLong("last_status_at", System.currentTimeMillis()).apply();
    }

    public static JSONObject loadStatus(Context c) {
        try {
            return new JSONObject(prefs(c).getString("last_status", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static List<JSONObject> rows(JSONObject status) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = status.optJSONArray("rows");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Handles one message pushed by the server (see server/src/notify). */
    public static void onServerPush(Context c, JSONObject m) {
        String type = m.optString("type");
        prefs(c).edit().putString("last_push", m.toString()).putLong("last_push_at", System.currentTimeMillis()).apply();
        if ("in_stock".equals(type) || "test".equals(type)) {
            JSONObject r = row(m.optString("retailer"), m.optString("product"), m.optString("where"),
                    "IN STOCK", m.optString("detail"), m.optString("url"));
            // A real alert opens checkout and adds one to the cart; a test only opens the page.
            notifyInStock(c, r, "in_stock".equals(type));
        } else {
            // One notification per retailer for blocked/recovered, so "recovered" replaces "blocked"
            // and two blocked retailers don't overwrite each other.
            String retailer = m.optString("retailer");
            int id = ("server|" + (retailer.isEmpty() ? type : retailer)).hashCode();
            notifyServer(c, m.optString("title", "Zelda Stock server"), m.optString("body"), id);
        }
    }

    /** Normal-priority notification for server news: blocked, recovered, heartbeat. */
    public static void notifyServer(Context c, String title, String text, int id) {
        if (!canNotify(c)) return;
        createChannels(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH_SERVER)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
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
        nm.deleteNotificationChannel("watch"); // the old on-phone watcher
        NotificationChannel server = new NotificationChannel(CH_SERVER, "Server status",
                NotificationManager.IMPORTANCE_DEFAULT);
        server.setDescription("A retailer is blocked or recovered, and the daily heartbeat");
        nm.createNotificationChannel(server);
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
                "IN STOCK", "Tap to open the Best Buy page. Real alerts open checkout and add one to your cart.",
                "https://www.bestbuy.com/product/switch-2-the-legend-of-zelda-40th-anniversary-edition/J7GSL57HTY"), false);
    }

    /** True if alerts may pop checkout open on their own (lock screen / screen off). */
    public static boolean canFullScreen(Context c) {
        return Build.VERSION.SDK_INT < 34
                || c.getSystemService(NotificationManager.class).canUseFullScreenIntent();
    }

    public static void notifyInStock(Context c, JSONObject r, boolean assist) {
        if (!canNotify(c)) return;
        createChannels(c);
        String url = r.optString("url");
        // Tapping the alert opens the in-app checkout, which adds one to your cart.
        Intent open = url.isEmpty()
                ? new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                : CheckoutActivity.intent(c, url, r.optString("retailer"), assist).putExtra(CheckoutActivity.EXTRA_NOTIF_ID, key(r).hashCode());
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
                // Its own group: Android bundles ungrouped alerts, and tapping the bundle opens the app, not checkout.
                .setGroup("instock|" + key(r))
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        // With the screen off or locked, open checkout right away instead of waiting for a tap.
        if (assist && !url.isEmpty() && canFullScreen(c)) b.setFullScreenIntent(pi, true);
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // keeps ringing until you open or dismiss it
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Updating a notification that's still in the shade doesn't fire its full-screen intent
        // again; cancel first so a repeat alert for the same store opens checkout too.
        nm.cancel(key(r).hashCode());
        nm.notify(key(r).hashCode(), n);
    }
}
