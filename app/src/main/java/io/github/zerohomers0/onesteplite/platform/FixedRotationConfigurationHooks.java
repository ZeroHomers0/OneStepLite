package io.github.zerohomers0.onesteplite;

import android.content.res.Configuration;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps Activity overrides aligned with the workspace-owned TOP and side viewports. */
final class FixedRotationConfigurationHooks {
    private FixedRotationConfigurationHooks() {}

    @FunctionalInterface
    interface ConfigurationResolver {
        Configuration resolve(Object activity, Object task) throws Throwable;
    }

    static void install(ClassLoader loader, Supplier<Object> displayContent,
            BooleanSupplier zoomed, BooleanSupplier rotationActive,
            ConfigurationResolver ownedSideConfiguration,
            ConfigurationResolver ownedTopConfiguration,
            Consumer<String> log) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", loader);
            XposedBridge.hookAllMethods(activityRecord, "resolveOverrideConfiguration",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object display = displayContent.get();
                    if (p.hasThrowable() || !zoomed.getAsBoolean()
                            || !rotationActive.getAsBoolean() || p.args.length != 1
                            || !(p.args[0] instanceof Configuration)) return;
                    try {
                        if (XposedHelpers.getObjectField(p.thisObject, "mDisplayContent") != display)
                            return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        Configuration sideLocal =
                                ownedSideConfiguration.resolve(p.thisObject, task);
                        if (SideConfiguration.apply(p.thisObject, task, sideLocal)) return;
                        Configuration local =
                                ownedTopConfiguration.resolve(p.thisObject, task);
                        if (TopConfiguration.applyRotated(p.thisObject, task, local)) return;
                        TopConfiguration.apply(p.thisObject, task, (Configuration) p.args[0]);
                    } catch (Throwable error) {
                        log.accept("upper Activity configuration unavailable: " + error);
                    }
                }
            });
            log.accept("upper Activity configuration follows full task viewport");
        } catch (Throwable error) {
            log.accept("upper Activity configuration hook unavailable: " + error);
        }
    }
}
