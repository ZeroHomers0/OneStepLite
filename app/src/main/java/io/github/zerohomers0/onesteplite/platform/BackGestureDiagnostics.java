package io.github.zerohomers0.onesteplite;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Observes native back gestures without taking over their input channel. */
final class BackGestureDiagnostics {
    private final BooleanSupplier zoomed;
    private final Consumer<String> logger;
    BackGestureDiagnostics(BooleanSupplier zoomed,Consumer<String> logger) { this.zoomed=zoomed;this.logger=logger; }
    private void log(String text) { logger.accept(text); }
    void install(ClassLoader cl) {
        try {
            Class<?> receiver = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl$inputEventReceiver$1", cl);
            XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("DisplayBackGestureHandler", (android.view.MotionEvent) p.args[0]);
                }
            });
            log("DisplayBackGestureHandler input diagnostics installed");
        } catch (Throwable error) {
            log("DisplayBackGestureHandler input diagnostics unavailable: " + error);
        }
        try {
            Class<?> panel = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.BackPanelController", cl);
            XposedBridge.hookAllMethods(panel, "onMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("BackPanelController", (android.view.MotionEvent) p.args[0]);
                }
            });
            log("BackPanelController input diagnostics installed");
        } catch (Throwable error) {
            log("BackPanelController input diagnostics unavailable: " + error);
        }
    }

    private void logBackEvent(String source, android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action != android.view.MotionEvent.ACTION_DOWN
                && action != android.view.MotionEvent.ACTION_UP
                && action != android.view.MotionEvent.ACTION_CANCEL) return;
        int display = -1;
        try { display = (Integer) XposedHelpers.callMethod(event, "getDisplayId"); }
        catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
        log("back event source=" + source + " action=" + action + " display=" + display
                + " local=" + event.getX() + "," + event.getY()
                + " raw=" + event.getRawX() + "," + event.getRawY()
                + " hostZoom=" + zoomed.getAsBoolean());
    }
}
