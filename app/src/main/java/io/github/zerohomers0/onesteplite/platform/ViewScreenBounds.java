package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;

/** Reads a View's physical screen bounds, including window transforms, into a caller-owned rect. */
final class ViewScreenBounds {
    private ViewScreenBounds() {}

    static boolean read(View view, Rect out) {
        if (view == null || out == null || !view.isAttachedToWindow()) return false;
        try {
            XposedHelpers.callMethod(view, "getBoundsOnScreen", out);
        } catch (Throwable unavailable) {
            return false;
        }
        return !out.isEmpty();
    }
}
