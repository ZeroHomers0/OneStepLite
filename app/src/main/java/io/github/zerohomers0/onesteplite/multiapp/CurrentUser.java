package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XposedHelpers;

/** The user the SystemUI process is running as; -1 when the framework refuses to answer. */
final class CurrentUser {
    private CurrentUser() {}

    static int id() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(android.app.ActivityManager.class,
                    "getCurrentUser");
        } catch (Throwable unavailable) { return -1; }
    }
}
