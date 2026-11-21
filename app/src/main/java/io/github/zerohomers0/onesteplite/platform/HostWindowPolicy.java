package io.github.zerohomers0.onesteplite;

import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/** Keep only our host windows in physical screen coordinates. */
final class HostWindowPolicy {
    private static final int NOT_MAGNIFIABLE = 1 << 22;
    private static final int EXCLUDE_FROM_MAGNIFICATION_REGION = 1 << 21;
    /**
     * {@code WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP}.
     * DragState only offers a drag to a window that is either the drag source or carries this
     * flag, and only such a window receives the ClipData with ACTION_DRAG_STARTED. Without it the
     * host never sees a drag that started in another app, so the drop listener is dead code.
     */
    private static final int INTERCEPT_GLOBAL_DRAG_AND_DROP = 1 << 31;

    private HostWindowPolicy() {}

    static void configure(WindowManager.LayoutParams params) {
        // These windows belong to SystemUI, which holds INTERNAL_SYSTEM_WINDOW.
        // A transparent hole in an application-overlay window's touch region alone does not prevent
        // InputDispatcher from rejecting touches to the application underneath.
        XposedHelpers.callMethod(params, "setTrustedOverlay");
        int flags = XposedHelpers.getIntField(params, "privateFlags");
        XposedHelpers.setIntField(params, "privateFlags",
                flags | NOT_MAGNIFIABLE | EXCLUDE_FROM_MAGNIFICATION_REGION
                        | INTERCEPT_GLOBAL_DRAG_AND_DROP);
    }
}
