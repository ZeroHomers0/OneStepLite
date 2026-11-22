package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.WindowManager;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The native hidden WindowState, rather than a client buffer callback, owns reveal. */
final class RetainedWindow {
    static final String ACTION = Actions.RETAINED_WINDOW;
    static final String READY = Actions.RETAINED_READY;
    private Object window;
    private long generation;
    private float scale, x, y;
    private final java.util.Map<Object, Float> heldMain = new java.util.IdentityHashMap<>();
    private final java.util.Map<Object,Object> heldParents=new java.util.IdentityHashMap<>();
    private final java.util.Map<Object,Object> heldChrome=new java.util.IdentityHashMap<>();
    private long heldUntil;
    private Handler wmHandler;
    private Object globalLock;
    /** Invalidates the lock-entry hold once a real retain lease takes over. */
    private long lockHoldGeneration;
    private Object lockHoldDisplay;
    private boolean warmNotified;
    private java.util.function.BooleanSupplier authenticated=()->false;
    /**
     * Delay before checking whether an early hold can return to native ownership.
     * This is an authentication probe, never an expiry while still locked.
     * The broadcast-based retain lease can take ownership for the locked period.
     */
    private static final long LOCK_HOLD_MS = 500L;

    interface Reveal { void apply(Object transaction, long generation, float scale, float x, float y) throws Throwable; }

    Bundle retain(Object display, Intent intent) {
        Bundle state = intent.getBundleExtra("state");
        IBinder token = state == null ? null : state.getBinder("host");
        if (display == null || token == null) return null;
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        Object candidate = ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).get(token);
        if (!owns(candidate, display)) {
            return null;
        }
        long next = intent.getLongExtra("generation", 0);
        if (next <= 0 || next < generation) return null;
        if (intent.getBooleanExtra("cancel", false)) {
            if (candidate == window) {
                releaseMain(XposedHelpers.callMethod(display, "getPendingTransaction"));
                window = null; generation = next;
            }
            return null;
        }
        float s = intent.getFloatExtra("scale", Float.NaN);
        float ox = intent.getFloatExtra("x", Float.NaN), oy = intent.getFloatExtra("y", Float.NaN);
        if (!Float.isFinite(s) || s <= 0 || s >= 1 || !Float.isFinite(ox) || !Float.isFinite(oy)) return null;
        // Never authorize warming from goingAway or an occluded-but-on-screen window.
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(candidate, "isOnScreen"))) return null;
        if (!heldMain.isEmpty()) releaseMain(XposedHelpers.callMethod(display, "getPendingTransaction"));
        warmNotified=false;
        window = candidate; generation = next; scale = s; x = ox; y = oy;
        // The retain lease now owns the hold; the lock-entry hold must not expire
        // underneath it.
        lockHoldGeneration++;
        lockHoldDisplay = null;
        heldUntil = 0;
        // Native keyguard already hides the Host. Set the canonical main windows
        // before its start transaction can reveal newly recreated client buffers.
        for (Object main : ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).values())
            holdOpeningMain(main, display, true);
        XposedHelpers.callMethod(display, "scheduleAnimation");
        Bundle result = new Bundle(); result.putLong("generation", next); return result;
    }

    static boolean owns(Object window, Object display) {
        return WindowIdentity.ownsHost(window, display);
    }

    void install(ClassLoader loader, java.util.function.Supplier<Object> display,
            java.util.function.BooleanSupplier authenticated, Reveal reveal) {
        this.authenticated=authenticated;
        Class<?> nativeWindow = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
        XposedBridge.hookAllMethods(nativeWindow, "prepareSurfaces", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if ((window == null && lockHoldDisplay == null)
                        || p.hasThrowable() || p.args.length != 0) return;
                try {
                    Object current = p.thisObject;
                    if (!authenticated.getAsBoolean()) holdWorkspaceChrome(current, display.get());
                    if (window == null) {
                        if (XposedHelpers.callMethod(current, "getDisplayContent") != lockHoldDisplay) return;
                        if (authenticated.getAsBoolean()) {
                            // A quick wake with keep-on-lock disabled must not
                            // wait for the temporary hold's emergency timeout.
                            lockHoldGeneration++;
                            lockHoldDisplay = null;
                            releaseMain(XposedHelpers.callMethod(current, "getPendingTransaction"));
                            return;
                        }
                        // Native prepareSurfaces can overwrite our first alpha=0
                        // on the very next frame, before any retain broadcast.
                        holdOpeningMain(current, lockHoldDisplay, true);
                        return;
                    }
                    if (current != window) {
                        holdOpeningMain(current, display.get(), false);
                        return;
                    }
                    if (current != window || !owns(current, display.get())) return;
                    Object animator = XposedHelpers.getObjectField(current, "mWinAnimator");
                    if (!surfaceShown(animator)
                            || XposedHelpers.getIntField(animator, "mDrawState") != 4
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(current, "isOnScreen"))
                            || !authenticated.getAsBoolean()) return;
                    Object transaction = XposedHelpers.callMethod(current, "getPendingTransaction");
                    // Layout alpha may now permit drawing the Host, but its real
                    // surface stays hidden until the main/task finish gate opens.
                    reapplyChrome(transaction);
                    if(!warmNotified) {
                        warmNotified=true;
                        reveal.apply(transaction, generation, scale, x, y);
                        XposedBridge.log("OneStepLiteProbe: native Host ready; held main waits for correlated unlock commit");
                    }
                } catch (Throwable error) {
                    try { releaseMain(XposedHelpers.callMethod(p.thisObject, "getPendingTransaction")); }
                    catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
                    window = null; // Never retry a failed native visibility transaction.
                    XposedBridge.log("OneStepLiteProbe: retained native visibility handoff stopped: " + error);
                }
            }
        });
        try{XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.RootWindowContainer",loader),"prepareSurfaces",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.hasThrowable()||(window==null&&lockHoldDisplay==null))return;
                try{Object dc=display.get();if(dc!=null)reapplyHeldSurfaces(dc);}
                catch(Throwable failure){XposedBridge.log("OneStepLiteProbe: lock tail surface hold retry: "+failure);}
            }
        });}catch(Throwable unavailable){XposedBridge.log("OneStepLiteProbe: final lock surface pass unavailable: "+unavailable);}
    }

    /** Last pass after task/activity animation surfaces have been prepared. */
    private void reapplyHeldSurfaces(Object display){
        if(window==null&&lockHoldDisplay==null)return;
        if(window==null&&authenticated.getAsBoolean()){
            lockHoldGeneration++;lockHoldDisplay=null;
            releaseMain(XposedHelpers.callMethod(display,"getPendingTransaction"));return;
        }
        Object service=XposedHelpers.getObjectField(display,"mWmService");
        for(Object current:((Map<?,?>)XposedHelpers.getObjectField(service,"mWindowMap")).values())
            holdOpeningMain(current,display,true);
        Object tx=XposedHelpers.callMethod(display,"getPendingTransaction");
        for(Object surface:heldMain.keySet())if(Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid")))
            XposedHelpers.callMethod(tx,"setAlpha",surface,0f);
        reapplyChrome(tx);
    }

    static boolean surfaceShown(Object animator) {
        if (ShellCompat.hasMethod(animator.getClass(), "getShown", 0))
            return Boolean.TRUE.equals(XposedHelpers.callMethod(animator, "getShown"));
        return XposedHelpers.getBooleanField(animator, "mSurfaceShown");
    }

    void setHandler(Handler handler) { wmHandler = handler; }

    void setGlobalLock(Object lock) { globalLock = lock; }

    /**
     * Lock entry runs inside KeyguardController#setKeyguardShown, i.e. on the WM
     * thread while it already holds mGlobalLock. The Host only learns about the
     * lock through a broadcast afterwards, so the opening main task would stay
     * full screen for those frames. Hide it here, in the same transaction which
     * restores physical geometry, and let retain() take the hold over.
     */
    void holdOpeningMainForLock(Object display) {
        if (display == null || wmHandler == null || globalLock == null || !Thread.holdsLock(globalLock)) return;
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        // Repeated AOD/keyguard notifications must not invalidate a real lease,
        // or extend the temporary hold indefinitely.
        if (window != null || lockHoldDisplay == display) return;
        long lease = ++lockHoldGeneration;
        lockHoldDisplay = display;
        for (Object main : ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).values())
            holdOpeningMain(main, display, true);
        // Keep the hook armed even if a replacement client surface has not yet
        // appeared. Its first prepareSurfaces must also be hidden.
        heldUntil = 0; // Screen-off transitions can exceed the old 500 ms budget.
        Handler handler = wmHandler;
        if (handler == null) return;
        handler.postDelayed(() -> {
            synchronized (globalLock) {
                // A real retain lease owns the hold from here on; its own watchdog
                // releases it. An early hold can clear only after authentication.
                if (lease != lockHoldGeneration || window != null) return;
                if(!authenticated.getAsBoolean()) {
                    XposedBridge.log("OneStepLiteProbe: lock-entry hold retained until native authentication");return;
                }
                lockHoldDisplay = null;
                releaseMain(XposedHelpers.callMethod(display, "getPendingTransaction"));
                XposedHelpers.callMethod(display, "scheduleAnimation");
                XposedBridge.log("OneStepLiteProbe: lock-entry main hold expired without a retain lease");
            }
        }, LOCK_HOLD_MS);
    }

    boolean hostPresented(Object display){
        if(window==null||!owns(window,display))return false;
        try{
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            Object animator=XposedHelpers.getObjectField(window,"mWinAnimator");
            return attrs.alpha>0f&&surfaceShown(animator)&&XposedHelpers.getIntField(animator,"mDrawState")==4
                    &&Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isOnScreen"));
        }catch(Throwable absent){return false;}
    }

    interface PhysicalRestore {void apply() throws Throwable;}
    /** The same committed transaction must hold physical geometry before unmasking main. */
    void finishAuthenticatedRelease(Object display,Object transaction,boolean authenticated,
            boolean completed,boolean workspace,PhysicalRestore physical,PhysicalRestore retained) throws Throwable {
        if(!authenticated)return;
        // A correlated Host frame is stronger than WindowState's lagging draw
        // cache. Never turn a successful workspace reveal into physical fullscreen.
        if(completed && workspace)retained.apply();
        else physical.apply();
        if(completed)completeAuthenticatedReveal(display,transaction);
    }

    /** Called only after an authenticated, correlated WM/Host-frame handoff. */
    void completeAuthenticatedReveal(Object display, Object transaction) {
        if (globalLock == null || !Thread.holdsLock(globalLock)) return;
        if (window != null && !owns(window, display)) return;
        lockHoldGeneration++;
        lockHoldDisplay = null;
        releaseMain(transaction);
        window = null;
    }

    /** Native keyguard has already chosen these opening windows. Only postpone
     * their visibility; never show a task or touch the physical lockscreen. */
    private void holdOpeningMain(Object current, Object display, boolean prime) {
        if (!authenticated.getAsBoolean()) holdWorkspaceChrome(current, display);
        if (wmHandler == null || XposedHelpers.callMethod(current, "getDisplayContent") != display
                || XposedHelpers.getObjectField(current, "mActivityRecord") == null
                || ((Number) XposedHelpers.callMethod(current, "getWindowingMode")).intValue() != 1
                || (!prime && !Boolean.TRUE.equals(XposedHelpers.callMethod(current, "isOnScreen")))) return;
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) XposedHelpers.getObjectField(current, "mAttrs");
        if (attrs.type < 1 || attrs.type > 3) return;
        if ((attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0
                || Boolean.TRUE.equals(XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(current, "mActivityRecord"), "canShowWhenLocked"))) {
            Object tx=XposedHelpers.callMethod(current,"getPendingTransaction");
            releaseSurface(optionalCall(current,"getSurfaceControl"),tx);
            Object allowedActivity=XposedHelpers.getObjectField(current,"mActivityRecord");
            releaseContainer(allowedActivity,tx);releaseContainer(optionalCall(allowedActivity,"getTask"),tx);
            return;
        }
        Object animator = XposedHelpers.getObjectField(current, "mWinAnimator");
        if (!prime && !surfaceShown(animator)) return;
        Object surface = XposedHelpers.callMethod(current, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object transaction = XposedHelpers.callMethod(current, "getPendingTransaction");
        if (!prime && heldUntil == 0 && authenticated.getAsBoolean()) {
            heldUntil = android.os.SystemClock.uptimeMillis() + 2500L;
            final Object owner = window;
            final long lease = generation;
            wmHandler.postDelayed(() -> {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (owner != window || lease != generation) return;
                    Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
                    releaseMain(pending); window = null;
                    XposedHelpers.callMethod(display, "scheduleAnimation");
                    XposedBridge.log("OneStepLiteProbe: retained opening-main watchdog returned native surfaces");
                }
            }, 2500L);
        }
        if (heldUntil != 0 && android.os.SystemClock.uptimeMillis() >= heldUntil) { releaseMain(transaction); window = null; return; }
        if (!heldMain.containsKey(surface)) {
            heldMain.put(surface, ((Number) XposedHelpers.getObjectField(animator, "mAlpha")).floatValue());
            XposedBridge.log("OneStepLiteProbe: native opening main held until authenticated Host visibility");
        }
        XposedHelpers.callMethod(transaction, "setAlpha", surface, 0f);
        // Window buffers are not the only visible owner during sleep animations.
        // The activity/task parents and their native animation leashes may carry
        // the former fullscreen frame independently of this WindowState surface.
        Object activity=XposedHelpers.getObjectField(current,"mActivityRecord");
        holdContainer(activity,transaction);
        Object task=optionalCall(activity,"getTask");
        Object top=optionalCall(task,"topRunningActivity");
        if(top!=null&&Boolean.FALSE.equals(optionalCall(top,"canShowWhenLocked")))holdContainer(task,transaction);
        else if(top!=null&&Boolean.TRUE.equals(optionalCall(top,"canShowWhenLocked")))releaseContainer(task,transaction);
    }

    private void holdContainer(Object container,Object transaction){
        if(container==null)return;
        holdParentSurface(optionalCall(container,"getSurfaceControl"),container,transaction);
        Object leash=optionalCall(container,"getAnimationLeash");
        if(leash==null){Object animator=optionalField(container,"mSurfaceAnimator");leash=optionalCall(animator,"getAnimationLeash");}
        holdParentSurface(leash,container,transaction);
    }
    private void holdParentSurface(Object surface,Object container,Object transaction){
        if(surface==null||!Boolean.TRUE.equals(optionalCall(surface,"isValid")))return;
        if(!heldMain.containsKey(surface)){
            Object alpha=optionalField(container,"mLastSurfaceAlpha");
            heldMain.put(surface,alpha instanceof Number?((Number)alpha).floatValue():1f);
            heldParents.put(surface,container);
        }
        XposedHelpers.callMethod(transaction,"setAlpha",surface,0f);
    }
    private static Object optionalCall(Object target,String name){try{return target==null?null:XposedHelpers.callMethod(target,name);}catch(Throwable missing){return null;}}
    private static Object optionalField(Object target,String name){try{return target==null?null:XposedHelpers.getObjectField(target,name);}catch(Throwable missing){return null;}}

    private void releaseContainer(Object container,Object transaction){
        if(container==null)return;
        for(Object surface:new java.util.ArrayList<>(heldParents.keySet()))
            if(heldParents.get(surface)==container)releaseSurface(surface,transaction);
    }
    private void releaseSurface(Object surface,Object transaction){
        Float fallback=heldMain.remove(surface);Object parent=heldParents.remove(surface);
        if(fallback==null||!Boolean.TRUE.equals(optionalCall(surface,"isValid")))return;
        Object alpha=optionalField(parent,"mLastSurfaceAlpha");
        XposedHelpers.callMethod(transaction,"setAlpha",surface,alpha instanceof Number?((Number)alpha).floatValue():fallback);
    }

    /** The Host, its TaskView children, and separate mirror/transition windows leave together. */
    private void holdWorkspaceChrome(Object current, Object display) {
        if (!WindowIdentity.ownsChrome(current, display)) return;
        Object surface=optionalCall(current,"getSurfaceControl");
        if (surface==null || !Boolean.TRUE.equals(optionalCall(surface,"isValid"))) return;
        heldChrome.put(surface,current);
        XposedHelpers.callMethod(XposedHelpers.callMethod(current,"getPendingTransaction"),"setAlpha",surface,0f);
    }

    private void releaseChrome(Object transaction) {
        for (java.util.Map.Entry<Object,Object> entry : heldChrome.entrySet()) {
            if (!Boolean.TRUE.equals(optionalCall(entry.getKey(),"isValid"))) continue;
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(entry.getValue(),"mAttrs");
            XposedHelpers.callMethod(transaction,"setAlpha",entry.getKey(),attrs.alpha);
        }
        heldChrome.clear();
    }

    private void reapplyChrome(Object transaction) {
        for (Object surface : heldChrome.keySet())
            if (Boolean.TRUE.equals(optionalCall(surface,"isValid")))
                XposedHelpers.callMethod(transaction,"setAlpha",surface,0f);
    }

    private void releaseMain(Object transaction) {
        releaseChrome(transaction);
        for (java.util.Map.Entry<Object, Float> entry : heldMain.entrySet()) {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(entry.getKey(), "isValid"))) {
                Object alpha=optionalField(heldParents.get(entry.getKey()),"mLastSurfaceAlpha");
                float restored=alpha instanceof Number?((Number)alpha).floatValue():entry.getValue();
                XposedHelpers.callMethod(transaction,"setAlpha",entry.getKey(),restored);
            }
        }
        heldMain.clear();heldParents.clear(); heldUntil = 0;
    }

    static void request(Context context, Handler handler, IBinder token, long generation,
            float scale, float x, float y, Consumer<Boolean> ready) {
        Bundle state = new Bundle(); state.putBinder("host", token);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state", state)
                    .putExtra("generation", generation).putExtra("scale", scale).putExtra("x", x).putExtra("y", y),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS, new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent intent) {
                            Bundle result = getResultExtras(false);
                            ready.accept(getResultCode() == -1 && result != null
                                    && result.getLong("generation", -1) == generation);
                        }
                    }, handler, 0, null, null);
        } catch (Throwable error) { ready.accept(false); }
    }
}
