package me.chrisalvis.zeldastock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the retailer checks in a WebView, one retailer at a time.
 *
 * Each retailer page loads on the phone's own connection, then a script from
 * assets/ reads the retailer's own data and reports rows back through the "ZS"
 * bridge. Must be used from the main thread.
 */
public final class StockChecker {

    public interface Listener {
        void onProgress(String retailer);
        void onDone(List<JSONObject> rows);
    }

    private static final long JOB_TIMEOUT_MS = 60_000;
    private static final long SETTLE_MS = 2_500;

    private static final class Job {
        final String retailer, url, script;
        Job(String retailer, String url, String script) {
            this.retailer = retailer; this.url = url; this.script = script;
        }
    }

    private final Context ctx;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Job> queue = new ArrayList<>();
    private final List<JSONObject> rows = new ArrayList<>();
    private Listener listener;
    private Job current;
    private int jobSeq = 0;
    private boolean scriptStarted;

    /**
     * @param offscreen true when the WebView is not in any window (background
     *                  service); it then gets a fixed phone-sized layout so pages render.
     */
    @SuppressLint("SetJavaScriptEnabled")
    public StockChecker(Context ctx, boolean offscreen) {
        this.ctx = ctx;
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Present as regular mobile Chrome rather than an embedded WebView.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "ZS");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (current == null || scriptStarted) return;
                final int seq = jobSeq;
                main.postDelayed(() -> {
                    if (seq == jobSeq && current != null && !scriptStarted) injectScript();
                }, SETTLE_MS);
            }
        });
        if (offscreen) {
            int w = 1080, h = 2200;
            web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, w, h);
        }
    }

    public WebView getWebView() { return web; }

    public boolean isRunning() { return current != null; }

    public void destroy() {
        queue.clear();
        current = null;
        jobSeq++;
        web.stopLoading();
        web.destroy();
    }

    private final class Bridge {
        @JavascriptInterface
        public void done(final String jobId, final String json) {
            main.post(() -> onJobResult(jobId, json));
        }
    }

    private String asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Checks every retailer. Store-level checks use the given ZIP and radius. */
    public void run(String zip, int radiusMiles, Listener l) {
        if (isRunning()) return;
        listener = l;
        JSONObject params = new JSONObject();
        try {
            params.put("zips", new JSONArray().put(zip));
            params.put("radius", radiusMiles);
        } catch (Exception ignored) { }
        String prefix = "var ZS_PARAMS = " + params + ";\n" + asset("common.js");
        rows.clear();
        queue.clear();
        queue.add(new Job("Best Buy",
                "https://www.bestbuy.com/product/switch-2-the-legend-of-zelda-40th-anniversary-edition/J7GSL57HTY",
                prefix + asset("bestbuy.js")));
        queue.add(new Job("Target",
                "https://www.target.com/s?searchTerm=nintendo+switch+2+zelda+40th+anniversary+edition",
                prefix + asset("target.js")));
        queue.add(new Job("Walmart",
                "https://www.walmart.com/ip/21002656445",
                prefix + asset("walmart.js")));
        queue.add(new Job("GameStop",
                "https://www.gamestop.com/search/?q=switch+2+zelda+40th+anniversary&lang=default",
                prefix + asset("gamestop.js")));
        queue.add(new Job("GameStop",
                "https://www.gamestop.com/consoles-hardware/nintendo-switch-2/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition/20037854.html",
                prefix + asset("gamestop_store.js")));
        queue.add(new Job("Nintendo",
                "https://www.nintendo.com/us/store/products/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition-121642/",
                prefix + asset("nintendo.js")));
        queue.add(new Job("Amazon",
                "https://www.amazon.com/dp/B0HJ6F8L6V",
                prefix + asset("amazon.js")));
        nextJob();
    }

    private void nextJob() {
        if (queue.isEmpty()) {
            current = null;
            web.loadUrl("about:blank");
            if (listener != null) listener.onDone(new ArrayList<>(rows));
            return;
        }
        current = queue.remove(0);
        jobSeq++;
        scriptStarted = false;
        final int seq = jobSeq;
        final Job job = current;
        if (listener != null) listener.onProgress(job.retailer);
        web.loadUrl(job.url);
        main.postDelayed(() -> {
            if (seq == jobSeq && current == job) {
                rows.add(Results.row(job.retailer, "-", "-", "UNVERIFIED",
                        "timed out after 60s (page never finished)", job.url));
                nextJob();
            }
        }, JOB_TIMEOUT_MS);
    }

    private void injectScript() {
        scriptStarted = true;
        web.evaluateJavascript("var ZS_JOB = '" + jobSeq + "';\n" + current.script, null);
    }

    private void onJobResult(String jobId, String json) {
        if (current == null || !jobId.equals(String.valueOf(jobSeq))) return;
        Job job = current;
        try {
            JSONObject res = new JSONObject(json);
            if (res.optBoolean("blocked")) {
                rows.add(Results.row(job.retailer, "-", "-", "UNVERIFIED",
                        "bot check shown. Open the app, tap Show browser, solve it, then Check now.", job.url));
            }
            JSONArray rs = res.optJSONArray("rows");
            if (rs != null) for (int i = 0; i < rs.length(); i++) rows.add(rs.getJSONObject(i));
        } catch (Exception e) {
            rows.add(Results.row(job.retailer, "-", "-", "ERROR",
                    "could not read result: " + e.getMessage(), job.url));
        }
        nextJob();
    }
}
