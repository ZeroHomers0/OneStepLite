package io.github.zerohomers0.onesteplite;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Publishes confirmed TOP/main focus changes to native task listeners. */
final class TopFocusHooks {
    private TopFocusHooks() {}

    static void install(ClassLoader loader, Supplier<ZoomTarget> zoomTarget,
            Supplier<Object> displayContent, BooleanSupplier zoomed,
            TopFocusNotification notification, Consumer<String> log) {
        try {
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", loader);
            XposedBridge.hookAllMethods(display, "updateFocusedWindowLocked", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target=zoomTarget.get();
                    if (p.hasThrowable() || target==null || p.thisObject!=target.display
                            || target.display!=displayContent.get()
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        if (notification.reconcile(target.display, DesktopLaunchBridge::ownsTopTask,
                                () -> zoomed.getAsBoolean() && target==zoomTarget.get()
                                        && target.display==displayContent.get()))
                            log.accept("workspace real TOP/main focus synchronized native front-task notification");
                    } catch (Throwable unavailable) {
                        log.accept("TOP front notification unavailable: " + unavailable);
                    }
                }
            });
            Class<?> controller=XposedHelpers.findClass(
                    "com.android.server.wm.TaskChangeNotificationController", loader);
            XposedBridge.hookAllMethods(controller, "notifyTaskMovedToFront", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length!=1) return;
                    try { notification.observed(displayContent.get(), p.args[0]); }
                    catch (Throwable unavailable) {
                        log.accept("native front notification observation unavailable: " + unavailable);
                    }
                }
            });
            log.accept("workspace native task front follows confirmed TOP/main focus changes");
        } catch (Throwable unavailable) {
            log.accept("TOP front notification hook unavailable: " + unavailable);
        }
    }
}
