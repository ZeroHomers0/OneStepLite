package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Limits resizability overrides to OneStepLite-owned side tasks. */
final class TaskResizeableHooks {
    private TaskResizeableHooks() {}

    static void install(ClassLoader loader, BooleanSupplier rotationActive,
            Predicate<Object> sideTask, Consumer<String> log) {
        try {
            Class<?> taskCls = XposedHelpers.findClass("com.android.server.wm.Task", loader);
            XposedBridge.hookAllMethods(taskCls, "isResizeable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!rotationActive.getAsBoolean()) return;
                    try {
                        if (sideTask.test(p.thisObject)) p.setResult(Boolean.TRUE);
                    } catch (Throwable error) {
                        log.accept("scoped resizeability check skipped: " + error);
                    }
                }
            });
            log.accept("Task#isResizeable override scoped to active OneStepLite side tasks");
        } catch (Throwable error) {
            log.accept("Task#isResizeable hook failed: " + error);
        }
    }
}
