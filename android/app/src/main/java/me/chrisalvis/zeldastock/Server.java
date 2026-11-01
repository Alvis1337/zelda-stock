package me.chrisalvis.zeldastock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to the stock server on the home cluster. The server sits behind Cloudflare
 * Access, so every call carries the service-token client ID and secret the user
 * entered in Settings (never built into the app).
 */
public final class Server {
    private Server() { }

    public static final String DEFAULT_URL = "https://zelda-stock.chrisalvis.me";

    public interface Callback { void done(JSONObject res, String error); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static String baseUrl(Context c) {
        String u = Results.prefs(c).getString("server_url", DEFAULT_URL).trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? DEFAULT_URL : u;
    }

    public static boolean configured(Context c) {
        SharedPreferences p = Results.prefs(c);
        return !p.getString("cf_id", "").isEmpty() && !p.getString("cf_secret", "").isEmpty();
    }

    /** Async call; the callback runs on the main thread. */
    public static void call(Context c, String method, String path, JSONObject body, Callback cb) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            JSONObject res = null;
            String err = null;
            try {
                res = callSync(app, method, path, body);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final JSONObject r = res;
            final String e = err;
            if (cb != null) MAIN.post(() -> cb.done(r, e));
        });
    }

    /** Blocking call, for background threads. Throws with a readable message on failure. */
    public static JSONObject callSync(Context c, String method, String path, JSONObject body) throws Exception {
        if (!configured(c)) throw new Exception("add the Cloudflare client ID and secret in Server settings");
        SharedPreferences p = Results.prefs(c);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl(c) + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(false); // an Access login redirect means the token was refused
            conn.setRequestProperty("CF-Access-Client-Id", p.getString("cf_id", "").trim());
            conn.setRequestProperty("CF-Access-Client-Secret", p.getString("cf_secret", "").trim());
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code == 401 || code == 403 || (code >= 300 && code < 400)) {
                throw new Exception("Cloudflare Access refused the client ID / secret (HTTP " + code + ")");
            }
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject json;
            try {
                json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (Exception e) {
                throw new Exception("HTTP " + code + ": not a server response");
            }
            if (code >= 400 && !(code == 409 && json.has("error"))) {
                throw new Exception("HTTP " + code + ": " + json.optString("error", text));
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Sends this phone's push endpoint to the server, if there is one and the
     * server credentials are set. Safe to call often; the server dedupes by endpoint.
     */
    public static void registerPush(Context c, Callback cb) {
        SharedPreferences p = Results.prefs(c);
        String endpoint = p.getString("push_endpoint", "");
        if (endpoint.isEmpty() || !configured(c)) {
            if (cb != null) MAIN.post(() -> cb.done(null, endpoint.isEmpty() ? "no push endpoint yet" : "server not set up"));
            return;
        }
        JSONObject b = new JSONObject();
        try {
            b.put("endpoint", endpoint);
            b.put("p256dh", p.getString("push_p256dh", ""));
            b.put("auth", p.getString("push_auth", ""));
            b.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        } catch (Exception ignored) { }
        call(c, "POST", "/api/register", b, (res, err) -> {
            SharedPreferences.Editor e = Results.prefs(c).edit();
            if (err == null) {
                e.putString("push_registered", endpoint).putLong("push_registered_at", System.currentTimeMillis());
            } else {
                e.remove("push_registered");
            }
            e.putString("push_server_error", err == null ? "" : err).apply();
            if (cb != null) cb.done(res, err);
        });
    }
}
