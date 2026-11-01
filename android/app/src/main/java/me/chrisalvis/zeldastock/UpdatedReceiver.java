package me.chrisalvis.zeldastock;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Installing an update switches "full-screen alerts" back off (Android does this for
 * sideloaded apps), and then an in-stock alert can't open checkout over the lock screen.
 * Right after an update, say so with a notification that opens the switch.
 */
public class UpdatedReceiver extends BroadcastReceiver {
    private static final int ID = "full-screen-off".hashCode();

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT < 34) return; // before Android 14 full-screen alerts need no permission
        if (Results.canFullScreen(c) || !Results.canNotify(c)) return;
        Results.createChannels(c);
        Intent allow = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, ID, allow,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = "The update turned off full-screen alerts, so a restock can't open checkout on the lock screen. Tap to allow them again.";
        Notification n = new Notification.Builder(c, Results.CH_UPDATES)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Allow full-screen alerts again")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(ID, n);
    }
}
