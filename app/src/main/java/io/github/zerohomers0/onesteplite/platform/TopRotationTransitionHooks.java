package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.Consumer;

/** Hands owned TOP rotation surfaces to the native transition before reparenting. */
final class TopRotationTransitionHooks {
    private TopRotationTransitionHooks() {}

    interface Host {
        void onTransitionEnd();
        Map<Object, Rect> rotationBounds();
        boolean ownsTaskRotationTransform(Object page);
        boolean isUpperLandscapeTask(Object task);
        void handoffSurface(Object page);
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", loader);
            XC_MethodHook topRotationEnd = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) host.onTransitionEnd();
                }
            };
            XposedBridge.hookAllMethods(transition, "finishTransition", topRotationEnd);
            XposedBridge.hookAllMethods(transition, "abort", topRotationEnd);
            XposedBridge.hookAllMethods(transition, "getLeashSurface", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 2 || p.args[0] == null) return;
                    Object page = p.args[0];
                    if (!RotationAnimation.owns(page) || !host.rotationBounds().containsKey(page))
                        return;
                    try {
                        if (!host.ownsTaskRotationTransform(page)
                                || !host.isUpperLandscapeTask(
                                        XposedHelpers.callMethod(page, "getTask"))
                                || !RotationAnimation.inTransition(page)) return;
                        // Give the native transition the fixed carrier before it builds its reparent.
                        host.handoffSurface(page);
                        RotationAnimation.cancel(page);
                    } catch (Throwable error) {
                        log.accept("TOP native surface handoff unavailable: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            log.accept("TOP rotation transition hook unavailable: " + error);
        }
    }
}
