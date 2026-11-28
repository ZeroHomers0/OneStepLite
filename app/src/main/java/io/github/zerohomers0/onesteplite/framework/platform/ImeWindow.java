package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XposedHelpers;

/** Use the display's current IME accessor, retaining the older field contract. */
final class ImeWindow {
    static Object current(Object display) {
        if (display == null) return null;
        Object window = ShellCompat.hasMethod(display.getClass(), "getImeWindow", 0)
                ? XposedHelpers.callMethod(display, "getImeWindow")
                : XposedHelpers.getObjectField(display, "mInputMethodWindow");
        if (window != null && XposedHelpers.callMethod(window, "getDisplayContent") != display)
            throw new IllegalStateException("IME window belongs to another display");
        return window;
    }
    private ImeWindow() {}
}
