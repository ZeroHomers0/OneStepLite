package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.Consumer;

/** Tracks WM magnification surface identities without taking state ownership from the host. */
final class MagnificationSurfaceTrackingHooks {
    private MagnificationSurfaceTrackingHooks() {}

    interface State {
        boolean zoomed();
        Object displayContent();
        ZoomTarget zoomTarget();
        boolean finalSnapshot();
        Object finishInfo();
    }

    interface Surfaces {
        Object routeNativeFreeform(Object container, Object display, Object transaction,
                boolean finalSnapshot);
        Object routeLiveSwap(Object container, Object display, Object transaction,
                boolean finalSnapshot);
        Map<Object, Object> trackedSurfaces();
        void remember(Object container, Object spec);
        void stagePassBlur(Object display, Object transaction, Object spec);
    }

    interface Host {
        State state();
        Surfaces surfaces();
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> container = XposedHelpers.findClass(
                    "com.android.server.wm.WindowContainer", loader);
            Class<?> transaction = XposedHelpers.findClass(
                    "android.view.SurfaceControl$Transaction", loader);
            Class<?> spec = XposedHelpers.findClass("android.view.MagnificationSpec", loader);
            XposedHelpers.findAndHookMethod(container, "applyMagnificationSpec", transaction,
                    spec, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = host.state().zoomTarget();
                    if (!host.state().zoomed() || target == null || target.display != host.state().displayContent()
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        boolean finalSnapshot = host.state().finalSnapshot();
                        boolean homeFinish = finalSnapshot && HomeTransitionMagnification.hasInfo(
                                host.state().finishInfo());
                        if (!homeFinish && HomeTransitionMagnification.owns(
                                p.thisObject, target.display)) {
                            p.setObjectExtra("onesteplite.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        if (SideMagnification.ownsGeometry(p.thisObject, target.display)) {
                            p.setObjectExtra("onesteplite.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        p.args[0] = host.surfaces().routeNativeFreeform(
                                p.thisObject, target.display, p.args[0], host.state().finalSnapshot());
                        p.args[0] = host.surfaces().routeLiveSwap(
                                p.thisObject, target.display, p.args[0], host.state().finalSnapshot());
                        Map<Object, Object> tracked = host.surfaces().trackedSurfaces();
                        if (!homeFinish && HomeTransitionMagnification.activeOn(target.display)) {
                            tracked = new java.util.IdentityHashMap<>(tracked);
                            tracked.entrySet().removeIf(entry ->
                                    HomeTransitionMagnification.owns(entry.getKey(), target.display));
                        }
                        MagnificationTree.prepare(p.thisObject, target.display,
                                p.args[0], p.args[1], tracked);
                    } catch (Throwable error) {
                        log.accept("magnification replay surface cleanup skipped: " + error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()
                            && !Boolean.TRUE.equals(p.getObjectExtra("onesteplite.sideMagnification")))
                        host.surfaces().remember(p.thisObject, p.args[1]);
                    if (!p.hasThrowable()) host.surfaces().stagePassBlur(p.thisObject, p.args[0], p.args[1]);
                }
            });
            XposedHelpers.findAndHookMethod(container, "clearMagnificationSpec", transaction,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = host.state().zoomTarget();
                    if (!host.state().zoomed() || target == null || target.display != host.state().displayContent()
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        // Native clear recurses before apply. Skipping apply alone lets
                        // an older WM spec reset the TaskView leash to identity first.
                        boolean homeFinish = host.state().finalSnapshot()
                                && HomeTransitionMagnification.hasInfo(host.state().finishInfo());
                        if (!homeFinish
                                && HomeTransitionMagnification.owns(p.thisObject, target.display)) {
                            p.setResult(null);
                            return;
                        }
                        if (SideMagnification.ownsGeometry(p.thisObject, target.display))
                            p.setResult(null);
                    } catch (Throwable error) {
                        log.accept("side magnification clear guard unavailable: " + error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) host.surfaces().stagePassBlur(p.thisObject, p.args[0], null);
                }
            });
            log.accept("WM magnification surface identities tracked for exit reconciliation");
        } catch (Throwable error) {
            log.accept("WM magnification surface tracking unavailable: " + error);
        }
    }
}
