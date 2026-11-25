package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Binder;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Launcher task queries keep native order while hiding only exact passive side leases. */
final class WorkspaceRunningTasksHooks {
    private WorkspaceRunningTasksHooks() {}

    static void install(ClassLoader loader, java.util.function.Supplier<Object> systemContext,
            java.util.function.Supplier<Object> displayContent,
            java.util.function.BooleanSupplier zoomed,
            ThreadLocal<Boolean> launcherTaskQuery,
            java.util.function.Consumer<String> log) {
        try {
            Class<?> running = XposedHelpers.findClass("com.android.server.wm.RunningTasks", loader);
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", loader);
            // The ROM passes the original caller at argument 5, before clearing Binder identity.
            for (java.lang.reflect.Method method : running.getDeclaredMethods()) {
                if (!method.getName().equals("getTasks") || method.getParameterCount() != 7
                        || method.getParameterTypes()[5] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setObjectExtra("onesteplitePreviousTaskQuery", launcherTaskQuery.get());
                        boolean launcher = false;
                        try {
                            Context context = (Context) systemContext.get();
                            String[] packages = context == null ? null : context.getPackageManager()
                                    .getPackagesForUid((Integer) p.args[5]);
                            launcher = packages != null
                                    && java.util.Arrays.asList(packages).contains(Packages.LAUNCHER);
                        } catch (Throwable unavailable) { /* Preserve native queries on lookup failure. */ }
                        launcherTaskQuery.set(zoomed.getAsBoolean() && launcher);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Boolean previous = (Boolean) p.getObjectExtra("onesteplitePreviousTaskQuery");
                        if (previous == null) launcherTaskQuery.remove();
                        else launcherTaskQuery.set(previous);
                    }
                });
            }
            XposedHelpers.findAndHookMethod(running, "accept", taskClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!zoomed.getAsBoolean() || !Boolean.TRUE.equals(launcherTaskQuery.get())) return;
                    try {
                        Object task = p.args[0];
                        if (XposedHelpers.callMethod(task, "getDisplayContent") != displayContent.get()) return;
                        Object lease = DesktopLaunchBridge.runningTaskLease(task);
                        // Launcher also queries this list at native Back DOWN. Hiding the focused
                        // TOP makes it classify the underlying Home and cancel the gesture.
                        if (lease != null && DesktopLaunchBridge.recentsLeaseAlive(lease)
                                && !ImePolicy.isFocusedTopTask(task, displayContent.get(),
                                        DesktopLaunchBridge::ownsTopTask))
                            p.setResult(null);
                    } catch (Throwable unavailable) { /* An uncertain task remains a native candidate. */ }
                }
            });
            log.accept("workspace Launcher running-task scan skips passive leases and retains native-focused TOP before maxNum");
        } catch (Throwable unavailable) { log.accept("workspace running-task hook unavailable: " + unavailable); }
        try {
            Class<?> vendor = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerServiceImpl", loader);
            XposedBridge.hookMethod(vendor.getDeclaredMethod("getTasksUnlock", int.class, int.class),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!zoomed.getAsBoolean() || !Integer.valueOf(0).equals(p.args[1])) return;
                    try {
                        Context context = (Context) systemContext.get();
                        String[] packages = context == null ? null : context.getPackageManager()
                                .getPackagesForUid(Binder.getCallingUid());
                        if (packages == null || !java.util.Arrays.asList(packages)
                                .contains(Packages.LAUNCHER)) return;
                        // OS3's shared task cache bypasses RunningTasks.accept and truncates first.
                        // Native traversal preserves caller permissions and our exact lease filter.
                        Object atms = XposedHelpers.getObjectField(p.thisObject, "mAtmService");
                        Object tasks = XposedHelpers.callMethod(atms, "getTasks",
                                p.args[0], false, false, 0);
                        if (tasks instanceof java.util.List) p.setResult(tasks);
                    } catch (Throwable unavailable) { /* Preserve the OEM query on uncertainty. */ }
                }
            });
            log.accept("workspace Launcher cached-task query uses native filtered default-display traversal");
        } catch (Throwable unavailable) { log.accept("workspace cached-task hook unavailable: " + unavailable); }
    }
}
