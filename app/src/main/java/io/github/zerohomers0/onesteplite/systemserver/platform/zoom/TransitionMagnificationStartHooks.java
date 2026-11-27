package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.function.Consumer;

/** Transfers the current display transform into native transition start transactions. */
final class TransitionMagnificationStartHooks {
    private TransitionMagnificationStartHooks() {}

    interface Host {
        boolean zoomed();
        ZoomTarget zoomTarget();
        Object displayContent();
        NativeFreeformStartGate nativeFreeformStart();
        void finishNativeFreeformStart(ZoomTarget target, Object start);
        void reapplyFinalMagnification(Object display, Object transaction, Object info);
        float zoomScale();
        float zoomOffsetX();
        float zoomOffsetY();
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", loader);
            XposedBridge.hookAllMethods(transition, "calculateTransitionInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    handleTransitionInfo(host,log,p);
                }
            });
        } catch (Throwable error) {
            log.accept("transition magnification start hook unavailable: " + error);
        }
    }

    private static void handleTransitionInfo(Host host,Consumer<String> log,XC_MethodHook.MethodHookParam p) {
                    ZoomTarget target = host.zoomTarget();
                    if (p.hasThrowable() || !host.zoomed() || target == null
                            || target.display != host.displayContent()
                            || !Thread.holdsLock(target.globalLock)
                            || !TransitionMagnificationCompat.isStart(p.args)) return;
                    try {
                        if (NativeFreeformReturn.change(p.getResult()) != null) {
                            Object nativeTask = null;
                            for (Object change : (java.util.List<?>) p.args[2]) {
                                Object container = XposedHelpers.getObjectField(change, "mContainer");
                                Object task = XposedHelpers.callMethod(container, "asTask");
                                if (task != null && XposedHelpers.getIntField(change, "mWindowingMode") == 5
                                        && Integer.valueOf(1).equals(
                                                XposedHelpers.callMethod(task, "getWindowingMode"))
                                        && XposedHelpers.callMethod(task, "getDisplayContent") == target.display)
                                    nativeTask = task;
                            }
                            Object start = p.args[3];
                            NativeFreeformStartGate gate = host.nativeFreeformStart();
                            if (nativeTask == null || !gate.begin(target.display, start)) return;
                            try {
                                ((android.view.SurfaceControl.Transaction) start).addTransactionCommittedListener(
                                        command -> target.handler.post(command),
                                        () -> host.finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markNativePrepared(nativeTask))
                                    throw new IllegalStateException("native return source identity changed");
                                host.reapplyFinalMagnification(target.display, start, null);
                                if (!HomeTransitionMagnification.prepare(
                                        p.getResult(), (java.util.List<?>) p.args[2],
                                        target.display, start, host.zoomScale(),
                                        host.zoomOffsetX(), host.zoomOffsetY()))
                                    throw new IllegalStateException("native return Root scene changed");
                                log.accept("native return Root/start magnification transferred atomically");
                            } catch (Throwable error) {
                                gate.finish(target.display, start);
                                throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            if (XposedHelpers.getIntField(change, "mWindowingMode") != 1
                                    || !Boolean.TRUE.equals(
                                            XposedHelpers.getObjectField(change, "mVisible"))) continue;
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null
                                    || XposedHelpers.callMethod(task, "getDisplayContent") != target.display
                                    || ((Number) XposedHelpers.callMethod(
                                            task, "getWindowingMode")).intValue() != 5) continue;
                            Object start = p.args[3];
                            NativeFreeformStartGate gate = host.nativeFreeformStart();
                            if (!gate.begin(target.display, start)) return;
                            try {
                                android.view.SurfaceControl.Transaction tx =
                                        (android.view.SurfaceControl.Transaction) start;
                                tx.addTransactionCommittedListener(
                                        command -> target.handler.post(command),
                                        () -> host.finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markPrepared(task))
                                    throw new IllegalStateException("native fullscreen source identity changed");
                                host.reapplyFinalMagnification(target.display, start, null);
                                log.accept("native freeform main transform transferred in start transaction");
                            } catch (Throwable error) {
                                gate.finish(target.display, start);
                                throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null) continue;
                            int transitMode = (Integer) XposedHelpers.callMethod(
                                    change, "getTransitMode", container);
                            if (!MainLaunchMagnification.shouldApply(host.zoomed(), target.display,
                                    XposedHelpers.callMethod(task, "getDisplayContent"),
                                    XposedHelpers.callMethod(task, "getRootTask") == task,
                                    Boolean.TRUE.equals(XposedHelpers.callMethod(
                                            task, "isVisibleRequested")),
                                    (Integer) XposedHelpers.callMethod(task, "getWindowingMode"),
                                    transitMode)) continue;
                            host.reapplyFinalMagnification(target.display, p.args[3], null);
                            if (HomeTransitionMagnification.prepare(
                                    p.getResult(), (java.util.List<?>) p.args[2],
                                    target.display, p.args[3], host.zoomScale(),
                                    host.zoomOffsetX(), host.zoomOffsetY()))
                                log.accept("Home transition root retains workspace transform");
                            log.accept("main launch magnification included in transition start task="
                                    + XposedHelpers.getIntField(task, "mTaskId")
                                    + " transit=" + transitMode);
                            return;
                        }
                    } catch (Throwable error) {
                        log.accept("transition start magnification skipped: " + error);
                    }

    }
}
