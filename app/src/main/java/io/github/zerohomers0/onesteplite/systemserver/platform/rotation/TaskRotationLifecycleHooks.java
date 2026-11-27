package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;

/** Keeps token rotation local while native WM evaluates and commits orientation changes. */
final class TaskRotationLifecycleHooks {
    private TaskRotationLifecycleHooks() {}

    interface Policy {
        Boolean applyFixedRotation(Object display, Object activity, String source);
        void beforeApplicationOrientationRequest(Object activity, int request);
        boolean updatingTaskRotation();
        Map<Object, Integer> taskRotations();
        boolean rotationActive();
        Object displayContent();
        int heldRotation();
    }

    interface Actions {
        boolean retainTaskRotationOnNativeFinish(Object activity);
        void setTaskRotationResolver(Method resolver);
        void queueWorkspaceRotationRefresh(Object displayRotation);
        void evaluateNativeLandscape(Object display, String source);
        void refreshTaskRotation(Object display);
    }

    interface Host {
        Policy policy();
        Actions actions();
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        installActivityLaunch(loader,host,log);
        installRequestedOrientation(loader,host,log);
        installConfigurationChanged(loader,host,log);
        installDisplayLaunchExclusion(loader,host,log);
        installLifetime(loader,host,log);
        installDisplayRotation(loader,host,log);
    }
    private static void installActivityLaunch(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", loader);
            XposedBridge.hookAllMethods(displayContent,
                    "handleTopActivityLaunchingInDifferentOrientation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || p.args[0] == null) return;
                    Boolean decision = host.policy().applyFixedRotation(
                            p.thisObject, p.args[0], "activity-orientation");
                    if (decision != null) p.setResult(decision);
                }
            });
            log.accept("task rotation: activity path intercepted before display launch");
        } catch (Throwable error) {
            log.accept("fixed rotation top-activity hook unavailable: " + error);
        }

    }
    private static void installRequestedOrientation(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", loader);
            XposedBridge.hookAllMethods(activityRecord, "setRequestedOrientation",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Integer) {
                        host.policy().beforeApplicationOrientationRequest(
                                p.thisObject, (Integer) p.args[0]);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!host.policy().rotationActive() || p.thisObject == null) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        // This path also covers non-top OneStepLite side tasks. The stock
                        // DisplayContent hook only sees the current top activity.
                        host.policy().applyFixedRotation(
                                display, p.thisObject, "activity-requested-orientation");
                    } catch (Throwable error) {
                        log.accept("task rotation requested-orientation refresh failed: " + error);
                    }
                }
            });
            log.accept("task rotation: ActivityRecord requests hooked for side tasks");
        } catch (Throwable error) {
            log.accept("fixed rotation ActivityRecord hook unavailable: " + error);
        }

    }
    private static void installConfigurationChanged(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", loader);
            XposedBridge.hookAllMethods(activityRecord, "onConfigurationChanged",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!host.policy().rotationActive() || p.thisObject == null
                            || host.policy().updatingTaskRotation()) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        host.policy().applyFixedRotation(
                                display, p.thisObject, "activity-configuration-changed");
                    } catch (Throwable error) {
                        log.accept("task rotation configuration refresh failed: " + error);
                    }
                }
            });
            log.accept("task rotation: configuration changes re-evaluate token orientation");
        } catch (Throwable error) {
            log.accept("fixed rotation configuration hook unavailable: " + error);
        }

    }
    private static void installDisplayLaunchExclusion(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", loader);
            for (String name : new String[]{"setFixedRotationLaunchingApp",
                    "setFixedRotationLaunchingAppUnchecked"}) {
                XposedBridge.hookAllMethods(displayContent, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (host.policy().rotationActive() && p.thisObject == host.policy().displayContent()
                                && p.args.length > 0 && p.args[0] != null
                                && host.policy().taskRotations().containsKey(p.args[0])) {
                            // OEM promotion paths also try to register an already transformed
                            // token as a display launch. Keep our transform local to that token.
                            p.setResult(null);
                        }
                    }
                });
            }
            log.accept("task rotation: owned tokens excluded from display launch notifications");
        } catch (Throwable error) {
            log.accept("task rotation display-launch exclusion unavailable: " + error);
        }

    }
    private static void installLifetime(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> windowToken = XposedHelpers.findClass(
                    "com.android.server.wm.WindowToken", loader);
            XposedBridge.hookAllMethods(windowToken, "finishFixedRotationTransform",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // A non-null action is a real display rotation, not cancellation.
                    if (p.args.length > 1 || p.args.length == 1 && p.args[0] != null) return;
                    if (host.actions().retainTaskRotationOnNativeFinish(p.thisObject)) p.setResult(null);
                }
            });
            log.accept("task rotation: owned token transform retained across native transition finish");
        } catch (Throwable error) {
            log.accept("task rotation lifetime hook unavailable: " + error);
        }

    }
    private static void installDisplayRotation(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> displayRotation = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", loader);
            Method resolver = XposedHelpers.findMethodExact(displayRotation,
                    "rotationForOrientation", int.class, int.class);
            host.actions().setTaskRotationResolver(resolver);
            XposedBridge.hookMethod(resolver, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        int displayId = (Integer) XposedHelpers.callMethod(display, "getDisplayId");
                        if (FullscreenRotationPolicy.shouldHoldDisplay(
                                host.policy().rotationActive(), displayId)
                                && display == host.policy().displayContent()) {
                            p.setResult(host.policy().heldRotation());
                        }
                    } catch (Throwable error) {
                        log.accept("fixed rotation display hold skipped: " + error);
                    }
                }
            });
            log.accept("fixed rotation: display 0 hold hooked");
            try {
                XposedHelpers.findAndHookMethod(displayRotation, "dispatchProposedRotation", int.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    host.actions().queueWorkspaceRotationRefresh(p.thisObject);
                }
                });
            } catch (Throwable unavailable) {
                log.accept("workspace direction proposal unavailable: " + unavailable);
            }
            XposedBridge.hookAllMethods(displayRotation, "updateRotationUnchecked",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (host.policy().updatingTaskRotation()) return;
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        if (display != host.policy().displayContent()) return;
                        host.actions().evaluateNativeLandscape(display, "display rotation updated");
                        if (!host.policy().rotationActive()) return;
                        host.actions().refreshTaskRotation(display);
                    } catch (Throwable error) {
                        log.accept("task rotation policy refresh failed: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            log.accept("fixed rotation display hold unavailable: " + error);
        }

    }

}
