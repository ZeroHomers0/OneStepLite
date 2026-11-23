package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.provider.Settings;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Shared module-owned pins with a read fallback for existing per-user secure settings. */
final class QuickAppPins {
    private static final String KEY = ShortcutSettingsClient.LEGACY_APPS;
    private QuickAppPins() {}

    static int user() { return CurrentUser.id(); }

    static List<String> read(Context context, int user) {
        if (user < 0 || user!=user()) throw new IllegalStateException("current user unavailable");
        try {
            String pins=ShortcutSettingsClient.readForUser(context,ShortcutSettingsClient.APPS,user);
            if(pins!=null)return QuickAppOrder.decode(pins);
        } catch(RuntimeException unavailable) { /* Keep legacy pins until the provider is available. */ }
        return QuickAppOrder.decode((String) XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), KEY, user));
    }

    static boolean save(Context context, int user, Iterable<String> keys) {
        if (user < 0 || user != user()) return false;
        Boolean saved=ShortcutSettingsClient.writeForUser(context,ShortcutSettingsClient.APPS,QuickAppOrder.encode(keys),user);
        if(saved!=null)return saved;
        try {
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(Settings.Secure.class,
                    "putStringForUser", context.getContentResolver(), KEY, QuickAppOrder.encode(keys), user));
        } catch (Throwable unavailable) { return false; }
    }

    static List<QuickApp> arrange(Context context, List<QuickApp> apps) {
        try {
            int user = user();
            List<String> saved = read(context, user);
            List<String> installed = new java.util.ArrayList<>(apps.size());
            for (QuickApp app : apps) installed.add(app.component.flattenToString());
            List<String> pins = QuickAppReconcilePolicy.prune(saved, installed);
            // A cold or invalidated cache is not an authoritative uninstall list.
            // Rendering must never overwrite the user's saved selection.
            return QuickAppOrder.arrange(apps, pins, app -> app.component.flattenToString());
        } catch (Throwable unavailable) { return apps; }
    }
}
