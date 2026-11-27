package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Isolates fullscreen side-task and leased TOP bars from main-region appearance. */
final class SideSystemBarHooks {
    private SideSystemBarHooks() {}

    static void install(ClassLoader loader, BooleanSupplier rotationActive,
            BooleanSupplier zoomed, Supplier<Object> displayContent,
            Predicate<Object> sideTask, Consumer<String> log) {
        try {
            Class<?> windowState = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", loader);
            XposedBridge.hookAllMethods(windowState, "getSystemUiVisibility",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!rotationActive.getAsBoolean() || !(p.getResult() instanceof Integer))
                        return;
                    try {
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (!sideTask.test(task)) return;
                        int original = (Integer) p.getResult();
                        int visible = SystemBarVisibilityPolicy.forSideTask(original);
                        if (visible != original) p.setResult(visible);
                    } catch (Throwable error) {
                        log.accept("side task system-bar policy skipped: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(windowState, "canAffectSystemUiFlags",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!zoomed.getAsBoolean() || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        Object display = displayContent.get();
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != display)
                            return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        // TOP has its own bar-free viewport. Its unscaled landscape frame
                        // must not consume the main task's status-bar appearance region.
                        if (rotationActive.getAsBoolean() && SideRoleChange.isSide(task)
                                && DesktopLaunchBridge.ownsTopViewport(task)) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        Object lease = DesktopLaunchBridge.recentsLease(task);
                        if (lease != null && DesktopLaunchBridge.recentsLeaseAlive(lease))
                            p.setResult(Boolean.FALSE);
                    } catch (Throwable stale) {
                        // Preserve the native decision for unrelated windows.
                    }
                }
            });
            log.accept("side task fullscreen system-ui flags and leased TOP appearance isolated from main bars");
        } catch (Throwable error) {
            log.accept("side task system-bar policy unavailable: " + error);
        }
    }
}
