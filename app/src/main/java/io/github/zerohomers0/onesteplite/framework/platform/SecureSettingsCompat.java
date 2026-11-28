package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;

/** Isolates the legacy per-user Secure setting read used during settings migration. */
final class SecureSettingsCompat {
    private SecureSettingsCompat() {}

    static String legacyStringForUser(Context context, String key, int user) {
        return (String) XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), key, user);
    }
}