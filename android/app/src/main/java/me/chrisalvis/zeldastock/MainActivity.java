package me.chrisalvis.zeldastock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Zelda 40th Anniversary Switch 2 stock checker.
 *
 * Only the 40th Anniversary console sold by the retailer itself is reported, and
 * a row says IN STOCK only when the retailer's own data says so.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1, REQ_NOTIFY = 2, REQ_NOTIFY_TEST = 3;
    private static final Integer[] INTERVALS = {1, 2, 5, 10, 15, 30, 60};

    private static final int INK = Color.parseColor("#17212B");
    private static final int MUTED = Color.parseColor("#5B6B7B");
    private static final int BG = Color.parseColor("#F3F5F8");
    private static final int ACCENT = Color.parseColor("#2E7D32");

    private SharedPreferences prefs;
    private StockChecker checker;

    private LinearLayout root, resultsBox;
    private TextView statusText, zipLabel, updateText, batteryText, exactText, fullText;
    private EditText zipInput, radiusInput;
    private Switch locationSwitch, watchSwitch;
    private Spinner intervalSpinner;
    private Button checkButton, browserButton, updateButton, batteryButton, exactButton, fullButton;
    private LinearLayout updateCard;
    private Updater.Info pendingUpdate;
    private boolean bindingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Results.prefs(this);
        Results.createChannels(this);
        checker = new StockChecker(this, false);
        buildUi();
        showSaved();
        if (prefs.getBoolean("use_location", true)) refreshLocation(false);
        if (prefs.getBoolean("watching", false)) WatchService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.onRunFinished = () -> runOnUiThread(this::showSaved);
        refreshBatteryRow();
        showSaved();
        if (pendingUpdate == null && System.currentTimeMillis() - lastUpdateCheck > 60_000) checkForUpdate();
    }

    @Override
    protected void onPause() {
        WatchService.onRunFinished = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        checker.destroy();
        super.onDestroy();
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
                + "Best Buy and Target store stock near you; Walmart, GameStop, Nintendo and Amazon online.", 13, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(8));
        root.addView(sub);

        // Update banner (hidden until an update is found).
        updateCard = card();
        updateText = text("", 14, INK, true);
        updateCard.addView(updateText);
        updateButton = new Button(this);
        updateButton.setOnClickListener(v -> onUpdateButton());
        updateCard.addView(updateButton);
        addCard(root, updateCard);
        setUpdateUi("App v" + BuildConfig.VERSION_NAME, "Check for updates");

        // Search area.
        LinearLayout area = card();
        area.addView(text("Search area", 16, INK, true));
        LinearLayout locRow = row();
        locRow.addView(text("Use my current location", 15, INK, false), weight(1));
        locationSwitch = new Switch(this);
        locationSwitch.setChecked(prefs.getBoolean("use_location", true));
        locationSwitch.setOnCheckedChangeListener((b, on) -> {
            if (bindingUi) return;
            prefs.edit().putBoolean("use_location", on).apply();
            zipInput.setEnabled(!on);
            if (on) refreshLocation(true);
        });
        locRow.addView(locationSwitch);
        area.addView(locRow);
        zipLabel = text("", 13, MUTED, false);
        area.addView(zipLabel);

        LinearLayout inRow = row();
        inRow.setPadding(0, dp(4), 0, 0);
        zipInput = new EditText(this);
        zipInput.setHint("ZIP");
        zipInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        zipInput.setText(prefs.getString("zip", "45701"));
        zipInput.setEnabled(!locationSwitch.isChecked());
        zipInput.addTextChangedListener(new SimpleWatcher(s -> {
            if (s.matches("\\d{5}") && !locationSwitch.isChecked()) prefs.edit().putString("zip", s).apply();
        }));
        inRow.addView(zipInput, weight(1));
        TextView within = text("within", 15, MUTED, false);
        within.setPadding(dp(12), 0, dp(8), 0);
        inRow.addView(within);
        radiusInput = new EditText(this);
        radiusInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        radiusInput.setText(String.valueOf(prefs.getInt("radius", 50)));
        radiusInput.setGravity(Gravity.CENTER);
        radiusInput.addTextChangedListener(new SimpleWatcher(s -> {
            try {
                int r = Integer.parseInt(s);
                if (r > 0 && r <= 250) prefs.edit().putInt("radius", r).apply();
            } catch (NumberFormatException ignored) { }
        }));
        inRow.addView(radiusInput, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        inRow.addView(text(" miles", 15, MUTED, false));
        area.addView(inRow);
        addCard(root, area);

        // Background watcher.
        LinearLayout watch = card();
        LinearLayout wRow = row();
        LinearLayout wText = new LinearLayout(this);
        wText.setOrientation(LinearLayout.VERTICAL);
        wText.addView(text("Watch in background", 16, INK, true));
        wText.addView(text("Notifies you the moment one shows up or a store restocks.", 13, MUTED, false));
        wRow.addView(wText, weight(1));
        watchSwitch = new Switch(this);
        watchSwitch.setChecked(prefs.getBoolean("watching", false));
        watchSwitch.setOnCheckedChangeListener((b, on) -> {
            if (bindingUi) return;
            if (on) enableWatcher(); else WatchService.stop(this);
        });
        wRow.addView(watchSwitch);
        watch.addView(wRow);

        LinearLayout iRow = row();
        iRow.setPadding(0, dp(8), 0, 0);
        iRow.addView(text("Check every", 15, INK, false), weight(1));
        intervalSpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (int m : INTERVALS) labels.add(m + " min");
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        intervalSpinner.setAdapter(ad);
        int cur = Arrays.asList(INTERVALS).indexOf(prefs.getInt("interval_min", 10));
        intervalSpinner.setSelection(cur < 0 ? 3 : cur);
        intervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (prefs.getInt("interval_min", 10) == INTERVALS[pos]) return;
                prefs.edit().putInt("interval_min", INTERVALS[pos]).apply();
                if (prefs.getBoolean("watching", false)) WatchService.start(MainActivity.this);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        iRow.addView(intervalSpinner);
        watch.addView(iRow);

        LinearLayout bRow = row();
        bRow.setPadding(0, dp(6), 0, 0);
        batteryText = text("", 13, MUTED, false);
        bRow.addView(batteryText, weight(1));
        batteryButton = new Button(this);
        batteryButton.setText("Allow");
        batteryButton.setOnClickListener(v -> askBatteryExemption());
        bRow.addView(batteryButton);
        watch.addView(bRow);

        LinearLayout eRow = row();
        eRow.setPadding(0, dp(2), 0, 0);
        exactText = text("", 13, MUTED, false);
        eRow.addView(exactText, weight(1));
        exactButton = new Button(this);
        exactButton.setText("Allow");
        exactButton.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        eRow.addView(exactButton);
        watch.addView(eRow);

        Button test = new Button(this);
        test.setText("Test alert");
        test.setOnClickListener(v -> {
            if (!Results.canNotify(this) && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY_TEST);
            } else {
                Results.testAlert(this);
            }
        });
        watch.addView(test);
        addCard(root, watch);

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
        signin.addView(fRow);
        addCard(root, signin);

        // Actions.
        LinearLayout actions = row();
        checkButton = new Button(this);
        checkButton.setText("Check now");
        checkButton.setOnClickListener(v -> checkNow());
        actions.addView(checkButton, weight(1));
        browserButton = new Button(this);
        browserButton.setText("Show browser");
        browserButton.setOnClickListener(v -> toggleBrowser());
        actions.addView(browserButton, weight(1));
        root.addView(actions);

        statusText = text("", 13, MUTED, false);
        statusText.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(statusText);

        // Kept attached (1px) so pages run; "Show browser" enlarges it to solve a bot check.
        root.addView(checker.getWebView(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);

        setContentView(scroll);
        updateZipLabel();
    }

    private void updateZipLabel() {
        String zip = prefs.getString("zip", "45701");
        zipLabel.setText(locationSwitch.isChecked()
                ? (LocationHelper.hasPermission(this) ? "Searching near ZIP " + zip : "Location permission needed")
                : "Using the ZIP below");
        if (locationSwitch.isChecked()) {
            bindingUi = true;
            zipInput.setText(zip);
            bindingUi = false;
        }
    }

    private void toggleBrowser() {
        View web = checker.getWebView();
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        boolean show = lp.height <= 1;
        lp.height = show ? dp(480) : 1;
        web.setLayoutParams(lp);
        browserButton.setText(show ? "Hide browser" : "Show browser");
    }

    private void refreshBatteryRow() {
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        batteryText.setText(exempt
                ? "Battery: unrestricted, so checks keep running with the screen off."
                : "Battery saver may delay checks with the screen off.");
        batteryButton.setVisibility(exempt ? View.GONE : View.VISIBLE);
        boolean exact = Build.VERSION.SDK_INT < 31
                || getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms();
        exactText.setText(exact
                ? "Timing: exact, so 1–2 min checks stay on schedule."
                : "Timing: Android may stretch short intervals to ~10 min.");
        exactButton.setVisibility(exact ? View.GONE : View.VISIBLE);
        boolean full = Results.canFullScreen(this);
        fullText.setText(full
                ? "Alerts can open checkout on the lock screen by themselves."
                : "Allow alerts to open checkout on the lock screen without a tap.");
        fullButton.setVisibility(full ? View.GONE : View.VISIBLE);
    }

    private void askBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ------------------------------------------------------------- Results
    private void showSaved() {
        if (checker != null && checker.isRunning()) return;
        List<JSONObject> rows = Results.load(this);
        long at = prefs.getLong("last_checked", 0);
        if (at > 0) {
            int hits = 0;
            for (JSONObject r : rows) if ("IN STOCK".equals(r.optString("status"))) hits++;
            statusText.setText((hits > 0 ? hits + " in stock" : "None in stock") + " near "
                    + prefs.getString("last_zip", "?") + " · last checked "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at)));
        } else {
            statusText.setText("No checks yet. Tap Check now.");
        }
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
            case "OUT OF STOCK": color = Color.parseColor("#C62828"); break;
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
        if (checker.isRunning()) return;
        checkButton.setEnabled(false);
        if (locationSwitch.isChecked() && LocationHelper.hasPermission(this)) {
            statusText.setText("Finding your location…");
            LocationHelper.currentZip(this, new LocationHelper.Callback() {
                @Override public void onZip(String zip) {
                    prefs.edit().putString("zip", zip).apply();
                    updateZipLabel();
                    runChecker(zip);
                }
                @Override public void onError(String message) {
                    String zip = prefs.getString("zip", "45701");
                    statusText.setText("Location: " + message + ". Using " + zip + ".");
                    runChecker(zip);
                }
            });
        } else {
            String zip = zipInput.getText().toString().trim();
            if (!zip.matches("\\d{5}")) {
                statusText.setText("Enter a 5-digit ZIP.");
                checkButton.setEnabled(true);
                return;
            }
            prefs.edit().putString("zip", zip).apply();
            runChecker(zip);
        }
    }

    private void runChecker(String zip) {
        int radius = prefs.getInt("radius", 50);
        resultsBox.removeAllViews();
        checker.run(zip, radius, new StockChecker.Listener() {
            @Override public void onProgress(String retailer) {
                statusText.setText("Checking " + retailer + " near " + zip + " (" + radius + " mi)…");
            }
            @Override public void onDone(List<JSONObject> rows) {
                Results.saveAndNotify(MainActivity.this, rows, zip);
                checkButton.setEnabled(true);
                showSaved();
            }
        });
    }

    // ---------------------------------------------------------- Permissions
    private void refreshLocation(boolean ask) {
        if (!LocationHelper.hasPermission(this)) {
            if (ask || !prefs.getBoolean("asked_location", false)) {
                prefs.edit().putBoolean("asked_location", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
            updateZipLabel();
            return;
        }
        zipLabel.setText("Finding your location…");
        LocationHelper.currentZip(this, new LocationHelper.Callback() {
            @Override public void onZip(String zip) {
                prefs.edit().putString("zip", zip).apply();
                updateZipLabel();
            }
            @Override public void onError(String message) {
                zipLabel.setText("Location: " + message + ". Using " + prefs.getString("zip", "45701") + ".");
            }
        });
    }

    private void enableWatcher() {
        if (Build.VERSION.SDK_INT >= 33 && !Results.canNotify(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        WatchService.start(this);
        statusText.setText("Watcher on. It checks every " + prefs.getInt("interval_min", 10) + " min.");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_LOCATION) {
            if (granted) refreshLocation(false);
            else {
                bindingUi = true;
                locationSwitch.setChecked(false);
                bindingUi = false;
                prefs.edit().putBoolean("use_location", false).apply();
                zipInput.setEnabled(true);
                updateZipLabel();
            }
        } else if (code == REQ_NOTIFY_TEST) {
            if (granted) Results.testAlert(this);
        } else if (code == REQ_NOTIFY) {
            if (granted) enableWatcher();
            else {
                bindingUi = true;
                watchSwitch.setChecked(false);
                bindingUi = false;
                statusText.setText("The watcher needs notification permission to alert you.");
            }
        }
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

    private static final class SimpleWatcher implements TextWatcher {
        interface On { void changed(String s); }
        private final On on;
        SimpleWatcher(On on) { this.on = on; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { on.changed(s.toString().trim()); }
    }
}
