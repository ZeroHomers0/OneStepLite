package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.Consumer;

/** Reasserts display magnification in the native transition finish transaction. */
final class TransitionMagnificationFinishHooks {
    private TransitionMagnificationFinishHooks() {}

    interface Host {
        ZoomTarget zoomTarget();
        boolean zoomed();
        Map<Object, Object> homeTransitionInfos();
        Map<Object, Object> magnificationFinishReplays();
        void reapplyFinalMagnification(Object display, Object transaction, Object info);
        void layerWorkspaceWithNativeFreeforms(Object display, Object transaction);
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", loader);
            XposedBridge.hookAllMethods(transition, "buildFinishTransaction", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    ZoomTarget target = host.zoomTarget();
                    if (target == null) return;
                    try {
                        Object[] participantDisplays = TransitionMagnificationCompat.finishDisplays(
                                p.thisObject, p.args);
                        if (participantDisplays == null) return;
                        if (HomeTransitionMagnification.hasInfo(p.args[1]))
                            host.homeTransitionInfos().put(p.thisObject, p.args[1]);
                        boolean replayed = TransitionMagnificationReplay.reapplyIfActive(
                                host.zoomed(), target.display, Thread.holdsLock(target.globalLock),
                                participantDisplays, p.args[0],
                                (display, transaction) -> host.reapplyFinalMagnification(
                                        display, transaction, p.args[1]));
                        if (replayed) {
                            // A closing native task can stop normal child-layer assignment.
                            // Detach our windows before its leash becomes hidden, and include
                            // the same restoration in the transition's finish transaction.
                            host.layerWorkspaceWithNativeFreeforms(target.display, p.args[0]);
                            host.magnificationFinishReplays().put(p.thisObject, target.display);
                            log.accept("display magnification reasserted in transition finish");
                        }
                    } catch (Throwable error) {
                        log.accept("transition magnification replay unavailable: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            log.accept("transition magnification finish hook unavailable: " + error);
        }
    }
}
