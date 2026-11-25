package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Keeps native focus selection on exact side-task leases while preserving TOP fallback. */
final class WorkspaceTaskFocusHooks {
    private WorkspaceTaskFocusHooks() {}

    static void install(ClassLoader loader, BooleanSupplier zoomed,
            Supplier<Object> displayContent, ThreadLocal<Boolean> readingFocus,
            Predicate<Object> upperLandscapeTask, Consumer<String> log) {
        try {
            Class<?> area = XposedHelpers.findClass(
                    "com.android.server.wm.TaskDisplayArea", loader);
            XC_MethodHook selection = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (!zoomed.getAsBoolean() || p.hasThrowable() || p.getResult() == null
                            || Boolean.TRUE.equals(readingFocus.get())) return;
                    readingFocus.set(Boolean.TRUE);
                    try {
                        Object original = p.getResult();
                        if (ImePolicy.isTopAppFocused(original, display)) return;
                        Object lease = DesktopLaunchBridge.recentsLease(original);
                        boolean upperFallback = "getNextFocusableRootTask".equals(p.method.getName())
                                && upperLandscapeTask.test(original);
                        if ((!upperFallback
                                && (lease == null || !DesktopLaunchBridge.recentsLeaseAlive(lease)))
                                || XposedHelpers.callMethod(original, "getDisplayContent") != display)
                            return;
                        Object ignored = p.args.length >= 2 && Boolean.TRUE.equals(p.args[1])
                                ? p.args[0] : null;
                        boolean excludeMode = p.args.length == 4 && Boolean.TRUE.equals(p.args[2]);
                        int mode = excludeMode ? (Integer) p.args[3] : 0;
                        Object target = WorkspaceFocus.next(
                                p.thisObject, ignored, excludeMode, mode, task -> {
                                    Object binding = DesktopLaunchBridge.recentsLease(task);
                                    return !ImePolicy.isTopAppFocused(task, display)
                                            && (upperLandscapeTask.test(task)
                                            || binding != null
                                            && DesktopLaunchBridge.recentsLeaseAlive(binding));
                                });
                        // No replacement means WM keeps its native fallback; never return a hidden task.
                        if (target != null) p.setResult(target);
                    } catch (Throwable unavailable) {
                        log.accept("workspace focus selection unavailable: " + unavailable);
                    } finally {
                        readingFocus.remove();
                    }
                }
            };
            XposedBridge.hookAllMethods(area, "getFocusedRootTask", selection);
            XposedBridge.hookAllMethods(area, "getNextFocusableRootTask", selection);
            log.accept("workspace native focus selection skips exact side-task leases");
        } catch (Throwable unavailable) {
            log.accept("workspace focus hook unavailable: " + unavailable);
        }
    }
}
