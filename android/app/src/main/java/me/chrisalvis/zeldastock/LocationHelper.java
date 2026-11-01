package me.chrisalvis.zeldastock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

/** Turns the phone's current location into a ZIP code for the store search. */
public final class LocationHelper {
    private LocationHelper() { }

    public interface Callback {
        void onZip(String zip);
        void onError(String message);
    }

    private static final long FRESH_MS = 30 * 60 * 1000L;

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public static void currentZip(Context c, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!hasPermission(c)) { cb.onError("location permission not granted"); return; }
        LocationManager lm = c.getSystemService(LocationManager.class);
        Location best = null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) { }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < FRESH_MS) {
            geocode(c, best, cb, main);
            return;
        }
        final Location fallback = best;
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            if (fallback != null) geocode(c, fallback, cb, main);
            else cb.onError("location is turned off");
            return;
        }
        final boolean[] done = {false};
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            if (fallback != null) geocode(c, fallback, cb, main);
            else cb.onError("couldn't get a location fix");
        };
        main.postDelayed(timeout, 20_000);
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, c.getMainExecutor(), loc -> {
                if (done[0]) return;
                done[0] = true;
                main.removeCallbacks(timeout);
                if (loc != null) geocode(c, loc, cb, main);
                else if (fallback != null) geocode(c, fallback, cb, main);
                else cb.onError("couldn't get a location fix");
            });
        } else {
            lm.requestSingleUpdate(provider, new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    geocode(c, loc, cb, main);
                }
            }, Looper.getMainLooper());
        }
    }

    @SuppressWarnings("deprecation")
    private static void geocode(Context c, Location loc, Callback cb, Handler main) {
        if (!Geocoder.isPresent()) { cb.onError("this phone has no geocoder"); return; }
        new Thread(() -> {
            try {
                List<Address> a = new Geocoder(c, Locale.US)
                        .getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                String zip = (a != null && !a.isEmpty()) ? a.get(0).getPostalCode() : null;
                if (zip != null && zip.length() >= 5) {
                    String z = zip.substring(0, 5);
                    main.post(() -> cb.onZip(z));
                } else {
                    main.post(() -> cb.onError("no ZIP for this location"));
                }
            } catch (Exception e) {
                main.post(() -> cb.onError("geocoder failed: " + e.getMessage()));
            }
        }).start();
    }
}
