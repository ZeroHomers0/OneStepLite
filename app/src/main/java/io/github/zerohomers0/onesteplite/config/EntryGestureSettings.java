package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.provider.Settings;

/** Reads the system gesture-line flag that gates the bottom-centre entry gesture. */
final class EntryGestureSettings {
    static final String HIDE_GESTURE_LINE = "hide_gesture_line";
    private EntryGestureSettings() {}

    static boolean lineHidden(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), HIDE_GESTURE_LINE, 0) == 1;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
