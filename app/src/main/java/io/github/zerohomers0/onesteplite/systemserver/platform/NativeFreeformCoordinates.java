package io.github.zerohomers0.onesteplite;

import android.view.MotionEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps OEM freeform coordinates in OneStepLite's main-region coordinate space. */
final class NativeFreeformCoordinates {
    private static final ThreadLocal<MotionEvent> velocityEvent = new ThreadLocal<>();
    static void install(ClassLoader cl) {
        try {
            Class<?> touchState = XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTouchState", cl);
            Class<?> velocity = XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.common.MultiTaskingVelocityMonitor", cl);
            XposedHelpers.findAndHookMethod(velocity, "update", float[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MotionEvent event = velocityEvent.get();
                    if (event == null || !(p.args[0] instanceof float[])
                            || ((float[]) p.args[0]).length != 2) return;
                    // MultiTaskSwitch's transformed spy already supplies logical getX/Y.
                    // TouchState alone samples getRawX/Y; never invert the event a second time.
                    p.args[0] = new float[]{event.getX(), event.getY()};
                }
            });
            XposedBridge.hookAllMethods(touchState, "handleMotionEvents", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("onesteplitePreviousVelocityEvent", velocityEvent.get());
                    velocityEvent.remove();
                    if (p.args.length < 2 || !(p.args[0] instanceof MotionEvent)
                            || p.args[1] == null) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(event, "getDisplayId"))) return;
                    velocityEvent.set(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                        XposedBridge.log("OneStepLiteProbe: native freeform pointer logical="
                                + event.getX() + "," + event.getY() + " raw="
                                + event.getRawX() + "," + event.getRawY());
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    MotionEvent previous = (MotionEvent) p.getObjectExtra("onesteplitePreviousVelocityEvent");
                    if (previous == null) velocityEvent.remove();
                    else velocityEvent.set(previous);
                }
            });
            XposedBridge.log("OneStepLiteProbe: native freeform velocity uses monitor logical coordinates");
        } catch (Throwable error) {
            XposedBridge.log("OneStepLiteProbe: native freeform velocity mapping unavailable: " + error);
        }
    }
}
