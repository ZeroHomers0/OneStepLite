package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps OEM touch-exclusion union in sync with scoped OneStepLite relative layers. */
final class NativeFreeformTouchExclusionHooks {
    private static final ThreadLocal<Object> scanDisplay = new ThreadLocal<>();
    private static Object manager;

    private NativeFreeformTouchExclusionHooks() {}

    static Object manager() {
        return manager;
    }

    static void install(ClassLoader loader, Supplier<ZoomTarget> zoomTarget,
            Supplier<Object> layerAnchor, Supplier<Map<Object, NativeHostLayer>> hostLayers,
            BooleanSupplier zoomed, Consumer<String> log) {
        try {
            Class<?> managerClass = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiFreeFormManagerService", loader);
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", loader);
            XposedHelpers.findAndHookMethod(managerClass, "updateMiuiFreeformTouchExcludeRegion", display,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    manager = p.thisObject;
                    p.setObjectExtra("onesteplitePreviousExcludeDisplay", scanDisplay.get());
                    scanDisplay.set(p.args[0]);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("onesteplitePreviousExcludeDisplay");
                    if (previous == null) scanDisplay.remove();
                    else scanDisplay.set(previous);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
            XposedHelpers.findAndHookMethod(window, "getTouchableRegion", android.graphics.Region.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object currentDisplay = scanDisplay.get();
                    if (currentDisplay == null || p.hasThrowable()) return;
                    ZoomTarget target = zoomTarget.get();
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    NativeHostLayer state = hostLayers.get().get(p.thisObject);
                    try {
                        boolean sameSurfaceAndAnchor = state != null
                                && state.nativeAnchor != null
                                && state.nativeAnchor == layerAnchor.get()
                                && state.surface == XposedHelpers.callMethod(p.thisObject, "getSurfaceControl");
                        if (NativeFreeformPolicy.omitFromTouchExclusion(zoomed.getAsBoolean(),
                                currentDisplay == target.display
                                && currentDisplay == XposedHelpers.callMethod(p.thisObject, "getDisplayContent"),
                                state != null, sameSurfaceAndAnchor)) {
                            // Only OEM's exclusion-union scratch Region changes. Its ordinary
                            // InputDispatcher region and all other windows retain native behavior.
                            ((android.graphics.Region) p.args[0]).setEmpty();
                        }
                    } catch (Throwable error) { log.accept("native touch exclusion skipped: " + error); }
                }
            });
            log.accept("native touch exclusion follows scoped OneStepLite relative layers");
        } catch (Throwable error) { log.accept("native touch exclusion hook unavailable: " + error); }
    }
}
