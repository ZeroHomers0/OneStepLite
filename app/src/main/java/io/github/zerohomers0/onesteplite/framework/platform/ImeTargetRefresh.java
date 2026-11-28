package io.github.zerohomers0.onesteplite;

import java.util.function.Consumer;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** Publish viewport, magnification and parent together on native input-target changes. */
final class ImeTargetRefresh {
    private boolean updating;

    void update(Object display, Predicate<Object> lease, Consumer<Object> configure) {
        if (updating || display == null) return;
        updating = true;
        try {
            ImeViewport.refreshLayering(display, lease);
            Object ime = ImeWindow.current(display);
            if (ime == null) return;
            configure.accept(ime);
            ShellCompat.call(display, "reapplyMagnificationSpec",
                    XposedHelpers.callMethod(display, "getPendingTransaction"));
            XposedHelpers.callMethod(display, "updateImeParent");
        } finally { updating = false; }
    }
}
