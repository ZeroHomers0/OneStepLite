package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Replays native TOP system-bar ownership on task, focus and transition callbacks. */
final class TopSystemBarReturnHooks {
    private TopSystemBarReturnHooks() {}

    static void install(ClassLoader loader, TopSystemBars bars,
            Supplier<ZoomTarget> zoomTarget, BooleanSupplier zoomed,
            BooleanSupplier rotationActive, Consumer<String> log) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiStatusBarController", loader);
            if (!TopSystemBars.supported(controller)) {
                log.accept("upper native system bar return uses ROM transition lifecycle; no task-owner replay API");
                return;
            }
            Class<?> follow = XposedHelpers.findClass(
                    "com.android.server.wm.BarFollowAnimationImpl", loader);
            XposedBridge.hookAllMethods(follow, "onTaskInfoChanedForCaptionBar", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 1) return;
                    changed(bars, zoomTarget, zoomed, rotationActive,
                            XposedHelpers.getObjectField(p.thisObject, "mStatusBarController"),
                            p.args[0], log);
                }
            });
            XposedBridge.hookAllMethods(controller, "resetStatusBarState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) changed(bars, zoomTarget, zoomed, rotationActive,
                            p.thisObject,
                            XposedHelpers.getObjectField(p.thisObject, "mCurrentTopTask"), log);
                }
            });
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", loader);
            XposedBridge.hookAllMethods(display, "updateFocusedWindowLocked", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target = zoomTarget.get();
                    if (p.hasThrowable() || target == null || p.thisObject != target.display
                            || !zoomed.getAsBoolean() || !rotationActive.getAsBoolean()) return;
                    synchronized (target.globalLock) {
                        bars.focused(target.display, target.globalLock,
                                () -> zoomed.getAsBoolean() && rotationActive.getAsBoolean()
                                        && zoomTarget.get() == target,
                                log);
                    }
                }
            });
            log.accept("upper native system bar return follows task/focus/transition lifecycle");
        } catch (Throwable unavailable) {
            log.accept("upper native system bar return hooks unavailable: " + unavailable);
        }
    }

    private static void changed(TopSystemBars bars, Supplier<ZoomTarget> zoomTarget,
            BooleanSupplier zoomed, BooleanSupplier rotationActive,
            Object controller, Object task, Consumer<String> log) {
        ZoomTarget target = zoomTarget.get();
        if (target == null || !zoomed.getAsBoolean() || !rotationActive.getAsBoolean()) return;
        synchronized (target.globalLock) {
            if (zoomTarget.get() != target) return;
            bars.changed(controller, task, target.display, target.globalLock,
                    () -> zoomed.getAsBoolean() && rotationActive.getAsBoolean()
                            && zoomTarget.get() == target,
                    log);
        }
    }
}
