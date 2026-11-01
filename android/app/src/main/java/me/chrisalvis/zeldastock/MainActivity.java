package me.chrisalvis.zeldastock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.unifiedpush.android.connector.UnifiedPush;

import java.text.DateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Zelda 40th Anniversary Switch 2 stock alerts.
 *
 * The checks run on the home server; this app shows the server's latest results,
 * gets its alerts through UnifiedPush, and does the checkout on the phone.
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFY = 2, REQ_NOTIFY_TEST = 3;
    private static final long STALE_MS = 30 * 60_000L;
    private static final String PUSH_INSTANCE = "default";

    private static final int INK = Color.parseColor("#17212B");
    private static final int MUTED = Color.parseColor("#5B6B7B");
    private static final int BG = Color.parseColor("#F3F5F8");
    private static final int ACCENT = Color.parseColor("#2E7D32");
    private static final int WARN = Color.parseColor("#C62828");

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root, resultsBox, settingsBox;
    private TextView serverText, statusText, pushText, settingsText, updateText, fullText;
    private EditText urlInput, idInput, secretInput;
    private Button checkButton, pushButton, updateButton, fullButton, settingsToggle;
    private LinearLayout updateCard;
    private Updater.Info pendingUpdate;
    private boolean polling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Results.prefs(this);
        Results.createChannels(this);
        buildUi();
        showStatus(Results.loadStatus(this));
        if (Build.VERSION.SDK_INT >= 33 && !Results.canNotify(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        // Re-register on every start, as UnifiedPush asks; the distributor returns the same endpoint.
        if (UnifiedPush.getAckDistributor(this) != null) {
            UnifiedPush.register(this, PUSH_INSTANCE, null, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionRows();
        refreshPushUi();
        if (Server.configured(this)) {
            refreshStatus();
            // Retry a failed registration, and keep the server's copy fresh (it may have lost its data).
            String endpoint = prefs.getString("push_endpoint", "");
            if (!endpoint.equals(prefs.getString("push_registered", ""))
                    || System.currentTimeMillis() - prefs.getLong("push_registered_at", 0) > 12 * 3_600_000L) {
                Server.registerPush(this, (r, e) -> refreshPushUi());
            }
        }
        if (pendingUpdate == null && System.currentTimeMillis() - lastUpdateCheck > 60_000) checkForUpdate();
    }

    @Override
    protected void onPause() {
        polling = false;
        super.onPause();
    }

    // ------------------------------------------------------------------ UI
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setElevation(dp(1));
        return c;
    }

    private void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(card, lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(value);
        e.setTextSize(14);
        return e;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // Keep content clear of the status bar, nav bar and keyboard (edge-to-edge on Android 15).
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            root.setPadding(left + dp(16), top + dp(12), right + dp(16), bottom + dp(16));
            return insets;
        });

        root.addView(text("Zelda 40th Anniversary", 24, INK, true));
        TextView sub = text("Nintendo Switch 2 console only, sold by the retailer itself. "
                + "The home server checks Best Buy, Target, Walmart, GameStop, Nintendo and Amazon "
                + "every few minutes; this phone just gets the alert.", 13, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(8));
        root.addView(sub);

        // Update banner.
        updateCard = card();
        updateText = text("", 14, INK, true);
        updateCard.addView(updateText);
        updateButton = new Button(this);
        updateButton.setOnClickListener(v -> onUpdateButton());
        updateCard.addView(updateButton);
        addCard(root, updateCard);
        setUpdateUi("App v" + BuildConfig.VERSION_NAME, "Check for updates");

        // Server status.
        LinearLayout server = card();
        server.addView(text("Home server", 16, INK, true));
        serverText = text("", 15, INK, true);
        serverText.setPadding(0, dp(4), 0, dp(2));
        server.addView(serverText);
        statusText = text("", 13, MUTED, false);
        server.addView(statusText);
        LinearLayout actions = row();
        actions.setPadding(0, dp(6), 0, 0);
        checkButton = new Button(this);
        checkButton.setText("Check now");
        checkButton.setOnClickListener(v -> checkNow());
        actions.addView(checkButton, weight(1));
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshStatus());
        actions.addView(refresh, weight(1));
        server.addView(actions);
        addCard(root, server);

        // Alerts.
        LinearLayout alerts = card();
        alerts.addView(text("Alerts", 16, INK, true));
        pushText = text("", 13, MUTED, false);
        alerts.addView(pushText);
        pushButton = new Button(this);
        pushButton.setOnClickListener(v -> setUpPush());
        alerts.addView(pushButton);
        LinearLayout tRow = row();
        Button test = new Button(this);
        test.setText("Test alarm");
        test.setOnClickListener(v -> {
            if (!Results.canNotify(this) && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY_TEST);
            } else {
                Results.testAlert(this);
            }
        });
        tRow.addView(test, weight(1));
        Button serverTest = new Button(this);
        serverTest.setText("Test from server");
        serverTest.setOnClickListener(v -> testFromServer());
        tRow.addView(serverTest, weight(1));
        alerts.addView(tRow);
        LinearLayout fRow = row();
        fRow.setPadding(0, dp(4), 0, 0);
        fullText = text("", 13, MUTED, false);
        fRow.addView(fullText, weight(1));
        fullButton = new Button(this);
        fullButton.setText("Allow");
        fullButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        fRow.addView(fullButton);
        alerts.addView(fRow);
        addCard(root, alerts);

        // Sign in once so checkout has your saved card and address.
        LinearLayout signin = card();
        signin.addView(text("Fast checkout", 16, INK, true));
        signin.addView(text("Sign in to each store once here. When the console shows up, the alert "
                + "opens checkout, adds one to your cart (only at about MSRP and only from the store "
                + "itself), and you confirm the payment.", 13, MUTED, false));
        String[][] stores = {
                {"Best Buy", "https://www.bestbuy.com/identity/global/signin"},
                {"Target", "https://www.target.com/account"},
                {"Walmart", "https://www.walmart.com/account/login"},
                {"Amazon", "https://www.amazon.com/gp/css/homepage.html"},
                {"Nintendo", "https://www.nintendo.com/us/"}};
        LinearLayout sRow = null;
        for (int i = 0; i < stores.length; i++) {
            if (i % 3 == 0) { sRow = row(); signin.addView(sRow); }
            final String[] st = stores[i];
            Button sb = new Button(this);
            sb.setText(st[0]);
            sb.setAllCaps(false);
            sb.setOnClickListener(v -> startActivity(CheckoutActivity.intent(this, st[1], st[0], false)));
            sRow.addView(sb, weight(1));
        }
        addCard(root, signin);

        // Server settings: Cloudflare Access service token, entered here, never built into the app.
        LinearLayout settings = card();
        LinearLayout hRow = row();
        hRow.addView(text("Server settings", 16, INK, true), weight(1));
        settingsToggle = new Button(this);
        settingsToggle.setOnClickListener(v -> showSettings(settingsBox.getVisibility() != View.VISIBLE));
        hRow.addView(settingsToggle);
        settings.addView(hRow);
        settingsText = text("", 13, MUTED, false);
        settings.addView(settingsText);
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.addView(text("Server URL", 13, MUTED, false));
        urlInput = field(Server.DEFAULT_URL, Server.baseUrl(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        settingsBox.addView(urlInput);
        settingsBox.addView(text("Cloudflare Access client ID", 13, MUTED, false));
        idInput = field("xxxxxxxx.access", prefs.getString("cf_id", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        settingsBox.addView(idInput);
        settingsBox.addView(text("Cloudflare Access client secret", 13, MUTED, false));
        secretInput = field("secret", prefs.getString("cf_secret", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settingsBox.addView(secretInput);
        Button save = new Button(this);
        save.setText("Save and connect");
        save.setOnClickListener(v -> saveSettings());
        settingsBox.addView(save);
        settings.addView(settingsBox);
        addCard(root, settings);
        showSettings(!Server.configured(this));

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);

        setContentView(scroll);
    }

    private void showSettings(boolean show) {
        settingsBox.setVisibility(show ? View.VISIBLE : View.GONE);
        settingsToggle.setText(show ? "Hide" : "Edit");
        settingsText.setText(Server.configured(this)
                ? "Connected through Cloudflare Access to " + Server.baseUrl(this)
                : "Enter the Cloudflare Access service token (client ID and secret) for the server.");
    }

    private void saveSettings() {
        String url = urlInput.getText().toString().trim();
        if (!url.isEmpty() && !url.startsWith("https://")) {
            settingsText.setText("The server URL must start with https://");
            return;
        }
        prefs.edit()
                .putString("server_url", url)
                .putString("cf_id", idInput.getText().toString().trim())
                .putString("cf_secret", secretInput.getText().toString().trim())
                .remove("push_registered_at")
                .apply();
        settingsText.setText("Connecting…");
        Server.call(this, "GET", "/api/status", null, (res, err) -> {
            if (err != null) {
                settingsText.setText("Could not connect: " + err);
                return;
            }
            Results.saveStatus(this, res);
            showStatus(res);
            showSettings(false);
            Server.registerPush(this, (r, e) -> refreshPushUi());
        });
    }

    private void refreshPermissionRows() {
        boolean full = Results.canFullScreen(this);
        fullText.setText(full
                ? "Alerts can open checkout on the lock screen by themselves."
                : "Allow alerts to open checkout on the lock screen without a tap.");
        fullButton.setVisibility(full ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- Push
    private void refreshPushUi() {
        String endpoint = prefs.getString("push_endpoint", "");
        String state = prefs.getString("push_state", "");
        String serverErr = prefs.getString("push_server_error", "");
        boolean registered = !endpoint.isEmpty() && endpoint.equals(prefs.getString("push_registered", ""));
        String distributor = UnifiedPush.getAckDistributor(this);
        if (!Results.canNotify(this)) {
            pushText.setText("Notifications are off for this app, so alerts can't ring.");
            pushText.setTextColor(WARN);
        } else if (registered) {
            pushText.setText("On. The server pushes alerts to this phone through "
                    + (distributor == null ? "your push app" : distributor) + ".");
            pushText.setTextColor(ACCENT);
        } else if (!endpoint.isEmpty()) {
            pushText.setText("Push is set up but the server doesn't know this phone yet"
                    + (serverErr.isEmpty() ? "." : ": " + serverErr));
            pushText.setTextColor(WARN);
        } else if (UnifiedPush.getDistributors(this).isEmpty()) {
            pushText.setText("Install the ntfy app (it delivers the server's alerts without keeping "
                    + "this app awake), then tap Set up alerts.");
            pushText.setTextColor(WARN);
        } else {
            pushText.setText(state.isEmpty() ? "Not set up yet." : "Push " + state + ".");
            pushText.setTextColor(WARN);
        }
        pushButton.setText(registered ? "Re-register alerts"
                : UnifiedPush.getDistributors(this).isEmpty() ? "Get ntfy" : "Set up alerts");
    }

    private void setUpPush() {
        if (UnifiedPush.getDistributors(this).isEmpty()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=io.heckel.ntfy")));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=io.heckel.ntfy")));
            }
            return;
        }
        pushText.setText("Registering…");
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this, ok -> {
            if (ok) {
                UnifiedPush.register(this, PUSH_INSTANCE, null, null);
                // The endpoint arrives in PushReceiver; give it a moment, then show the result.
                main.postDelayed(this::refreshPushUi, 3_000);
            } else {
                UnifiedPush.tryPickDistributor(this, picked -> {
                    if (picked) UnifiedPush.register(this, PUSH_INSTANCE, null, null);
                    main.postDelayed(this::refreshPushUi, 3_000);
                    return kotlin.Unit.INSTANCE;
                });
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    private void testFromServer() {
        pushText.setText("Asking the server to send a test alert…");
        Server.call(this, "POST", "/api/test-alert", null, (res, err) -> {
            if (err != null) {
                pushText.setText("Test failed: " + err);
                return;
            }
            JSONObject sent = res.optJSONObject("sent");
            pushText.setText("Server sent it: " + (sent == null ? res.toString() : sent.toString())
                    + ". The alarm should ring in a few seconds.");
        });
    }

    // ------------------------------------------------------------- Results
    private void refreshStatus() {
        if (!Server.configured(this)) return;
        Server.call(this, "GET", "/api/status", null, (res, err) -> {
            if (err != null) {
                statusText.setText("Could not reach the server: " + err);
                return;
            }
            Results.saveStatus(this, res);
            showStatus(res);
        });
    }

    private static long parseTime(String iso) {
        try {
            return iso == null || iso.isEmpty() || "null".equals(iso) ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private void showStatus(JSONObject s) {
        List<JSONObject> rows = Results.rows(s);
        long last = parseTime(s.optString("lastRun", ""));
        if (!Server.configured(this)) {
            serverText.setText("Not connected. Open Server settings below.");
            serverText.setTextColor(WARN);
        } else if (last == 0) {
            serverText.setText("No server results yet.");
            serverText.setTextColor(MUTED);
        } else {
            long ago = System.currentTimeMillis() - last;
            long min = ago / 60_000;
            serverText.setText("Last server check: " + (min < 1 ? "just now" : min + " min ago"));
            serverText.setTextColor(ago > STALE_MS ? WARN : ACCENT);
        }
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (JSONObject r : rows) if ("IN STOCK".equals(r.optString("status"))) hits++;
        if (last > 0) {
            sb.append(hits > 0 ? hits + " in stock" : "None in stock").append(" near ");
            org.json.JSONArray zips = s.optJSONArray("zips");
            if (zips == null || zips.length() == 0) sb.append('?');
            for (int i = 0; zips != null && i < zips.length(); i++) sb.append(i > 0 ? ", " : "").append(zips.optString(i));
        }
        JSONObject running = s.optJSONObject("running");
        if (running != null) {
            sb.append(sb.length() > 0 ? "\n" : "").append("Checking ").append(running.optString("progress", "…")).append(" now…");
        } else if (s.optBoolean("paused")) {
            sb.append(sb.length() > 0 ? "\n" : "").append("Paused on the server (someone is using its browser view).");
        } else {
            long next = parseTime(s.optString("nextRun", ""));
            if (next > 0) sb.append(sb.length() > 0 ? "\n" : "").append("Next check ")
                    .append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(next)));
        }
        long cached = prefs.getLong("last_status_at", 0);
        if (cached > 0 && System.currentTimeMillis() - cached > 5 * 60_000L) {
            sb.append("\n(saved ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(cached))).append(")");
        }
        statusText.setText(sb.toString());
        render(rows);
    }

    private void render(List<JSONObject> rows) {
        resultsBox.removeAllViews();
        List<String> order = Arrays.asList("IN STOCK", "OUT OF STOCK", "NONE FOUND", "UNVERIFIED", "MANUAL", "ERROR");
        List<JSONObject> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int ia = order.indexOf(a.optString("status")), ib = order.indexOf(b.optString("status"));
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });
        for (JSONObject r : sorted) addResult(r);
    }

    private void addResult(JSONObject r) {
        String status = r.optString("status");
        int color;
        switch (status) {
            case "IN STOCK": color = ACCENT; break;
            case "OUT OF STOCK": color = WARN; break;
            case "NONE FOUND": color = Color.parseColor("#8D6E00"); break;
            default: color = Color.parseColor("#607080");
        }
        LinearLayout c = card();
        GradientDrawable bg = (GradientDrawable) c.getBackground();
        bg.setStroke(dp(2), color);
        TextView head = text(status + " · " + r.optString("retailer"), 13, color, true);
        c.addView(head);
        String product = r.optString("product");
        if (!product.isEmpty() && !"-".equals(product)) c.addView(text(product, 15, INK, false));
        String where = r.optString("where"), detail = r.optString("detail");
        StringBuilder sb = new StringBuilder();
        if (!where.isEmpty() && !"-".equals(where)) sb.append(where);
        if (!detail.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(detail); }
        if (sb.length() > 0) c.addView(text(sb.toString(), 13, MUTED, false));
        final String url = r.optString("url");
        final String retailer = r.optString("retailer");
        final boolean inStock = "IN STOCK".equals(status);
        if (!url.isEmpty()) c.setOnClickListener(v ->
                startActivity(CheckoutActivity.intent(this, url, retailer, inStock)));
        if (inStock && !url.isEmpty()) {
            TextView go = text("Tap to add one to your cart and check out", 13, ACCENT, true);
            c.addView(go);
        }
        addCard(resultsBox, c);
    }

    // ----------------------------------------------------------------- Run
    private void checkNow() {
        if (!Server.configured(this)) {
            showSettings(true);
            return;
        }
        checkButton.setEnabled(false);
        statusText.setText("Asking the server to check now…");
        final long before = parseTime(Results.loadStatus(this).optString("lastRun", ""));
        Server.call(this, "POST", "/api/check", null, (res, err) -> {
            if (err != null || res.has("error")) {
                checkButton.setEnabled(true);
                statusText.setText("Check now failed: " + (err != null ? err : res.optString("error")));
                return;
            }
            polling = true;
            poll(before, System.currentTimeMillis());
        });
    }

    /** Refreshes every 5 s until the server finishes the run (a run takes 1–4 min). */
    private void poll(long before, long started) {
        if (!polling) { checkButton.setEnabled(true); return; }
        Server.call(this, "GET", "/api/status", null, (res, err) -> {
            if (err == null) {
                Results.saveStatus(this, res);
                showStatus(res);
                boolean done = res.optJSONObject("running") == null && parseTime(res.optString("lastRun", "")) > before;
                if (done) { polling = false; checkButton.setEnabled(true); return; }
            }
            if (System.currentTimeMillis() - started > 6 * 60_000L) {
                polling = false;
                checkButton.setEnabled(true);
                return;
            }
            main.postDelayed(() -> poll(before, started), 5_000);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean granted = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (code == REQ_NOTIFY_TEST && granted) Results.testAlert(this);
        refreshPushUi();
    }

    // --------------------------------------------------------------- Update
    private java.io.File downloadedApk;
    private long lastUpdateCheck;

    private void setUpdateUi(String status, String button) {
        updateText.setText(status);
        updateButton.setText(button);
    }

    private void checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis();
        setUpdateUi("App v" + BuildConfig.VERSION_NAME + " · checking for updates…", "Check for updates");
        updateButton.setEnabled(false);
        Updater.check((info, err) -> {
            updateButton.setEnabled(true);
            if (err != null) {
                setUpdateUi("App v" + BuildConfig.VERSION_NAME + " · update check failed: " + err, "Try again");
            } else if (info == null) {
                setUpdateUi("App v" + BuildConfig.VERSION_NAME + " · up to date", "Check for updates");
            } else {
                pendingUpdate = info;
                setUpdateUi("Update available: " + info.tag + " (you have v" + BuildConfig.VERSION_NAME + ")",
                        "Download and install");
            }
        });
    }

    private void onUpdateButton() {
        if (downloadedApk != null) { installDownloaded(); return; }
        if (pendingUpdate != null) { startUpdate(); return; }
        checkForUpdate();
    }

    private void startUpdate() {
        updateButton.setEnabled(false);
        Updater.download(this, pendingUpdate, new Updater.DownloadCallback() {
            @Override public void onProgress(int pct) { updateButton.setText("Downloading " + pct + "%"); }
            @Override public void onDone(java.io.File apk, String error) {
                updateButton.setEnabled(true);
                if (apk == null) {
                    setUpdateUi("Download failed: " + error, "Retry download");
                    return;
                }
                downloadedApk = apk;
                installDownloaded();
            }
        });
    }

    private void installDownloaded() {
        if (Updater.install(this, downloadedApk)) {
            setUpdateUi("Installing " + pendingUpdate.tag + "… if nothing happened, tap Install.", "Install");
        } else {
            setUpdateUi("Allow \"Install unknown apps\" for Zelda Stock, come back, then tap Install.", "Install");
        }
    }
}
