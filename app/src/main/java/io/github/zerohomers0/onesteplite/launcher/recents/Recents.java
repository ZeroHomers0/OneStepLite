package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Preserve native recents filters, except TaskView's topmost flag for our exact live tasks. */
final class Recents {
    private static final ThreadLocal<Query> QUERY = new ThreadLocal<>();
    private static final class Query {
        final Object task, lease;
        Query(Object task, Object lease) { this.task = task; this.lease = lease; }
    }
    private static final String SAVED = "onesteplite.recents.previous";
    private static final String ENTERED = "onesteplite.recents.entered";
    private static java.util.Set<android.os.IBinder> hiddenTasks = java.util.Collections.emptySet();

    /** Invalidate Launcher caches when a preference or an exact hidden-task set changes. */
    static void registryChanged(Object wms) {
        java.util.Set<android.os.IBinder> next=DesktopLaunchBridge.hiddenRecentsTasks();
        synchronized(Recents.class) {
            if(next.equals(hiddenTasks))return;
            hiddenTasks=next;
        }
        try {
            Object atm=XposedHelpers.getObjectField(wms,"mAtmService");
            Object notifications=XposedHelpers.getObjectField(atm,"mTaskChangeNotificationController");
            XposedHelpers.callMethod(notifications,"notifyTaskStackChanged");
        } catch(Throwable unavailable) {
            XposedBridge.log("OneStepLiteProbe: recents refresh deferred to next native query: "+unavailable);
        }
    }

    static void install(ClassLoader loader) {
        XC_MethodHook.Unhook visibility = null;
        try {
            Class<?> task = XposedHelpers.findClass("com.android.server.wm.Task", loader);
            Class<?> recent = XposedHelpers.findClass("com.android.server.wm.RecentTasks", loader);
            visibility = XposedHelpers.findAndHookMethod(recent, "isVisibleRecentTask", task, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra(SAVED, QUERY.get());
                    p.setObjectExtra(ENTERED, Boolean.TRUE);
                    QUERY.remove();
                    if (p.args.length == 1) {
                        Object lease = DesktopLaunchBridge.recentsLease(p.args[0]);
                        if (lease != null) {
                            QUERY.set(new Query(p.args[0], lease));
                            if(DesktopLaunchBridge.recentsLeaseHidden(lease))p.setResult(false);
                        }
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra(ENTERED))) return;
                    Query previous = (Query) p.getObjectExtra(SAVED);
                    if (previous == null) QUERY.remove(); else QUERY.set(previous);
                }
            });
            XposedHelpers.findAndHookMethod(task, "isAlwaysOnTopWhenVisible", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Query query = QUERY.get();
                    // Do not build TaskInfo in this hook: that can query this same property recursively.
                    if (query != null && query.task == p.thisObject
                            && DesktopLaunchBridge.recentsLeaseAlive(query.lease))
                        p.setResult(false);
                }
            });
            XposedBridge.log("OneStepLiteProbe: exact side-task recents filter installed");
        } catch (Throwable error) {
            if (visibility != null) visibility.unhook();
            XposedBridge.log("OneStepLiteProbe: recents hook unavailable: " + error);
        }
    }
}
