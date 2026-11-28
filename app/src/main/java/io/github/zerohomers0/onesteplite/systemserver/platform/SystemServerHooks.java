package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.HostEntry.*;
import static io.github.zerohomers0.onesteplite.NativeWindowRuntime.*;
import static io.github.zerohomers0.onesteplite.LauncherRuntime.*;
import static io.github.zerohomers0.onesteplite.ZoomSettingsRuntime.*;
import static io.github.zerohomers0.onesteplite.ZoomRuntime.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.view.WindowManager;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Installs system-server hooks in dependency order and binds them to the current WMS display. */
final class SystemServerHooks {
    private final HostEntry owner;
    private final TaskRotationController rotations;
    private final TopFocusNotification topFocusNotification = new TopFocusNotification();
    private boolean systemUiRotationFailureLogged;

    SystemServerHooks(HostEntry owner, TaskRotationController rotations) {
        this.owner = owner;
        this.rotations = rotations;
    }

    /**
     * 系统框架作用域（LSPosed 里要勾"系统框架"）：
     * 捕获默认显示器的 DisplayContent，收到 io.github.zerohomers0.onesteplite.ZOOM 广播时调用
     * DisplayContent.applyMagnificationSpec(scale/offset) —— 目标机自带的整屏缩放原语，
     * 与锤子OneStepLite的 MagnificationSpecSmt / applyZoomedMatrixSmt 是同一机制的官方版本。
     */
    void install(ClassLoader cl) {
        log("=== attached to system_server (android) " + RUNTIME_MARKER);
        HookGuard.run("systemserver recents",HostEntry::log,()->Recents.install(cl));
        HookGuard.run("systemserver raw input",HostEntry::log,NativeRawInputBridge::install);
        if(!installDisplayContentHook(cl))return;
        HookGuard.run("systemserver input",HostEntry::log,()->installInputHooks(cl));
        HookGuard.run("systemserver keyguard",HostEntry::log,()->installKeyguardHooks(cl));
        HookGuard.run("systemserver rotation and focus",HostEntry::log,()->installRotationAndFocusHooks(cl));
        HookGuard.run("systemserver context",HostEntry::log,()->captureSystemContext(cl));
        HookGuard.run("systemserver desktop launch",HostEntry::log,()->installDesktopIconLaunchBridge(cl));
        HookGuard.run("systemserver zoom observer",HostEntry::log,()->{installZoomFileObserver();startZoomFileWatcher();});
    }
    private boolean installDisplayContentHook(ClassLoader cl){
        return HookGuard.install("DisplayContent surface capture",cl,HostEntry::log,hooks->{
            Class<?> dc=hooks.findClass("com.android.server.wm.DisplayContent");
            hooks.hookAllMethods(dc,"configureSurfaces",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam param){
                    Object self=param.thisObject;
                    try{
                        Object type=XposedHelpers.callMethod(self,"getDisplayId");
                        if(type instanceof Integer && ((Integer)type).intValue()!=0)return;
                        sDisplayContent=self;PortalDragInput.observeDisplay(self,cl,HostEntry::dragShareActive);
                        Object service=XposedHelpers.getObjectField(self,"mWmService");
                        Object lock=XposedHelpers.getObjectField(service,"mGlobalLock");
                        Object handler=XposedHelpers.getObjectField(service,"mH");
                        if(!(handler instanceof Handler))throw new IllegalStateException("WMS.mH is not a Handler");
                        owner.cancelZoomAnimation(null);sZoomAnimKey=null;
                        sZoomTarget=new ZoomTarget(owner,self,lock,(Handler)handler,()->sZoomTarget,error->{
                            log("WM zoom request stopped; no same-state retry: "+error);XposedBridge.log(error);
                        });
                        sRetainedWindow.setGlobalLock(lock);armZoomBridge(service);dispatchCurrentZoom();
                        log("captured DisplayContent(display 0) with WM handler/global lock -> "+self);
                    }catch(Throwable failure){sZoomTarget=null;log("capture DisplayContent failed: "+failure);}
                }
            });
        });
    }

    private void installInputHooks(ClassLoader cl){
        RecentsInput.install(cl,()->sZoomed?new float[]{sZoomScale,sZoomOffset,sZoomOffsetX}:null);
        WallpaperScale.install(cl,()->{
            ZoomTarget target=sZoomTarget;
            return sZoomed && target!=null && target.display==sDisplayContent?target.display:null;
        });
        installMagnificationSurfaceTracking(cl);
        InputWindowMagnificationHooks.install(cl,()->sZoomed,()->sDisplayContent,
                ()->sUnlockPhysicalShadeGeneration,()->sUnlockPhysicalShadeUntil,HostEntry::log);
        NavigationGestureSurfaceHooks.install(cl,new NavigationGestureSurfaceHooks.Host(){
            @Override public Context systemContext(){return (Context)sSystemContext;}
            @Override public java.util.Map<Object,String> gestureSurfaces(){return sGestureSurfaces;}
            @Override public ZoomTarget zoomTarget(){return sZoomTarget;}
            @Override public float zoomScale(){return sZoomScale;}
            @Override public float zoomOffsetX(){return sZoomOffsetX;}
            @Override public float zoomOffsetY(){return sZoomOffset;}
            @Override public void applyGestureSurfaces(ZoomTarget target,float scale,float offsetY,float offsetX){
                ZoomTransactions.applyGestureSurfacesLocked(target,scale,offsetY,offsetX);
            }
        },HostEntry::log);
        TaskResizeableHooks.install(cl,()->sFullscreenRotationActive,TaskRotationController::isSideTask,HostEntry::log);
    }
    private void installKeyguardHooks(ClassLoader cl){
        try{
            KeyguardZoom.Reset resetForLock=SystemServerHooks::resetForLock;
            sKeyguardZoom.install(cl,resetForLock);
            sKeyguardZoom.installSleep(cl,()->{
                Handler bridge=sZoomBridgeHandler;
                if(bridge!=null)bridge.post(()->{
                    if(!sKeyguardZoom.sleeping())return;
                    try{resetForLock.run();}catch(Throwable failure){log("early sleep geometry barrier: "+failure);}
                });
            });
            log("native keyguard zoom barrier armed");
        }catch(Throwable unavailable){log("native keyguard zoom barrier unavailable: "+unavailable);}
        try{
            sRetainedWindow.install(cl,()->sDisplayContent,()->!sKeyguardZoom.sleeping()&&EntryGuard.canOpen(sWmsContext),
                    (tx,generation,scale,x,y)->{
                        log("native retained Host ready; waiting for correlated unlock commit generation="+generation);
                        Context nativeContext=sWmsContext;Handler bridge=sZoomBridgeHandler;
                        if(nativeContext!=null&&bridge!=null)bridge.post(()->nativeContext.sendBroadcast(
                                new Intent(RetainedWindow.READY).setPackage(SYSTEMUI).putExtra("generation",generation),
                                android.Manifest.permission.WRITE_SECURE_SETTINGS));
                    });
        }catch(Throwable error){log("native retained visibility hook unavailable: "+error);}
    }

    static void resetForLock() throws Throwable{
        ZoomTarget target=sZoomTarget;if(target==null)return;
        synchronized(target.globalLock){
            if(target!=sZoomTarget || target.display!=sDisplayContent)return;
            long now=System.currentTimeMillis();
            synchronized(sZoomChannels){sZoomChannels.offer("1 0 0",now,now);}
            sUnlockPhysicalShadeGeneration=0;sUnlockPhysicalShadeUntil=0;
            target.dispatch.invalidateLastAttempt();target.owner.cancelZoomAnimation(target);
            if(!sZoomed)return;
            Object tx=XposedHelpers.callMethod(target.display,"getPendingTransaction");
            if(!sLiveSwapZoomSurfaces.begin(target.display,tx))
                throw new IllegalStateException("native lock-entry surface scope busy");
            try{
                sRetainedWindow.holdOpeningMainForLock(target.display);
                target.owner.applyDisplayZoomLocked(target,1f,0f,0f,null,true);
                reapplyFinalMagnification(target.display,tx);
            }finally{sLiveSwapZoomSurfaces.finish(target.display,tx);}
            log("native keyguard entry restored physical geometry before visibility");
        }
    }
    private void installRotationAndFocusHooks(ClassLoader cl){
        hookMagnificationReplayAfterTransition(cl);
        installNativeFreeformCoexistence(cl);
        rotations.installFixedMainRotationHooks(cl);
        installMainSystemUiRotation(cl);
        rotations.installFixedMainTouchableCropHook(cl);
        installSideSystemBarPolicy(cl);
        installWorkspaceFocus(cl);
        installWorkspaceRunningTasks(cl);
    }
    static void captureSystemContext(ClassLoader cl){
        try{
            Class<?> at=XposedHelpers.findClass("android.app.ActivityThread",cl);
            Object thread=XposedHelpers.callStaticMethod(at,"currentActivityThread");
            Object context=thread==null?null:XposedHelpers.callMethod(thread,"getSystemContext");
            sSystemContext=context;log("system context -> "+context);
        }catch(Throwable failure){log("system context failed: "+failure);}
    }

    private void hookMagnificationReplayAfterTransition(ClassLoader cl) {
        installTransitionMagnificationStart(cl);
        installTransitionMagnificationFinish(cl);
        installTopRotationTransition(cl);
    }
    private void installTransitionMagnificationStart(ClassLoader cl){
        TransitionMagnificationStartHooks.install(cl, new TransitionMagnificationStartHooks.Host() {
            @Override public TransitionMagnificationStartHooks.State state() {
                return new TransitionMagnificationStartHooks.State() {
                    @Override public boolean zoomed() { return sZoomed; }
                    @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
                    @Override public Object displayContent() { return sDisplayContent; }
                    @Override public float zoomScale() { return sZoomScale; }
                    @Override public float zoomOffsetX() { return sZoomOffsetX; }
                    @Override public float zoomOffsetY() { return sZoomOffset; }
                };
            }
            @Override public TransitionMagnificationStartHooks.Actions actions() {
                return new TransitionMagnificationStartHooks.Actions() {
                    @Override public NativeFreeformStartGate nativeFreeformStart() {
                        return sNativeFreeformStart;
                    }
                    @Override public void finishNativeFreeformStart(ZoomTarget target, Object start) {
                        HostEntry.finishNativeFreeformStart(target, start);
                    }
                    @Override public void reapplyFinalMagnification(
                            Object display, Object transaction, Object info) {
                        HostEntry.reapplyFinalMagnification(display, transaction, info);
                    }
                };
            }
        }, HostEntry::log);
    }
    private void installTransitionMagnificationFinish(ClassLoader cl){
        TransitionMagnificationFinishHooks.install(cl, new TransitionMagnificationFinishHooks.Host() {
            @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
            @Override public boolean zoomed() { return sZoomed; }
            @Override public java.util.Map<Object, Object> homeTransitionInfos() {
                return sHomeTransitionInfos;
            }
            @Override public java.util.Map<Object, Object> magnificationFinishReplays() {
                return sMagnificationFinishReplays;
            }
            @Override public void reapplyFinalMagnification(
                    Object display, Object transaction, Object info) {
                HostEntry.reapplyFinalMagnification(display, transaction, info);
            }
            @Override public void layerWorkspaceWithNativeFreeforms(
                    Object display, Object transaction) {
                NativeWindowRuntime.layerWorkspaceWithNativeFreeforms(display, transaction);
            }
        }, HostEntry::log);
    }
    private void installTopRotationTransition(ClassLoader cl){
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", cl);
            XposedBridge.hookAllMethods(transition, "finishTransition", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    HomeTransitionMagnification.finish(sHomeTransitionInfos.remove(p.thisObject));
                    try {
                        Object start = XposedHelpers.getObjectField(p.thisObject, "mStartTransaction");
                        sNativeFreeformStart.finish(target.display, start);
                    } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    Object display = sMagnificationFinishReplays.remove(p.thisObject);
                    if (p.hasThrowable() || display == null || target == null
                            || display != target.display) return;
                    target.handler.post(() -> {
                        synchronized (target.globalLock) {
                            if (target != sZoomTarget || target.display != sDisplayContent) return;
                            try {
                                if (sZoomed) {
                                    // A new session may have started since this finish was built.
                                    Object transaction = XposedHelpers.callMethod(display,
                                            "getPendingTransaction");
                                    ShellCompat.call(display, "reapplyMagnificationSpec",
                                            transaction);
                                    layerWorkspaceWithNativeFreeforms(display, transaction);
                                    XposedHelpers.callMethod(transaction, "apply");
                                } else {
                                    restoreExitedMagnificationLocked(target);
                                }
                            } catch (Throwable error) {
                                log("transition zoom reconciliation skipped: " + error);
                            }
                        }
                    });
                }
            });
            TopRotationTransitionHooks.install(cl, new TopRotationTransitionHooks.Host() {
                @Override public void onTransitionEnd() {
                    rotations.onTopRotationTransitionEnd();
                }
                @Override public java.util.Map<Object, android.graphics.Rect> rotationBounds() {
                    return TaskRotationController.sTopRotationBounds;
                }
                @Override public boolean ownsTaskRotationTransform(Object page) {
                    return rotations.ownsTaskRotationTransform(page);
                }
                @Override public boolean isUpperLandscapeTask(Object task) {
                    return TaskRotationController.isUpperLandscapeTask(task);
                }
                @Override public void handoffSurface(Object page) {
                    rotations.noteTopRotationHandoff(page);
                }
            }, HostEntry::log);
            log("Transition#buildFinishTransaction magnification replay hooked");
        } catch (Throwable error) {
            log("transition magnification replay hook unavailable: " + error);
        }
    }

    private final ImeTargetRefresh imeTargetRefresh = new ImeTargetRefresh();

    private void updateMainSystemUiRotation(Object window) {
        if (!sFullscreenRotationActive || !sZoomed) return;
        try {
            Object app = sFixedRotationApp;
            if (app != null && !sTaskRotations.containsKey(app)) app = null;
            publishMainUiConfiguration(app);
            if (TopRotation.phone(sDisplayContent) && ImeViewport.imeWindow(window)) {
                Object imeOwner = sMainSystemUiRotation.updatePhoneIme(window, sDisplayContent,
                        rotations::ownsTaskRotationTransform, w -> ImePolicy.isFocusedTopWindow(
                                w, sDisplayContent, DesktopLaunchBridge::ownsTopTask), HostEntry::log);
                sMainImeProcessConfiguration.update(window, sDisplayContent, imeOwner, HostEntry::log);
                return;
            }
            sMainSystemUiRotation.update(window, sDisplayContent, app, HostEntry::log);
            Object imeApp = sInteractiveImeViewport.update(window, sDisplayContent,
                    DesktopLaunchBridge::ownsTopTask, HostEntry::log);
            sMainImeProcessConfiguration.update(window, sDisplayContent,
                    imeApp == null ? app : imeApp, HostEntry::log);
        } catch (Throwable error) {
            if (!systemUiRotationFailureLogged) {
                systemUiRotationFailureLogged = true;
                log("main system UI rotation unavailable: " + error);
            }
        }
    }

    static void publishMainUiConfiguration(Object app) {
        try {
            Object state = app == null ? null : XposedHelpers.getObjectField(app, "mFixedRotationTransformState");
            if (state == sPublishedMainUiState) return;
            sPublishedMainUiState = state;
            final android.content.res.Configuration config = state == null ? null
                    : new android.content.res.Configuration((android.content.res.Configuration)
                            XposedHelpers.getObjectField(state, "mRotatedOverrideConfiguration"));
            Context context = (Context) (sSystemContext != null ? sSystemContext : sWmsContext);
            Handler bridge = sZoomBridgeHandler;
            if (context == null || bridge == null) return;
            // Never broadcast synchronously while holding WMS's global lock.
            bridge.post(() -> context.sendBroadcast(new Intent(MainUiConfiguration.ACTION)
                    .setPackage(SYSTEMUI).putExtra("configuration", config),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS));
        } catch (Throwable error) { log("main UI configuration publication unavailable: " + error); }
    }

    private void installMainSystemUiRotation(ClassLoader cl) {
        rotations.installTopSystemBarReturn(cl);
        try {
            sMainImeProcessConfiguration.install(cl);
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedHelpers.findAndHookMethod(display, "shouldImeAttachedToApp", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || p.thisObject != sDisplayContent || p.hasThrowable()) return;
                    try {
                        if (ImeViewport.target(p.thisObject,
                                DesktopLaunchBridge::ownsTopTask) != null
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(
                                        p.thisObject, "isImeControlledByApp"))) p.setResult(Boolean.TRUE);
                    } catch (Throwable error) { log("interactive native IME parent unavailable: " + error); }
                }
            });
            XposedBridge.hookAllMethods(display, "updateImeInputAndControlTarget", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || p.thisObject != sDisplayContent || p.hasThrowable()) return;
                    try {
                        imeTargetRefresh.update(p.thisObject,
                                DesktopLaunchBridge::ownsTopTask, SystemServerHooks.this::updateMainSystemUiRotation);
                    } catch (Throwable error) { log("interactive IME layering refresh unavailable: " + error); }
                }
            });
            Class<?> organizer = XposedHelpers.findClass("com.android.server.wm.WindowOrganizerController", cl);
            XposedBridge.hookAllMethods(organizer, "applyTaskChanges", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || p.args.length < 2) return;
                    rotations.captureMainBeforeSideChange(p.args[0],p.args[1]);
                    try { p.setObjectExtra("onesteplite.sideRole", SideRoleChange.enter(
                            p.args[0], p.args[1], sDisplayContent)); }
                    catch (Throwable error) { log("side role change observation unavailable: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object lease=p.getObjectExtra("onesteplite.sideRole");
                    try{if(lease!=null&&!p.hasThrowable()){
                        Object activity=XposedHelpers.callMethod(lease,"topRunningActivity");
                        if(activity!=null)rotations.applyFixedRotationLocked(sDisplayContent,activity,"side-role-committed");
                    }}catch(Throwable error){log("side role committed rotation: "+error);}
                    finally{SideRoleChange.exit(lease);}
                }
            });
            Class<?> policy = XposedHelpers.findClass("com.android.server.wm.DisplayPolicy", cl);
            XposedBridge.hookAllMethods(policy, "layoutWindowLw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0) updateMainSystemUiRotation(p.args[0]);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "getSurfaceTouchableRegion",
                    android.graphics.Region.class, WindowManager.LayoutParams.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (p.hasThrowable() || !sZoomed || !sFullscreenRotationActive
                            || target == null || target.display != sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        MainPanelTouchRegion.crop(p.thisObject, target.display,
                                (android.graphics.Region) p.args[0],
                                (WindowManager.LayoutParams) p.args[1]);
                    } catch (Throwable error) {
                        log("main panel touch boundary unavailable: " + error);
                    }
                }
            });
            log("main notification shade modal touch limited to its own surface");
            XposedBridge.hookAllMethods(window, "prepareSurfaces", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    updateMainSystemUiRotation(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !sZoomed || sDisplayContent == null) return;
                    try {
                        if (sFullscreenRotationActive)
                            sMainWindowClip.update(p.thisObject, sDisplayContent, HostEntry::log);
                    } catch (Throwable error) { log("main window animation clip unavailable: " + error); }
                    rotations.refreshWideVideoSignal(p.thisObject);
                    try {
                        Object spec = XposedHelpers.getObjectField(sDisplayContent, "mMagnificationSpec");
                        if (spec != null) SystemUiRotation.magnifyPrivacy(p.thisObject,
                                sDisplayContent, spec,
                                XposedHelpers.callMethod(sDisplayContent, "getPendingTransaction"));
                    } catch (Throwable error) {
                        if (!systemUiRotationFailureLogged) {
                            systemUiRotationFailureLogged = true;
                            log("permission window zoom unavailable: " + error);
                        }
                    }
                }
            });
            log("main system UI native token rotation and late permission-window zoom hooked");
        } catch (Throwable error) { log("main system UI rotation hooks unavailable: " + error); }
    }

    private void installMagnificationSurfaceTracking(ClassLoader cl) {
        MagnificationSurfaceTrackingHooks.install(cl, new MagnificationSurfaceTrackingHooks.Host() {
            @Override public MagnificationSurfaceTrackingHooks.State state() {
                return new MagnificationSurfaceTrackingHooks.State() {
                    @Override public boolean zoomed() { return sZoomed; }
                    @Override public Object displayContent() { return sDisplayContent; }
                    @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
                    @Override public boolean finalSnapshot() {
                        return Boolean.TRUE.equals(sFinalMagnificationSnapshot.get());
                    }
                    @Override public Object finishInfo() { return sMagnificationFinishInfo.get(); }
                };
            }
            @Override public MagnificationSurfaceTrackingHooks.Surfaces surfaces() {
                return new MagnificationSurfaceTrackingHooks.Surfaces() {
                    @Override public Object routeNativeFreeform(Object container, Object display,
                            Object transaction, boolean snapshot) {
                        return sNativeFreeformStart.route(container, display, transaction, snapshot);
                    }
                    @Override public Object routeLiveSwap(Object container, Object display,
                            Object transaction, boolean snapshot) {
                        return sLiveSwapZoomSurfaces.route(container, display, transaction, snapshot);
                    }
                    @Override public java.util.Map<Object, Object> trackedSurfaces() {
                        return sMagnifiedSurfaces;
                    }
                    @Override public void remember(Object container, Object spec) {
                        rememberMagnifiedSurface(container, spec);
                    }
                    @Override public void stagePassBlur(Object display, Object transaction, Object spec) {
                        stagePassBlurCoordinates(display, transaction, spec);
                    }
                };
            }
        }, HostEntry::log);
    }

    private void installWorkspaceRunningTasks(ClassLoader cl) {
        WorkspaceRunningTasksHooks.install(cl, () -> sSystemContext, () -> sDisplayContent,
                () -> sZoomed, sWorkspaceLauncherTaskQuery, HostEntry::log);
    }


    private void installWorkspaceWindowFocus(ClassLoader cl) {
        WorkspaceWindowFocusHooks.install(cl, () -> sZoomTarget, () -> sDisplayContent,
                () -> sZoomed, sWindowFocusScanDisplay, HostEntry::log);
    }


    private void installWorkspaceTopFrontNotifications(ClassLoader cl) {
        TopFocusHooks.install(cl, () -> sZoomTarget, () -> sDisplayContent,
                () -> sZoomed, topFocusNotification, HostEntry::log);
    }


    private void installWorkspaceFocus(ClassLoader cl) {
        installWorkspaceWindowFocus(cl);
        installWorkspaceTopFrontNotifications(cl);
        WorkspaceTaskFocusHooks.install(cl, () -> sZoomed, () -> sDisplayContent,
                sReadingWorkspaceFocus, TaskRotationController::isUpperLandscapeTask, HostEntry::log);
    }

    private void installSideSystemBarPolicy(ClassLoader cl) {
        SideSystemBarHooks.install(cl, () -> sFullscreenRotationActive, () -> sZoomed,
                () -> sDisplayContent, TaskRotationController::isSideTask, HostEntry::log);
    }
}
