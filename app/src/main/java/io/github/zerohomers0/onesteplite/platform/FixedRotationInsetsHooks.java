package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reports the native IME and insets against the workspace-owned TOP viewport. */
final class FixedRotationInsetsHooks {
    private FixedRotationInsetsHooks() {}

    static void install(ClassLoader loader, TopSystemBars bars,
            Supplier<Object> displayContent, BooleanSupplier zoomed,
            BooleanSupplier rotationActive, Consumer<String> log) {
        try {
            Class<?> policy = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", loader);
            XposedBridge.hookAllMethods(policy, "adjustInsetsForWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (p.hasThrowable() || p.args.length != 3 || !zoomed.getAsBoolean()
                            || !rotationActive.getAsBoolean() || display == null) return;
                    try {
                        Object original = p.getResult();
                        Object adjusted = TopInsets.adjust(p.args[0], display,
                                original, DesktopLaunchBridge::ownsTopViewport);
                        if (adjusted != original) bars.remember(p.args[0], display);
                        p.setResult(adjusted);
                    } catch (Throwable unavailable) {
                        log.accept("upper insets viewport unavailable: " + unavailable);
                    }
                }
            });
            XposedBridge.hookAllMethods(policy, "getInsetsForWindowMetrics", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (p.hasThrowable() || p.args.length != 2 || !zoomed.getAsBoolean()
                            || !rotationActive.getAsBoolean() || display == null
                            || p.args[0] == null) return;
                    try {
                        Object adjusted = TopInsets.adjustMetrics(
                                p.args[0], display, p.args[1], DesktopLaunchBridge::ownsTopViewport);
                        if (adjusted != p.args[1]) {
                            bars.remember(p.args[0], display);
                            XposedHelpers.callMethod(p.args[1], "set", adjusted, true);
                        }
                    } catch (Throwable unavailable) {
                        log.accept("upper metrics insets unavailable: " + unavailable);
                    }
                }
            });
            log.accept("upper leased viewport insets installed; native IME retained");
        } catch (Throwable unavailable) {
            log.accept("upper viewport insets hook unavailable: " + unavailable);
        }
    }
}
