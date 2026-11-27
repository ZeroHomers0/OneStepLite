package io.github.zerohomers0.onesteplite;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Registers native gesture-monitor surfaces so their transform follows the main region. */
final class NavigationGestureSurfaceHooks {
    private NavigationGestureSurfaceHooks() {}

    interface Host {
        Context systemContext();
        Map<Object, String> gestureSurfaces();
        ZoomTarget zoomTarget();
        float zoomScale();
        float zoomOffsetX();
        float zoomOffsetY();
        void applyGestureSurfaces(ZoomTarget target, float scale, float offsetY, float offsetX);
    }

    static void install(ClassLoader loader, Host host, Consumer<String> log) {
        try {
            Class<?> spy = XposedHelpers.findClass(
                    "com.android.server.input.GestureMonitorSpyWindow", loader);
            XposedBridge.hookAllConstructors(spy, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length != 7 || !Integer.valueOf(0).equals(p.args[2])) return;
                    String name = String.valueOf(p.args[1]);
                    try {
                        Context context = host.systemContext();
                        String[] packages = context == null ? null
                                : context.getPackageManager().getPackagesForUid((Integer) p.args[4]);
                        String kind = NativeFreeformPolicy.gestureKind((Integer) p.args[2],
                                name, (Integer) p.args[4], packages);
                        if (kind == null) return;
                        Object surface = XposedHelpers.getObjectField(
                                p.thisObject, "mInputSurface");
                        synchronized (host.gestureSurfaces()) {
                            host.gestureSurfaces().put(surface, kind);
                        }
                        ZoomTarget target = host.zoomTarget();
                        if (target != null) target.handler.post(() -> {
                            synchronized (target.globalLock) {
                                if (host.zoomTarget() == target)
                                    host.applyGestureSurfaces(target,
                                            host.zoomScale(), host.zoomOffsetY(), host.zoomOffsetX());
                            }
                        });
                        log.accept("captured gesture monitor name=" + name + " pid=" + p.args[3]
                                + " uid=" + p.args[4] + " packages="
                                + (packages == null ? "unknown" : java.util.Arrays.toString(packages)));
                        if ("return".equals(kind)) {
                            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                            StringBuilder callers = new StringBuilder();
                            for (int i = 1; i < frames.length && i < 9; i++) {
                                if (callers.length() > 0) callers.append(" <- ");
                                callers.append(frames[i].getClassName()).append('.')
                                        .append(frames[i].getMethodName());
                            }
                            log.accept("return gesture monitor creator " + callers);
                        }
                    } catch (Throwable error) {
                        log.accept("navigation surface admission skipped: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            log.accept("navigation input geometry unavailable: " + error);
        }
    }
}
