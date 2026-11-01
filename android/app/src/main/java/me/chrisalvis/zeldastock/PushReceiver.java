package me.chrisalvis.zeldastock;

import org.json.JSONObject;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PublicKeySet;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.nio.charset.StandardCharsets;

/**
 * Receives the server's alerts through UnifiedPush (the ntfy app is the distributor,
 * so this app holds no connection of its own and does no background work).
 */
public class PushReceiver extends PushService {

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        PublicKeySet keys = endpoint.getPubKeySet();
        Results.prefs(this).edit()
                .putString("push_endpoint", endpoint.getUrl())
                .putString("push_p256dh", keys == null ? "" : keys.getPubKey())
                .putString("push_auth", keys == null ? "" : keys.getAuth())
                .putString("push_state", "")
                .apply();
        Server.registerPush(this, null);
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        try {
            Results.onServerPush(this, new JSONObject(new String(message.getContent(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            android.util.Log.w("ZeldaStock", "bad push message", e);
        }
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        Results.prefs(this).edit().putString("push_state", "registration failed: " + reason).apply();
    }

    @Override
    public void onUnregistered(String instance) {
        Results.prefs(this).edit()
                .remove("push_endpoint").remove("push_p256dh").remove("push_auth").remove("push_registered")
                .putString("push_state", "unregistered by the push app")
                .apply();
    }
}
