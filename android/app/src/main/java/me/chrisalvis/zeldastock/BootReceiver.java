package me.chrisalvis.zeldastock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns the watcher back on after a reboot or an app update, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Results.prefs(c).getBoolean("watching", false)) {
            try {
                WatchService.start(c);
            } catch (Exception ignored) {
                // Some phones refuse a background start here; opening the app restarts it.
            }
        }
    }
}
