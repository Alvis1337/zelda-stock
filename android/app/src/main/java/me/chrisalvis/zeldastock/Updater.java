package me.chrisalvis.zeldastock;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from GitHub releases, the same way rotato does it: read the latest
 * release, compare the last number of its tag (v1.0.N) with this build's
 * versionCode, download the APK, and hand it to the system installer.
 */
public final class Updater {
    private Updater() { }

    private static final String LATEST =
            "https://api.github.com/repos/Alvis1337/zelda-stock/releases/latest";

    public static final class Info {
        public final int versionCode;
        public final String tag, apkUrl;
        Info(int versionCode, String tag, String apkUrl) {
            this.versionCode = versionCode; this.tag = tag; this.apkUrl = apkUrl;
        }
    }

    public interface CheckCallback { void onResult(Info updateOrNull, String error); }
    public interface DownloadCallback { void onProgress(int pct); void onDone(File apk, String error); }

    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void check(CheckCallback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "ZeldaStock/" + BuildConfig.VERSION_NAME);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                if (c.getResponseCode() != 200) {
                    int code = c.getResponseCode();
                    main.post(() -> cb.onResult(null, "HTTP " + code));
                    return;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                JSONObject j = new JSONObject(out.toString("UTF-8"));
                String tag = j.optString("tag_name");
                String last = tag.substring(tag.lastIndexOf('.') + 1);
                int code = Integer.parseInt(last.replaceAll("\\D", ""));
                String apk = null;
                JSONArray assets = j.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
                }
                if (apk == null) { main.post(() -> cb.onResult(null, "release " + tag + " has no APK")); return; }
                final Info info = code > BuildConfig.VERSION_CODE ? new Info(code, tag, apk) : null;
                main.post(() -> cb.onResult(info, null));
            } catch (Exception e) {
                main.post(() -> cb.onResult(null, String.valueOf(e.getMessage())));
            }
        }).start();
    }

    public static void download(Context ctx, Info info, DownloadCallback cb) {
        new Thread(() -> {
            File dest = new File(ctx.getExternalFilesDir(null), "zelda-stock-update.apk");
            try {
                if (dest.exists()) dest.delete();
                HttpURLConnection c = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "ZeldaStock/" + BuildConfig.VERSION_NAME);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                if (c.getResponseCode() != 200) throw new java.io.IOException("download HTTP " + c.getResponseCode());
                int total = c.getContentLength();
                long done = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        done += n;
                        if (total > 0) { int pct = (int) (done * 100 / total); main.post(() -> cb.onProgress(pct)); }
                    }
                }
                main.post(() -> cb.onDone(dest, null));
            } catch (Exception e) {
                dest.delete();
                main.post(() -> cb.onDone(null, String.valueOf(e.getMessage())));
            }
        }).start();
    }

    /** Opens the system installer, or the "install unknown apps" setting if that's still off. */
    /** @return false if the user first has to allow installs from this app. */
    public static boolean install(Activity a, File apk) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
        return true;
    }

    /** Background check from the watcher: posts a notification that opens the app. */
    public static void notifyIfAvailable(Context ctx) {
        check((info, err) -> {
            if (info == null || !Results.canNotify(ctx)) return;
            if (Results.prefs(ctx).getInt("notified_update", 0) >= info.versionCode) return;
            Results.prefs(ctx).edit().putInt("notified_update", info.versionCode).apply();
            Results.createChannels(ctx);
            PendingIntent pi = PendingIntent.getActivity(ctx, 7,
                    new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, Results.CH_UPDATES)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Zelda Stock update " + info.tag)
                    .setContentText("Open the app to install it.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ctx.getSystemService(NotificationManager.class).notify(2, n);
        });
    }
}
