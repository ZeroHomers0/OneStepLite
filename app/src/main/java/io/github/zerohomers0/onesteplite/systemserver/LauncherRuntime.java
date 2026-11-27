package io.github.zerohomers0.onesteplite;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import static io.github.zerohomers0.onesteplite.HostEntry.*;

/** Desktop launcher intents are acknowledged once and forwarded to the workspace host. */
final class LauncherRuntime {
    private LauncherRuntime() {}

    static volatile String sLastDesktopLaunchPackage;
    static volatile int sLastDesktopLaunchUser = -1;
    static volatile long sLastDesktopLaunchAt;

    static void installDesktopIconLaunchBridge(ClassLoader cl) {
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", cl);
            int hooked = 0;
            for (java.lang.reflect.Method method : atms.getDeclaredMethods()) {
                String name = method.getName();
                final boolean fromRecents = name.equals("startActivityFromRecents");
                if (!(fromRecents || name.equals("startActivity") || name.equals("startActivityAsUser")
                        || name.equals("startActivityAsUserWithFeature"))) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        boolean handled = fromRecents
                                ? DesktopLaunchBridge.beforeRecents((Context) sSystemContext, p.thisObject, p.args)
                                : DesktopLaunchBridge.before((Context) sSystemContext, p.thisObject, p.args,
                                        desktopLaunchUser(method, p.args));
                        if (handled) {
                            p.setObjectExtra("onestepliteHostLaunch", true);
                            p.setResult(2); // ActivityManager.START_TASK_TO_FRONT
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (fromRecents || Boolean.TRUE.equals(p.getObjectExtra("onestepliteHostLaunch"))) return;
                        if (p.getThrowable() != null) return;
                        Object result = p.getResult();
                        if (result instanceof Integer && (Integer) result < 0) return;
                        forwardDesktopIconLaunch(p.args, desktopLaunchUser(method, p.args));
                    }
                });
                hooked++;
            }
            log("desktop icon launch bridge hooked ATMS methods=" + hooked);
        } catch (Throwable error) {
            log("desktop icon launch bridge unavailable: " + error);
        }
    }

    static int desktopLaunchUser(java.lang.reflect.Method method, Object[] args) {
        int callerUser = (Integer) XposedHelpers.callStaticMethod(android.os.UserHandle.class,
                "getUserId", android.os.Binder.getCallingUid());
        return DesktopIconLaunchPolicy.launchUser(method.getName(), method.getParameterTypes(), args, callerUser);
    }

    static void forwardDesktopIconLaunch(Object[] args, int launchUser) {
        if (args == null || launchUser < 0) return;
        String callerPackage = null;
        android.content.Intent launch = null;
        for (Object arg : args) {
            if (arg instanceof String && Packages.LAUNCHER.equals(arg)) {
                callerPackage = (String) arg;
            } else if (arg instanceof android.content.Intent) {
                launch = (android.content.Intent) arg;
            }
        }
        String pkg = DesktopIconLaunchPolicy.packageForLauncherEntry(
                callerPackage,
                launch == null ? null : launch.getAction(),
                launch != null && launch.hasCategory(android.content.Intent.CATEGORY_LAUNCHER),
                launch == null || launch.getComponent() == null
                        ? null : launch.getComponent().getPackageName());
        if (pkg == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (HostEntry.class) {
            if (pkg.equals(sLastDesktopLaunchPackage) && launchUser == sLastDesktopLaunchUser
                    && now - sLastDesktopLaunchAt < 350L) return;
            sLastDesktopLaunchPackage = pkg;
            sLastDesktopLaunchUser = launchUser;
            sLastDesktopLaunchAt = now;
        }
        try {
            Context context = (Context) sSystemContext;
            if (context == null) return;
            android.content.Intent signal = new android.content.Intent(DESKTOP_ICON_LAUNCH_ACTION)
                    .setPackage(SYSTEMUI)
                    .putExtra("package", pkg).putExtra("userId", launchUser)
                    .putExtra("component", launch.getComponent());
            context.sendBroadcast(signal);
            log("desktop icon launch forwarded package=" + pkg + " user=" + launchUser);
        } catch (Throwable error) {
            log("desktop icon launch forward failed: " + error);
        }
    }
}
