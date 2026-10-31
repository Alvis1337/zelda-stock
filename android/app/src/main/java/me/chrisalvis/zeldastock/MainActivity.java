package me.chrisalvis.zeldastock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Checks Target, Walmart and GameStop for Legend of Zelda Switch 2 stock.
 *
 * Each retailer is loaded in a real WebView on the phone's own connection, and a
 * script reads the retailer's own data from that page. A row is shown as IN STOCK
 * only when that data says so; blocks and anything unclear show as UNVERIFIED.
 */
public class MainActivity extends Activity {

    private static final String[] DEFAULT_TCINS = {"1013322047", "1013559065"};
    private static final long JOB_TIMEOUT_MS = 60_000;
    private static final long SETTLE_MS = 2_500;

    private static final class Job {
        final String retailer, url, script;
        Job(String retailer, String url, String script) {
            this.retailer = retailer; this.url = url; this.script = script;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Job> queue = new ArrayList<>();
    private final List<JSONObject> rows = new ArrayList<>();
    private Job current;
    private int jobSeq = 0;
    private boolean scriptStarted;

    private WebView web;
    private EditText zipsInput, radiusInput;
    private Button runButton, browserButton;
    private TextView statusText;
    private LinearLayout resultsBox;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("zs", MODE_PRIVATE);
        buildUi();
    }

    // ------------------------------------------------------------------ UI
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(32), dp(16), dp(8));
        root.setBackgroundColor(Color.parseColor("#F6F7F9"));

        TextView title = new TextView(this);
        title.setText("Zelda Switch 2 stock");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1B2733"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Target (store stock by ZIP), Walmart and GameStop (online). Best Buy is off for now.");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#5B6B7B"));
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.HORIZONTAL);
        zipsInput = new EditText(this);
        zipsInput.setHint("ZIP codes");
        zipsInput.setText(prefs.getString("zips", "45701 45780 43130"));
        zipsInput.setInputType(InputType.TYPE_CLASS_TEXT);
        inputs.addView(zipsInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        radiusInput = new EditText(this);
        radiusInput.setHint("miles");
        radiusInput.setText(prefs.getString("radius", "50"));
        radiusInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputs.addView(radiusInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(inputs);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        runButton = new Button(this);
        runButton.setText("Check stock");
        runButton.setOnClickListener(v -> startRun());
        buttons.addView(runButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        browserButton = new Button(this);
        browserButton.setText("Show browser");
        browserButton.setOnClickListener(v -> toggleBrowser());
        buttons.addView(browserButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(Color.parseColor("#33414E"));
        statusText.setPadding(0, dp(6), 0, dp(6));
        statusText.setText("Tap Check stock.");
        root.addView(statusText);

        web = new WebView(this);
        setupWebView();
        // Kept attached (1px) so pages run; "Show browser" enlarges it to solve a captcha.
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView scroll = new ScrollView(this);
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(resultsBox);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void toggleBrowser() {
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        boolean show = lp.height <= 1;
        lp.height = show ? dp(420) : 1;
        web.setLayoutParams(lp);
        browserButton.setText(show ? "Hide browser" : "Show browser");
    }

    private void addRow(JSONObject r) {
        String status = r.optString("status");
        int color;
        switch (status) {
            case "IN STOCK": color = Color.parseColor("#1E8E3E"); break;
            case "OUT OF STOCK": color = Color.parseColor("#C5221F"); break;
            case "NONE FOUND": color = Color.parseColor("#7A5A00"); break;
            default: color = Color.parseColor("#5F6B7A");
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(2), color);
        card.setBackground(bg);

        TextView head = new TextView(this);
        head.setText(status + " · " + r.optString("retailer"));
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(color);
        card.addView(head);

        TextView prod = new TextView(this);
        prod.setText(r.optString("product"));
        prod.setTextColor(Color.parseColor("#1B2733"));
        prod.setTextSize(15);
        card.addView(prod);

        TextView where = new TextView(this);
        String detail = r.optString("detail");
        where.setText(r.optString("where") + (detail.isEmpty() ? "" : "\n" + detail));
        where.setTextColor(Color.parseColor("#5B6B7B"));
        where.setTextSize(13);
        card.addView(where);

        final String url = r.optString("url");
        if (!url.isEmpty()) {
            card.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        resultsBox.addView(card, lp);
    }

    private void renderAll() {
        resultsBox.removeAllViews();
        List<String> order = Arrays.asList("IN STOCK", "OUT OF STOCK", "NONE FOUND", "UNVERIFIED", "MANUAL", "ERROR");
        List<JSONObject> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int ia = order.indexOf(a.optString("status")), ib = order.indexOf(b.optString("status"));
            return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
        });
        for (JSONObject r : sorted) addRow(r);
    }

    // ------------------------------------------------------------- WebView
    private void setupWebView() {
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
    }

    private final class Bridge {
        @JavascriptInterface
        public void done(final String jobId, final String json) {
            main.post(() -> onJobResult(jobId, json));
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

    // ----------------------------------------------------------------- Run
    private void startRun() {
        String zipsRaw = zipsInput.getText().toString().trim();
        String radius = radiusInput.getText().toString().trim();
        if (radius.isEmpty()) radius = "50";
        prefs.edit().putString("zips", zipsRaw).putString("radius", radius).apply();

        JSONArray zips = new JSONArray();
        for (String z : zipsRaw.split("[\\s,]+")) if (z.matches("\\d{5}")) zips.put(z);
        if (zips.length() == 0) { statusText.setText("Enter at least one 5-digit ZIP."); return; }

        JSONObject params = new JSONObject();
        try {
            params.put("zips", zips);
            params.put("radius", Integer.parseInt(radius));
            params.put("extraTcins", new JSONArray(Arrays.asList(DEFAULT_TCINS)));
        } catch (Exception e) { statusText.setText("Bad input: " + e); return; }

        String common = asset("common.js");
        String prefix = "var ZS_PARAMS = " + params + ";\n";
        rows.clear();
        resultsBox.removeAllViews();
        queue.clear();
        queue.add(new Job("Target",
                "https://www.target.com/s?searchTerm=nintendo+switch+2+zelda",
                prefix + common + asset("target.js")));
        queue.add(new Job("Walmart",
                "https://www.walmart.com/search?q=nintendo+switch+2+zelda",
                prefix + common + asset("walmart.js")));
        queue.add(new Job("GameStop",
                "https://www.gamestop.com/search/?q=zelda+switch+2&lang=default",
                prefix + common + asset("gamestop.js")));
        runButton.setEnabled(false);
        nextJob();
    }

    private void nextJob() {
        if (queue.isEmpty()) {
            current = null;
            runButton.setEnabled(true);
            int hits = 0;
            for (JSONObject r : rows) if ("IN STOCK".equals(r.optString("status"))) hits++;
            statusText.setText(hits + " verified in-stock result(s) · checked "
                    + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
            renderAll();
            return;
        }
        current = queue.remove(0);
        jobSeq++;
        scriptStarted = false;
        final int seq = jobSeq;
        final Job job = current;
        statusText.setText("Checking " + job.retailer + "…");
        web.loadUrl(job.url);
        main.postDelayed(() -> {
            if (seq == jobSeq && current == job) {
                rows.add(row(job.retailer, "timed out after 60s (page never finished)", "UNVERIFIED", job.url));
                renderAll();
                nextJob();
            }
        }, JOB_TIMEOUT_MS);
    }

    private void injectScript() {
        scriptStarted = true;
        String js = "var ZS_JOB = '" + jobSeq + "';\n" + current.script;
        web.evaluateJavascript(js, null);
    }

    private void onJobResult(String jobId, String json) {
        if (current == null || !jobId.equals(String.valueOf(jobSeq))) return;
        Job job = current;
        try {
            JSONObject res = new JSONObject(json);
            if (res.optBoolean("blocked")) {
                rows.add(row(job.retailer,
                        "bot check shown. Tap Show browser, solve it, then Check stock again.",
                        "UNVERIFIED", job.url));
            }
            JSONArray rs = res.optJSONArray("rows");
            if (rs != null) for (int i = 0; i < rs.length(); i++) rows.add(rs.getJSONObject(i));
        } catch (Exception e) {
            rows.add(row(job.retailer, "could not read result: " + e.getMessage(), "ERROR", job.url));
        }
        renderAll();
        nextJob();
    }

    private static JSONObject row(String retailer, String detail, String status, String url) {
        JSONObject o = new JSONObject();
        try {
            o.put("retailer", retailer);
            o.put("product", "-");
            o.put("where", "-");
            o.put("status", status);
            o.put("detail", detail);
            o.put("url", url);
        } catch (Exception ignored) { }
        return o;
    }
}
