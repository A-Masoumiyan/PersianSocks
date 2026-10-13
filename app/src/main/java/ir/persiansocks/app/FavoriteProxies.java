package ir.persiansocks.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * User-owned saved proxy list. Deliberately separate from scan and test caches.
 * Automatic rescans, rejection, sorting, and cache cleanup NEVER remove favorites.
 */
final class FavoriteProxies {
    private static final String FILE = "persiansocks-user-favorites";
    private static final String KEY = "saved-proxies-v1";
    private FavoriteProxies() {}

    static LinkedHashMap<String, ProxyEndpoint> load(Context context) {
        LinkedHashMap<String, ProxyEndpoint> saved = new LinkedHashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                ProxyEndpoint proxy = ProxyEndpoint.fromJson(object);
                if (proxy == null || ProxyEndpoint.parse(proxy.key()) == null) continue;
                saved.put(proxy.key(), proxy);
            }
        } catch (Exception ex) {
            android.util.Log.e("PersianSocks", "Favorite list could not be read; original preferences kept", ex);
        }
        return saved;
    }

    /** Synchronous commit, so a successful star tap persists before the UI reports success. */
    static boolean save(Context context, Collection<ProxyEndpoint> saved) {
        try {
            JSONArray array = new JSONArray();
            for (ProxyEndpoint proxy : saved) array.put(proxy.json());
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, array.toString()).commit();
        } catch (Exception ex) {
            android.util.Log.e("PersianSocks", "Favorite list save failed", ex);
            return false;
        }
    }
}
