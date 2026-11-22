package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.provider.Settings;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** SystemUI-owned per-user preferences; never stored in a process-wide app cache. */
final class QuickAppPins {
    private static final String KEY = "onesteplite_quick_app_pins_v1";
    private QuickAppPins() {}

    static int user() { return CurrentUser.id(); }

    static List<String> read(Context context, int user) {
        if (user < 0) throw new IllegalStateException("current user unavailable");
        return QuickAppOrder.decode((String) XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), KEY, user));
    }

    static boolean save(Context context, int user, Iterable<String> keys) {
        if (user < 0 || user != user()) return false;
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
