package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Keep native status-bar and sidebar animations inside the scaled main display. */
final class MainWindowClip {
    private static final class Owned {
        final Object display, surface;
        Rect crop;
        Owned(Object display, Object surface) { this.display = display; this.surface = surface; }
    }
    private final IdentityHashMap<Object, Owned> owned = new IdentityHashMap<>();

    void update(Object window, Object display, Consumer<String> log) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return;
        Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
        int type=XposedHelpers.getIntField(attrs,"type");
        Object pkg=XposedHelpers.getObjectField(attrs,"packageName");
        boolean statusBar=type==WindowTypes.STATUS_BAR && Packages.SYSTEM_UI.equals(pkg);
        boolean sidebar=type==WindowTypes.DISPLAY_OVERLAY && Packages.SECURITY_CENTER.equals(pkg)
                && "DockAssistantView".contentEquals((CharSequence)XposedHelpers.callMethod(attrs,"getTitle"));
        if (!statusBar && !sidebar) return;
        Object token = XposedHelpers.getObjectField(window, "mToken");
        if (token == null || XposedHelpers.callMethod(token, "getParent") == null) return;
        Object surface = XposedHelpers.callMethod(token, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Rect bounds = (Rect) XposedHelpers.callMethod(token, "getBounds");
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;
        // The token is outside the moving window/insets leash. Its own native rotation
        // and ancestor magnification project this local canvas into the main area.
        Rect crop = new Rect(0, 0, bounds.width(), bounds.height());
        Owned current = owned.get(token);
        if (current == null || current.surface != surface || current.display != display) {
            current = new Owned(display, surface);
            owned.put(token, current);
        }
        // The OEM hide animation may reset this crop without replacing the token.
        // Reassert sidebar ownership after native prepareSurfaces, including exit
        // frames whose client is no longer visible but whose surface still animates.
        boolean changed = !crop.equals(current.crop);
        if (!sidebar && !changed) return;
        // Remember ownership before writing so exit can also clean up a failed write.
        XposedHelpers.callMethod(XposedHelpers.callMethod(token, "getPendingTransaction"),
                "setCrop", surface, crop);
        current.crop = crop;
        if (changed) log.accept((sidebar ? "main sidebar clip=" : "main status bar clip=") + crop);
    }

    void clear(Consumer<String> log) {
        for (Object token : owned.keySet().toArray()) {
            Owned current = owned.get(token);
            try {
                // Never clear a replacement SurfaceControl or a token moved to another display.
                if (XposedHelpers.callMethod(token, "getDisplayContent") == current.display
                        && XposedHelpers.callMethod(token, "getSurfaceControl") == current.surface
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(current.surface, "isValid"))) {
                    XposedHelpers.callMethod(XposedHelpers.callMethod(token, "getPendingTransaction"),
                            "setCrop", current.surface, (Object) null);
                }
                owned.remove(token);
            } catch (Throwable error) {
                log.accept("main status bar clip release deferred: " + error);
            }
        }
    }
}
