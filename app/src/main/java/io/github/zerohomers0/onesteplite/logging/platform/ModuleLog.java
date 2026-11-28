package io.github.zerohomers0.onesteplite;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;
import java.util.function.Consumer;

/** One routing point for routine, warning and compatibility diagnostics. */
final class ModuleLog {
    private static final String TAG = Tags.PROBE;

    private ModuleLog() {}

    /** Routine traffic stays in logcat; high-frequency messages must not enter the Xposed bridge. */
    static void info(String scope, String message) {
        if (!RoutineLog.allow(message)) return;
        logcat(Log.INFO, scope, message, null, false);
    }

    static void routine(String scope, String message) {
        info(scope, message);
    }

    /** Explicit one-off diagnostics are mirrored to the Xposed log for device-side troubleshooting. */
    static void diagnostic(String scope, String message) {
        logcat(Log.INFO, scope, message, null, true);
    }

    static void warn(String scope, String message) {
        logcat(Log.WARN, scope, message, null, true);
    }

    static void error(String scope, String message, Throwable failure) {
        logcat(Log.ERROR, scope, message, failure, true);
    }

    static Consumer<String> consumer(String scope) {
        return message -> info(scope, message);
    }

    private static void logcat(int level, String scope, String message, Throwable failure,
            boolean bridge) {
        String line = scope == null || scope.isEmpty() ? message : scope + ": " + message;
        if (line == null) line = "null";
        if (level == Log.ERROR) Log.e(TAG, line, failure);
        else if (level == Log.WARN) Log.w(TAG, line, failure);
        else Log.i(TAG, line);
        if (!bridge) return;
        try {
            XposedBridge.log(failure == null ? TAG + ": " + line : TAG + ": " + line + ": " + failure);
        } catch (Throwable ignored) {
            // logcat remains available when the Xposed bridge is unavailable.
        }
    }
}