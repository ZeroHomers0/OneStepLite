package io.github.zerohomers0.onesteplite;

import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Scopes native IME and system-window magnification to the active workspace display. */
final class InputWindowMagnificationHooks {
    private InputWindowMagnificationHooks() {}

    static void install(ClassLoader loader, BooleanSupplier zoomed, Supplier<Object> displayContent, LongSupplier shadeGeneration, LongSupplier shadeUntil, Consumer<String> log) {
        installMagnificationPolicy(loader, zoomed, displayContent, shadeGeneration, shadeUntil, log);
        installImeTargeting(loader, zoomed, displayContent, log);
    }
    private static void installMagnificationPolicy(ClassLoader loader, BooleanSupplier zoomed, Supplier<Object> displayContent, LongSupplier shadeGeneration, LongSupplier shadeUntil, Consumer<String> log) {
        try {
            Class<?> window = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", loader);
            XposedHelpers.findAndHookMethod(window, "shouldMagnify", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (!zoomed.getAsBoolean() || display == null) return;
                    try {
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != display)
                            return;
                        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(p.thisObject, "mAttrs");
                        if (shadeGeneration.getAsLong() != 0
                                && android.os.SystemClock.uptimeMillis() < shadeUntil.getAsLong()
                                && attrs.type == WindowTypes.NOTIFICATION_SHADE
                                && Packages.SYSTEM_UI.equals(attrs.packageName)
                                && "NotificationShade".contentEquals(attrs.getTitle())) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        if (ImeViewport.imeWindow(p.thisObject)
                                && ImeViewport.target(display,
                                        DesktopLaunchBridge::ownsTopTask) != null) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        if (InputMagnificationPolicy.shouldExcludeFromMagnification(attrs.type,
                                attrs.packageName, attrs.getTitle())) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        // Native freeforms inherit the same WM parent transform as main apps.
                        // Their canonical leash retains the OEM's own mini/freeform scale.
                        int flags = XposedHelpers.getIntField(attrs, "privateFlags");
                        // Native gesture windows keep the ROM's own magnification policy.
                        if (InputMagnificationPolicy.shouldForceMagnification(attrs.type, flags,
                                attrs.packageName, attrs.getTitle()))
                            p.setResult(Boolean.TRUE);
                    } catch (Throwable error) {
                        log.accept("input UI magnification skipped: " + error);
                    }
                }
            });
            log.accept("scoped IME/navigation magnification admitted on default display");
        } catch (Throwable error) {
            log.accept("input UI magnification unavailable: " + error);
        }

    }
    private static void installImeTargeting(ClassLoader loader, BooleanSupplier zoomed, Supplier<Object> displayContent, Consumer<String> log) {
        try {
            Class<?> window = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", loader);
            XposedHelpers.findAndHookMethod(window, ShellCompat.hasMethod(
                            window, "canBeImeLayeringTarget", 0)
                            ? "canBeImeLayeringTarget" : "canBeImeTarget",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (!zoomed.getAsBoolean() || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        if (ImeViewport.skipOtherTask(p.thisObject, display,
                                DesktopLaunchBridge::ownsTopTask)) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (task != null
                                && Integer.valueOf(6).equals(XposedHelpers.callMethod(
                                        task, "getWindowingMode"))
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(
                                        task, "isForceTranslucent"))
                                && !ImePolicy.isFocusedTopWindow(p.thisObject, display,
                                        DesktopLaunchBridge::ownsTopTask)) {
                            p.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable error) {
                        log.accept("side IME exclusion skipped: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(window, "shouldControlIme", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!zoomed.getAsBoolean() || !Boolean.FALSE.equals(p.getResult())) return;
                    try {
                        if (ImePolicy.isFocusedTopWindow(p.thisObject, displayContent.get(),
                                DesktopLaunchBridge::ownsTopTask)
                                && DesktopLaunchBridge.ownsTopTask(
                                        XposedHelpers.callMethod(p.thisObject, "getTask")))
                            p.setResult(Boolean.TRUE);
                    } catch (Throwable unavailable) {
                        log.accept("TOP IME control skipped: " + unavailable);
                    }
                }
            });
            log.accept("passive side TaskViews excluded from IME layering; focused TOP keeps native eligibility");
        } catch (Throwable error) {
            log.accept("side IME exclusion unavailable: " + error);
        }

    }

}
