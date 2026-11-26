package io.github.zerohomers0.onesteplite;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native WM focus scans skip exact passive side leases while TOP retains priority. */
final class WorkspaceWindowFocusHooks {
    private WorkspaceWindowFocusHooks() {}

    static void install(ClassLoader loader, java.util.function.Supplier<ZoomTarget> zoomTarget, java.util.function.Supplier<Object> displayContent, java.util.function.BooleanSupplier zoomed, ThreadLocal<Object> scanDisplay, java.util.function.Consumer<String> log) {
        installPointerFocus(loader, zoomTarget, displayContent, zoomed, log);
        installWindowFocus(loader, zoomTarget, displayContent, zoomed, scanDisplay, log);
    }
    private static void installPointerFocus(ClassLoader loader, java.util.function.Supplier<ZoomTarget> zoomTarget, java.util.function.Supplier<Object> displayContent, java.util.function.BooleanSupplier zoomed, java.util.function.Consumer<String> log) {
        try {
            Class<?> pointer = XposedHelpers.findClass("com.android.server.wm.PointerEventDispatcher", loader);
            XposedBridge.hookAllMethods(pointer, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 1 || !(p.args[0] instanceof android.view.MotionEvent)) return;
                    android.view.MotionEvent event = (android.view.MotionEvent) p.args[0];
                    ZoomTarget target = zoomTarget.get();
                    if (!zoomed.getAsBoolean() || target == null || target.display != displayContent.get()
                            || event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN
                            || event.getPointerCount() != 1
                            || !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return;
                    try {
                        if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(event, "getDisplayId"))) return;
                        synchronized (target.globalLock) {
                            if (!zoomed.getAsBoolean() || zoomTarget.get() != target) return;
                            Object task = DesktopLaunchBridge.topTaskAt(
                                    target.display, event.getRawX(), event.getRawY());
                            if (TopPointerFocus.select(task, target.display,
                                    DesktopLaunchBridge::ownsTopTask))
                                log.accept("workspace TOP pointer selected native back/IME focus");
                        }
                    } catch (Throwable unavailable) { log.accept("TOP pointer focus unavailable: " + unavailable); }
                }
            });
            log.accept("workspace TOP focus follows physical pointer DOWN before native edge back");
        } catch (Throwable unavailable) { log.accept("TOP pointer focus hook unavailable: " + unavailable); }

    }
    private static void installWindowFocus(ClassLoader loader, java.util.function.Supplier<ZoomTarget> zoomTarget, java.util.function.Supplier<Object> displayContent, java.util.function.BooleanSupplier zoomed, ThreadLocal<Object> scanDisplay, java.util.function.Consumer<String> log) {
        try {
            Class<?> display=XposedHelpers.findClass("com.android.server.wm.DisplayContent",loader);
            Class<?> window=XposedHelpers.findClass("com.android.server.wm.WindowState",loader);
            XposedHelpers.findAndHookMethod(window, "handleTapOutsideFocusInsideSelf", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = zoomTarget.get();
                    if (!zoomed.getAsBoolean() || target == null || target.display != displayContent.get()
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        if (ImePolicy.admitTopTouch(p.thisObject, target.display,
                                DesktopLaunchBridge::ownsTopTask))
                            log.accept("workspace explicit TOP touch selected native Activity focus");
                    } catch (Throwable unavailable) { log.accept("TOP touch focus unavailable: " + unavailable); }
                }
            });
            final java.util.concurrent.atomic.AtomicInteger scans=new java.util.concurrent.atomic.AtomicInteger(2);
            final java.util.concurrent.atomic.AtomicInteger candidates=new java.util.concurrent.atomic.AtomicInteger(8);
            XposedHelpers.findAndHookMethod(display,"findFocusedWindow",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("onesteplite.previousFocusScan",scanDisplay.get());
                    scanDisplay.set(p.thisObject);
                    if(zoomed.getAsBoolean() && p.thisObject==displayContent.get())try {
                        if(ImePolicy.reconcilePassiveAppFocus(p.thisObject,task->{
                            Object lease=DesktopLaunchBridge.recentsLease(task);
                            return lease!=null && DesktopLaunchBridge.recentsLeaseAlive(lease);
                        }))log.accept("workspace reconciled native Activity focus after side lease");
                    } catch(Throwable unavailable) {log.accept("workspace Activity focus reconcile skipped: "+unavailable);}
                    if(zoomed.getAsBoolean() && p.thisObject==displayContent.get() && scans.getAndDecrement()>0)
                        log.accept("workspace focus scan entered");
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous=p.getObjectExtra("onesteplite.previousFocusScan");
                    if(previous==null)scanDisplay.remove();
                    else scanDisplay.set(previous);
                }
            });
            XC_MethodHook eligibility=new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(!zoomed.getAsBoolean() || displayContent.get()==null
                            || scanDisplay.get()!=displayContent.get()
                            || p.args.length>0 && Boolean.TRUE.equals(p.args[0])
                            || !Boolean.TRUE.equals(p.getResult()))return;
                    try {
                        Object observedTask=XposedHelpers.callMethod(p.thisObject,"getTask");
                        if (ImePolicy.isInactiveTopWindow(p.thisObject, displayContent.get(),
                                DesktopLaunchBridge::ownsTopTask)) {
                            p.setResult(Boolean.FALSE);
                            return;
                        }
                        if(observedTask!=null && Integer.valueOf(6).equals(XposedHelpers.callMethod(observedTask,"getWindowingMode"))
                                && candidates.getAndDecrement()>0) {
                            Object lease=DesktopLaunchBridge.recentsLease(observedTask);
                            log.accept("workspace focus candidate "+p.thisObject+" bounds="+XposedHelpers.callMethod(observedTask,"getBounds")
                                    +" translucent="+XposedHelpers.callMethod(observedTask,"isForceTranslucent")
                                    +" lease="+(lease!=null)+" alive="+(lease!=null && DesktopLaunchBridge.recentsLeaseAlive(lease)));
                        }
                        if(ImePolicy.isPassiveSideWindow(p.thisObject,displayContent.get(),task->{
                            Object lease=DesktopLaunchBridge.recentsLease(task);
                            return lease!=null && DesktopLaunchBridge.recentsLeaseAlive(lease);
                        }))p.setResult(Boolean.FALSE);
                    } catch(Throwable unavailable) {log.accept("passive side window focus skipped: "+unavailable);}
                }
            };
            XposedHelpers.findAndHookMethod(window,"canReceiveKeys",eligibility);
            XposedHelpers.findAndHookMethod(window,"canReceiveKeys",boolean.class,eligibility);
            // ART may have inlined these small focus wrappers before module hooks ran.
            // Deopt only the observed native scan callers; all focus decisions stay native.
            for(Class<?> owner:new Class<?>[]{display,window})for(java.lang.reflect.Method method:owner.getDeclaredMethods()) {
                String name=method.getName();
                if(owner==display && (name.equals("updateFocusedWindowLocked")
                        || name.equals("findFocusedWindowIfNeeded") || name.equals("lambda$new$3"))
                        || owner==window && name.equals("canReceiveKeys") && method.getParameterCount()==0) {
                    try {log.accept("workspace focus deopt "+name+"="+XposedHelpers.callStaticMethod(
                            XposedBridge.class,"deoptimizeMethod",method));}
                    catch(Throwable unavailable) {log.accept("workspace focus deopt unavailable "+name+": "+unavailable);}
                }
            }
            log.accept("native window focus scan skips exact passive side leases; TOP remains eligible");
        } catch(Throwable unavailable) {log.accept("workspace window focus hook unavailable: "+unavailable);}

    }

}
