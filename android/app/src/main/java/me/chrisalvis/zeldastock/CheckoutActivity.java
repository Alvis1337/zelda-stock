package me.chrisalvis.zeldastock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        Button add = new Button(this); add.setText("Add to cart");
        add.setOnClickListener(v -> {
            String u = web.getUrl();
            if (productUrl != null && (u == null || !sameHost(u, productUrl) || u.contains("/cart"))) {
                assistPending = true;
                web.loadUrl(productUrl);
            } else runAssist();
        });
        Button reload = new Button(this); reload.setText("Reload");
        reload.setOnClickListener(v -> web.reload());
        Button close = new Button(this); close.setText("Close");
        close.setOnClickListener(v -> finish());
        for (Button x : new Button[]{back, add, reload, close}) {
            buttons.addView(x, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        bar.addView(buttons);
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "ZSA");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
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
        String params = "var ZSA_PARAMS = {retailer: " + org.json.JSONObject.quote(retailer)
                + ", maxPrice: " + MAX_PRICE + "};\n";
        web.evaluateJavascript(params + asset("common.js") + asset("assist.js"), null);
    }

    private final class Bridge {
        @JavascriptInterface public void status(String text) {
            runOnUiThread(() -> status.setText(retailer + ": " + text));
        }
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
