package me.chrisalvis.zeldastock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * In-app browser for signing in and for assisted checkout.
 *
 * Uses the app's own cookie jar, so once you sign in here every retailer page
 * sees you as signed in. With assist on, it runs
 * assets/assist.js on the product page: one console into the cart, then the
 * cart opens and you finish checkout yourself.
 */
public class CheckoutActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RETAILER = "retailer";
    public static final String EXTRA_ASSIST = "assist";
    public static final String EXTRA_NOTIF_ID = "notif_id";
    /** How long the alarm rings over the lock screen if nobody touches the phone. */
    private static final long ALARM_MS = 60_000;
    /** Highest price assist will ever add to the cart (MSRP is $519.99). */
    public static final int MAX_PRICE = 560;

    private WebView web;
    private TextView status;
    private String retailer, productUrl;
    private boolean assistPending;
    private android.media.Ringtone alarm;
    private boolean assisting;

    public static Intent intent(Context c, String url, String retailer, boolean assist) {
        return new Intent(c, CheckoutActivity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_RETAILER, retailer)
                .putExtra(EXTRA_ASSIST, assist)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Opened from an in-stock alert: show over the lock screen and wake the display.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Same dark as the bar, so the white status bar icons above it show.
        root.setBackgroundColor(Color.parseColor("#17212B"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#17212B"));
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextSize(14);
        bar.addView(status);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this); back.setText("Back");
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); else finish(); });
        Button add = new Button(this); add.setText("Add");
        add.setOnClickListener(v -> {
            String u = web.getUrl();
            if (productUrl != null && (u == null || !sameHost(u, productUrl) || u.contains("/cart"))) {
                assistPending = true;
                web.loadUrl(productUrl);
            } else runAssist();
        });
        Button reload = new Button(this); reload.setText("Reload");
        reload.setOnClickListener(v -> web.reload());
        // For a store that won't let the in-app browser sign in: same page in your browser.
        Button chrome = new Button(this); chrome.setText("Chrome");
        chrome.setOnClickListener(v -> {
            String u = web.getUrl() != null ? web.getUrl() : productUrl;
            if (u == null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { status.setText("No browser app found."); }
        });
        Button close = new Button(this); close.setText("Close");
        close.setOnClickListener(v -> finish());
        for (Button x : new Button[]{back, add, reload, chrome, close}) {
            buttons.addView(x, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        bar.addView(buttons);
        root.addView(bar);

        // Your own phone only: lets chrome://inspect (adb) see these pages, for sites that misbehave.
        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        // Page errors go to logcat (adb logcat -s ZeldaWeb), e.g. a sign-in button that silently fails.
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    Log.w("ZeldaWeb", m.messageLevel() + " " + m.message() + " @ " + m.sourceId() + ":" + m.lineNumber());
                }
                return false;
            }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        looksLikeChrome(s);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedHttpError(WebView v, android.webkit.WebResourceRequest req, android.webkit.WebResourceResponse res) {
                Log.w("ZeldaWeb", "HTTP " + res.getStatusCode() + " " + req.getMethod() + " " + req.getUrl());
            }

            @Override public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
                // assist.js's last word is lost when it navigates to the cart; say it here.
                if (assisting && url.contains("/cart")) {
                    assisting = false;
                    status.setText(retailer + ": added. Check out from here.");
                }
                // Product URLs redirect to longer slugs, so match on the site, not the exact URL.
                if (assistPending && productUrl != null && sameHost(url, productUrl)) {
                    assistPending = false;
                    web.postDelayed(CheckoutActivity.this::runAssist, 1500);
                }
            }
        });
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            root.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(root);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        load(i);
    }

    private void load(Intent i) {
        retailer = i.getStringExtra(EXTRA_RETAILER);
        productUrl = i.getStringExtra(EXTRA_URL);
        assistPending = i.getBooleanExtra(EXTRA_ASSIST, false);
        status.setText(assistPending
                ? retailer + ": opening the page to add one to your cart…"
                : (retailer == null ? "" : retailer + ": ") + "sign in or browse. The app keeps you signed in.");
        if (productUrl != null) web.loadUrl(productUrl);
        if (assistPending) ringOverLockScreen(i.getIntExtra(EXTRA_NOTIF_ID, 0));
    }

    /**
     * Opened by an in-stock alert over the lock screen: ring here, until the phone is
     * touched. The notification's own sound can be muted by a connected watch
     * (Pixel Watch mutes phone notification effects), an app's alarm audio can't.
     */
    private void ringOverLockScreen(int notifId) {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) return; // unlocked: you tapped the alert yourself
        if (notifId != 0) getSystemService(android.app.NotificationManager.class).cancel(notifId); // one sound, not two
        stopAlarm();
        try {
            alarm = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM));
            if (alarm == null) return;
            alarm.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (Build.VERSION.SDK_INT >= 28) alarm.setLooping(true);
            alarm.play();
            web.postDelayed(this::stopAlarm, ALARM_MS);
        } catch (Exception e) {
            alarm = null;
        }
    }

    private void stopAlarm() {
        if (alarm != null) { alarm.stop(); alarm = null; }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        stopAlarm();
    }

    private static boolean sameHost(String a, String b) {
        try {
            String ha = android.net.Uri.parse(a).getHost(), hb = android.net.Uri.parse(b).getHost();
            return ha != null && hb != null && ha.replaceFirst("^www\\.", "").equals(hb.replaceFirst("^www\\.", ""));
        } catch (Exception e) { return false; }
    }

    private void runAssist() {
        if (retailer == null) return;
        status.setText(retailer + ": checking price and seller…");
        String params = "window.__zsaStatus = ''; var ZSA_PARAMS = {retailer: " + org.json.JSONObject.quote(retailer)
                + ", maxPrice: " + MAX_PRICE + "};\n";
        web.evaluateJavascript(params + asset("common.js") + asset("assist.js"), null);
        assisting = true;
        pollAssistStatus(System.currentTimeMillis() + 45_000);
    }

    /**
     * Sites (Target's sign-in among them) refuse embedded browsers. A WebView gives itself
     * away by the X-Requested-With header (the app's package name) and by an
     * "Android WebView" brand in the client hints; the user agent string is fixed above.
     */
    private static void looksLikeChrome(WebSettings s) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, java.util.Collections.emptySet());
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            UserAgentMetadata m = WebSettingsCompat.getUserAgentMetadata(s);
            java.util.List<UserAgentMetadata.BrandVersion> brands = new java.util.ArrayList<>();
            for (UserAgentMetadata.BrandVersion b : m.getBrandVersionList()) {
                brands.add(b.getBrand().contains("WebView")
                        ? new UserAgentMetadata.BrandVersion.Builder(b).setBrand("Google Chrome").build()
                        : b);
            }
            WebSettingsCompat.setUserAgentMetadata(s,
                    new UserAgentMetadata.Builder(m).setBrandVersionList(brands).build());
        }
    }

    /** assist.js reports progress in window.__zsaStatus; read it until it has finished. */
    private void pollAssistStatus(long until) {
        web.evaluateJavascript("window.__zsaStatus || ''", v -> {
            String t = "";
            try { t = String.valueOf(new org.json.JSONTokener(v).nextValue()); } catch (Exception ignored) { }
            if (!t.isEmpty()) status.setText(retailer + ": " + t);
            boolean added = t.startsWith("Added");
            boolean stopped = t.contains("Not adding") || t.contains("not adding") || t.startsWith("No active")
                    || t.startsWith("Bot check") || t.startsWith("Assist stopped") || t.contains("in-store only");
            if (stopped) assisting = false;
            if (added || stopped) return;
            if (System.currentTimeMillis() < until) web.postDelayed(() -> pollAssistStatus(until), 400);
            else assisting = false;
        });
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopAlarm();
        CookieManager.getInstance().flush();
        web.destroy();
        super.onDestroy();
    }
}
