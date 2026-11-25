package io.github.zerohomers0.onesteplite;

import android.content.res.Configuration;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Preserves TOP Activity layout across native local-configuration relaunches. */
final class FixedRotationRelaunchHooks {
    private FixedRotationRelaunchHooks() {}

    @FunctionalInterface
    interface KeepActivity {
        boolean test(Object activity, int changes, Object previous);
    }

    @FunctionalInterface
    interface PreserveWindow {
        boolean test(Object activity, int changes);
    }

    static void install(ClassLoader loader,
            Supplier<Map<Object, TopRelaunchScope>> relaunchScopes,
            KeepActivity keepActivity, PreserveWindow preserveWindow,
            Consumer<String> log) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", loader);
            XposedBridge.hookAllMethods(activityRecord, "shouldRelaunchLocked",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !Boolean.TRUE.equals(p.getResult())
                            || p.args.length != 2 || !(p.args[0] instanceof Integer)
                            || !(p.args[1] instanceof Configuration)) return;
                    try {
                        TopRelaunchScope scope = relaunchScopes.get().get(p.thisObject);
                        if (scope == null || !keepActivity.test(
                                p.thisObject, (Integer) p.args[0], p.args[1])) return;
                        scope.previous = new Configuration((Configuration) p.args[1]);
                    } catch (Throwable error) {
                        log.accept("TOP rotation relaunch guard unavailable: " + error);
                    }
                }
            });
            XposedBridge.hookAllMethods(activityRecord, "updateReportedConfigurationAndSend",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("onesteplite.previousRelaunchScope",
                            relaunchScopes.get().put(p.thisObject, new TopRelaunchScope()));
                    p.setObjectExtra("onesteplite.relaunchScope", Boolean.TRUE);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra("onesteplite.relaunchScope"))) return;
                    TopRelaunchScope previous =
                            (TopRelaunchScope) p.getObjectExtra("onesteplite.previousRelaunchScope");
                    if (previous == null) relaunchScopes.get().remove(p.thisObject);
                    else relaunchScopes.get().put(p.thisObject, previous);
                }
            });
            XposedBridge.hookAllMethods(activityRecord, "relaunchActivityLocked",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 2 || !(p.args[1] instanceof Integer)) return;
                    try {
                        if (preserveWindow.test(p.thisObject, (Integer) p.args[1])) {
                            p.args[0] = true;
                            log.accept("TOP rebuilds Activity layout with its real Window preserved");
                        }
                    } catch (Throwable error) {
                        log.accept("TOP preserved Window relaunch unavailable: " + error);
                    }
                }
            });
            XposedBridge.hookAllMethods(activityRecord, "startFreezingScreenLocked",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                    try {
                        if (preserveWindow.test(p.thisObject, (Integer) p.args[0]))
                            p.setResult(null);
                    } catch (Throwable error) {
                        log.accept("TOP local configuration freeze check unavailable: " + error);
                    }
                }
            });
            log.accept("TOP owned rotation rebuilds layouts while preserving the real Window");
        } catch (Throwable error) {
            log.accept("TOP rotation relaunch hook unavailable: " + error);
        }
    }
}
