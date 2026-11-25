package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * OneStepLite宿主模块。
 *
 * v0.1 能力探针（已完成）：核对类/方法签名，确认能拿到 TaskViewTransitions / TaskViewFactory。
 * v0.2 加窗口（已完成）：在 SystemUI 进程内用常规 WindowManager 加一个自己的窗口。
 * v0.3-v0.5（已完成）：单个 TaskView 内渲染真实应用，含 PendingIntent 与自锁修正。
 * v0.6（inc4）：4 个 TaskView（主区+3 格）+ WorkspaceLayout 几何 + 点击副格交换。
 * v0.7：角部内滑热区（R2 参数）+ 广播触发；首次触发再启动宿主，之后切换显示。
 * v0.8：修 inset 偏移、onTaskInfoChanged 状态、格子密度自适应、亮屏重断言。
 * v0.8.1（本版）：cutout 模式 ALWAYS（去掉 122px 状态栏偏移）、工作区不透明底、
 *                任务边界直发 WCT、热区提到宿主窗口之上。
 * 规格：research/rea分析/10-最少实现规格冻结.md、11-体验移植路线图.md；仍只启动我们自己的新任务。
 */
public class HostEntry implements IXposedHookLoadPackage {

    private static final String TAG = Tags.PROBE;

    /** Generated from version.json by both build paths. */
    private static final String RUNTIME_MARKER = BuildIdentity.RUNTIME_MARKER;
    private static final String SYSTEMUI = Packages.SYSTEM_UI;
    private static final String DESKTOP_ICON_LAUNCH_ACTION = Actions.DESKTOP_ICON_LAUNCH;
    private static final long LAUNCH_DELAY_MS = 12_000L;   // 等开机稳定

    /** 试验方框的位置与尺寸（后面要换成自适应布局）。 */

    private static volatile Context sContext;
    private static volatile Object sTaskViewFactory;
    private static volatile Object sTaskViewTransitions;
    private static volatile Executor sShellExecutor;
    private static volatile boolean sStarted;

    private final TopFocusNotification topFocusNotification = new TopFocusNotification();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (ViewImageSource.supports(lpparam.packageName)) {
            if(lpparam.packageName.equals(lpparam.processName)) {
                try{ViewImageSource.install();}
                catch(Throwable unavailable){log("View image source unavailable: "+unavailable);}
            }
            return;
        }
        if (SystemExtractHooks.PACKAGE.equals(lpparam.packageName)) {
            SystemExtractHooks.install(lpparam.classLoader);
            return;
        }
        if (PortalWire.PACKAGE.equals(lpparam.packageName)) {
            PortalHooks.install(lpparam.classLoader);
            return;
        }
        if (Packages.SETTINGS.equals(lpparam.packageName)) {
            SystemSettingsEntry.install(lpparam.classLoader);
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            ContentPermissionBridge.install(lpparam.classLoader,()->sZoomScale>0 && sZoomScale<1f && !sKeyguardZoom.sleeping());
            PortalDragInput.install(lpparam.classLoader,()->sDisplayContent,HostEntry::dragShareActive);
            EditorPasteCompat.install(lpparam.classLoader,()->sDisplayContent,
                    ()->sZoomScale>0 && sZoomScale<1f && !sKeyguardZoom.sleeping());
            NativeContentDragCard.install(lpparam.classLoader,()->sDisplayContent,HostEntry::dragShareActive);
            NativeMultiWindowUnlock.install(lpparam.classLoader, true);
            hookSystemServer(lpparam.classLoader);
            return;
        }
        if ("com.xiaomi.type".equals(lpparam.packageName)) {
            // Optional keyboard policy only; ordinary app layout/input uses the system hooks.
            hookMiTypeAutoFloating(lpparam.classLoader);
            return;
        }
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            // Raw correction is owned by the system publisher; never invert it twice in apps.
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        log("=== attached to " + SYSTEMUI + " " + RUNTIME_MARKER);
        try {
            NativeMultiWindowUnlock.install(cl, false);
            NativeRecents.install(cl);
            IslandMonitorInput.install(cl, () -> sContext);
            StatusBarSampling.install(cl);
            StatusBar.install(cl);
            TopNativeCaption.install(cl);
            PanelInput.install(cl);
            BiometricWindow.install(cl);
            MainUiConfiguration.install(cl);
            NotificationLaunch.install(cl, () -> sContext);
            installRetainedWorkspaceWakeHooks(cl);
            NativeFreeformCoordinates.install(cl);
            NativeFreeformPreview.install(cl);
            hookApplication(cl);
            hookShellProviders(cl);
            hookRecentsWallpaper(cl);
            hookBackGestureDiagnostics(cl);
            hookTransitionBounds(cl);
        } catch (Throwable t) {
            log("hooks failed: " + t);
        }
    }

    private static void hookMiTypeAutoFloating(ClassLoader cl) {
        MiTypeHooks.install(cl, RUNTIME_MARKER, HostEntry::log);
    }

    // ---------------------------------------------------------------- v1.1.0：系统框架整屏缩放（R2 同款）

    private static volatile Object sDisplayContent;
    private static volatile Object sSystemContext;
    private static volatile boolean sZoomed;
    private static volatile ZoomTarget sZoomTarget;
    /** 缩放动画只在 WM handler 上创建/取消；帧内矩阵仍在 mGlobalLock 内应用。 */
    private static volatile ValueAnimator sZoomAnimator;
    private static volatile String sZoomAnimKey;
    private static volatile boolean sZoomFrameApply;
    private static volatile PathInterpolator zoomAnimInterpolator;
    private static volatile float sZoomScale = 1f, sZoomOffset, sZoomOffsetX;
    private static final long sZoomWatcherStarted = System.currentTimeMillis();
    private static final KeyguardZoom sKeyguardZoom = new KeyguardZoom();
    private static final ZoomChannelState sZoomChannels = new ZoomChannelState(sZoomWatcherStarted);
    private static volatile android.os.FileObserver sZoomFileObserver;
    private static volatile Handler sZoomBridgeHandler;
    private static volatile boolean sHostZoomed;
    private static volatile long sUnlockPhysicalShadeGeneration, sUnlockPhysicalShadeUntil;
    private static final RetainedWindow sRetainedWindow = new RetainedWindow();
    private static volatile String sZoomReadError;
    private static volatile String sZoomLastSeen;
    private static volatile String sZoomLastDispatched;
    private static volatile String sZoomLastTargetNull;
    private static ZoomCommit sLiveZoomCommit;
    private static long sLiveZoomIssued;
    private static volatile boolean sFullscreenRotationActive;
    private static volatile int sHeldDisplayRotation;
    private static volatile Object sFixedRotationApp;
    private static volatile int sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
    /** Accessed only under the WMS global lock; these are per-token, not display launches. */
    private static final java.util.Map<Object, Integer> sTaskRotations =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, java.lang.ref.WeakReference<Object>> sTaskRotationTransforms =
            new java.util.WeakHashMap<>();
    /** Only surfaces actually written by our WM magnification, under its global lock. */
    private static final java.util.Map<Object, Object> sMagnifiedSurfaces =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, Object> sMagnificationFinishReplays =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, Object> sHomeTransitionInfos =
            new java.util.WeakHashMap<>();
    private static final ThreadLocal<Boolean> sFinalMagnificationSnapshot = new ThreadLocal<>();
    private static final ThreadLocal<Object> sMagnificationFinishInfo = new ThreadLocal<>();
    private static final NativeFreeformStartGate sNativeFreeformStart = new NativeFreeformStartGate();
    private static final NativeFreeformStartGate sLiveSwapZoomSurfaces = new NativeFreeformStartGate();
    /** Per-task state survives top-activity changes and main/side ownership swaps. */
    private static final java.util.Map<Object, TaskWindowState> sTaskWindowStates =
            new java.util.WeakHashMap<>();
    private static java.lang.reflect.Method sTaskRotationResolver;
    private static boolean sUpdatingTaskRotation;
    private static final java.util.Map<Object, String> sGestureSurfaces =
            new java.util.WeakHashMap<>();

    static void setHostZoomed(boolean zoomed) {
        sHostZoomed = zoomed;
    }

    /**
     * 系统框架作用域（LSPosed 里要勾"系统框架"）：
     * 捕获默认显示器的 DisplayContent，收到 io.github.zerohomers0.onesteplite.ZOOM 广播时调用
     * DisplayContent.applyMagnificationSpec(scale/offset) —— 目标机自带的整屏缩放原语，
     * 与锤子OneStepLite的 MagnificationSpecSmt / applyZoomedMatrixSmt 是同一机制的官方版本。
     */
    private void hookSystemServer(ClassLoader cl) {
        log("=== attached to system_server (android) " + RUNTIME_MARKER);
        Recents.install(cl);
        NativeRawInputBridge.install();
        try {
            Class<?> dc = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(dc, "configureSurfaces", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    try {
                        Object type = XposedHelpers.callMethod(self, "getDisplayId");
                        if (type instanceof Integer && ((Integer) type).intValue() != 0) {
                            return;
                        }
                        sDisplayContent = self;
                        PortalDragInput.observeDisplay(self,cl,HostEntry::dragShareActive);
                        Object service = XposedHelpers.getObjectField(self, "mWmService");
                        Object lock = XposedHelpers.getObjectField(service, "mGlobalLock");
                        Object handler = XposedHelpers.getObjectField(service, "mH");
                        if (!(handler instanceof Handler)) {
                            throw new IllegalStateException("WMS.mH is not a Handler");
                        }
                        // configureSurfaces creates a new display surface even for the same DC.
                        // New identity invalidates deduplication after a real surface rebuild.
                        cancelZoomAnimation(null);
                        sZoomAnimKey = null;
                        sZoomTarget = new ZoomTarget(HostEntry.this, self, lock, (Handler) handler,
                                () -> sZoomTarget, error -> {
                                    log("WM zoom request stopped; no same-state retry: " + error);
                                    XposedBridge.log(error);
                                });
                        // Lock entry must be able to hide the opening main task in the
                        // same WM transaction that restores physical geometry, without
                        // waiting for the Host broadcast round trip.
                        sRetainedWindow.setGlobalLock(lock);
                        armZoomBridge(service);
                        dispatchCurrentZoom();
                        log("captured DisplayContent(display 0) with WM handler/global lock -> " + self);
                    } catch (Throwable t) {
                        sZoomTarget = null;
                        log("capture DisplayContent failed: " + t);
                    }
                }
            });
            log("DisplayContent#configureSurfaces hooked");
        } catch (Throwable t) {
            log("hookSystemServer hook failed: " + t);
            return;
        }
        RecentsInput.install(cl, () -> sZoomed
                ? new float[]{sZoomScale, sZoomOffset, sZoomOffsetX} : null);
        WallpaperScale.install(cl, () -> {
            ZoomTarget target = sZoomTarget;
            return sZoomed && target != null && target.display == sDisplayContent
                    ? target.display : null;
        });
        installMagnificationSurfaceTracking(cl);
        try {
            KeyguardZoom.Reset resetForLock=() -> {
                ZoomTarget target = sZoomTarget;
                if (target == null) return;
                synchronized (target.globalLock) {
                    if (target != sZoomTarget || target.display != sDisplayContent) return;
                    long now = System.currentTimeMillis();
                    synchronized (sZoomChannels) { sZoomChannels.offer("1 0 0", now, now); }
                    sUnlockPhysicalShadeGeneration = 0;
                    sUnlockPhysicalShadeUntil = 0;
                    target.dispatch.invalidateLastAttempt();
                    target.owner.cancelZoomAnimation(target);
                    if (!sZoomed) return;
                    Object tx = XposedHelpers.callMethod(target.display, "getPendingTransaction");
                    if (!sLiveSwapZoomSurfaces.begin(target.display, tx))
                        throw new IllegalStateException("native lock-entry surface scope busy");
                    try {
                        // Hide the opening main task before restoring physical
                        // geometry: the restore makes it full screen, and the Host
                        // only learns about the lock through a later broadcast.
                        sRetainedWindow.holdOpeningMainForLock(target.display);
                        target.owner.applyDisplayZoomLocked(target, 1f, 0f, 0f, null, true);
                        reapplyFinalMagnification(target.display, tx);
                    } finally { sLiveSwapZoomSurfaces.finish(target.display, tx); }
                    log("native keyguard entry restored physical geometry before visibility");
                }
            };
            sKeyguardZoom.install(cl,resetForLock);
            sKeyguardZoom.installSleep(cl,()->{
                Handler bridge=sZoomBridgeHandler;
                if(bridge!=null)bridge.post(()->{
                    if(!sKeyguardZoom.sleeping())return;
                    try{resetForLock.run();}catch(Throwable failure){log("early sleep geometry barrier: "+failure);}
                });
            });

            log("native keyguard zoom barrier armed");
        } catch (Throwable unavailable) { log("native keyguard zoom barrier unavailable: " + unavailable); }
        try {
            sRetainedWindow.install(cl, () -> sDisplayContent,
                    () -> !sKeyguardZoom.sleeping()&&EntryGuard.canOpen(sWmsContext), (tx, generation, scale, x, y) -> {
                // Visibility is readiness evidence only. The authenticated, correlated
                // Host handoff owns geometry and the release of all held main surfaces.
                log("native retained Host ready; waiting for correlated unlock commit generation="+generation);
                Context nativeContext=sWmsContext;Handler bridge=sZoomBridgeHandler;
                if(nativeContext!=null&&bridge!=null)bridge.post(()->nativeContext.sendBroadcast(
                        new Intent(RetainedWindow.READY).setPackage(SYSTEMUI).putExtra("generation",generation),
                        android.Manifest.permission.WRITE_SECURE_SETTINGS));

            });
        } catch (Throwable error) { log("native retained visibility hook unavailable: " + error); }
        hookMagnificationReplayAfterTransition(cl);
        installNativeFreeformCoexistence(cl);
        installFixedMainRotationHooks(cl);
        installMainSystemUiRotation(cl);
        installFixedMainTouchableCropHook(cl);
        installSideSystemBarPolicy(cl);
        installWorkspaceFocus(cl);
        installWorkspaceRunningTasks(cl);
        InputWindowMagnificationHooks.install(cl, () -> sZoomed, () -> sDisplayContent,
                () -> sUnlockPhysicalShadeGeneration, () -> sUnlockPhysicalShadeUntil,
                HostEntry::log);
        NavigationGestureSurfaceHooks.install(cl, new NavigationGestureSurfaceHooks.Host() {
            @Override public Context systemContext() { return (Context) sSystemContext; }
            @Override public java.util.Map<Object, String> gestureSurfaces() {
                return sGestureSurfaces;
            }
            @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
            @Override public float zoomScale() { return sZoomScale; }
            @Override public float zoomOffsetX() { return sZoomOffsetX; }
            @Override public float zoomOffsetY() { return sZoomOffset; }
            @Override public void applyGestureSurfaces(
                    ZoomTarget target, float scale, float offsetY, float offsetX) {
                HostEntry.this.applyGestureSurfacesLocked(target, scale, offsetY, offsetX);
            }
        }, HostEntry::log);
        TaskResizeableHooks.install(cl, () -> sFullscreenRotationActive,
                HostEntry::isSideTask, HostEntry::log);
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object ctx = thread == null ? null
                    : XposedHelpers.callMethod(thread, "getSystemContext");
            sSystemContext = ctx;
            log("system context -> " + ctx);
        } catch (Throwable t) {
            log("system context failed: " + t);
        }
        installDesktopIconLaunchBridge(cl);
        // 文件只作备用；所有通道共享有时限的最新请求，读取失败不能撤销有效广播。
        //   onesteplite-zoom 内容 "scale offsetY offsetX"（如 "0.7472 622 273"），"1 0 0" = 还原；
        //   动画行为 "anim <scale> <offsetY> <offsetX> <durationMs>"，从当前 spec 起步。
        // SystemUI 私有文件用 FileObserver 即时触发（宿主自己写，用于呼出动画与后续交换动画）；
        // 250ms 轮询只作兜底，并保留 adb/root 写 /data/system/onesteplite-zoom 的调试通道。
        try {
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            java.io.File f1 = new java.io.File("/data/system/onesteplite-zoom");
                            java.io.File f2 = new java.io.File(
                                    "/data/user_de/0/com.android.systemui/files/onesteplite-zoom");
                            java.io.File f = f1;
                            if (f2.exists() && (!f1.exists()
                                    || f2.lastModified() > f1.lastModified())) {
                                f = f2;
                            }
                            readAndDispatchZoom(f);
                            Thread.sleep(250L);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable t) {
                            try {
                                Thread.sleep(2000L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }, "onesteplite-zoom-watch");
            watcher.setDaemon(true);
            watcher.start();
            log("ZOOM lease watcher armed; transforms only on locked WMS handler");
        } catch (Throwable t) {
            log("ZOOM watcher failed: " + t);
        }
        installZoomFileObserver();
    }

    private static volatile boolean sZoomBridgeArmed;
    private static volatile android.content.Context sWmsContext;
    private static volatile String sLastSettingsZoom;
    private static boolean sZoomSettingsWatcherArmed;

    /** Settings.Global 通道：SystemUI 有 WRITE_SECURE_SETTINGS，system_server 读取不受文件权限影响。 */
    private static synchronized void startZoomSettingsWatcher() {
        if (sZoomSettingsWatcherArmed) return;
        try {
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        android.content.Context context = sWmsContext;
                        if (context != null) {
                            String value = android.provider.Settings.Global.getString(
                                    context.getContentResolver(), "onesteplite_zoom");
                            synchronized (sZoomChannels) {
                                long now = System.currentTimeMillis();
                                boolean accepted = sZoomChannels.offerEncoded(value, now);
                                if (accepted && !java.util.Objects.equals(value, sLastSettingsZoom)) {
                                    log("zoom settings received: " + value);
                                }
                                sLastSettingsZoom = value;
                                dispatchCurrentZoom();
                            }
                        }
                        Thread.sleep(200L);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignored) {
                        try { Thread.sleep(1000L); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }, "onesteplite-zoom-settings");
            thread.setDaemon(true);
            thread.start();
            sZoomSettingsWatcherArmed = true;
            log("zoom settings watcher armed");
        } catch (Throwable error) {
            log("zoom settings watcher failed: " + error);
        }
    }
    private static volatile boolean sDragShareEnabled=true;

    private static boolean dragShareActive() {
        return sDragShareEnabled && sZoomScale>0 && sZoomScale<1f && !sKeyguardZoom.sleeping();
    }

    /** 文件通道在部分机型被 SELinux/权限挡住；再开一条 SystemUI -> system_server 的广播直投。 */
    private static synchronized void armZoomBridge(Object service) {
        try {
            final Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            sWmsContext = context;
            VideoDock.install(service,context,service.getClass().getClassLoader());
            startZoomSettingsWatcher();
            if (sZoomBridgeArmed) return;
            android.os.HandlerThread thread = new android.os.HandlerThread("onesteplite-zoom-bridge");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            sZoomBridgeHandler = handler;
            sRetainedWindow.setHandler(handler);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    if (RetainedWindow.ACTION.equals(intent.getAction())) {
                        ZoomTarget target = sZoomTarget;
                        android.os.Bundle reply = null;
                        if (target != null) synchronized (target.globalLock) {
                            if (target == sZoomTarget && !EntryGuard.canOpen(context))
                                reply = sRetainedWindow.retain(target.display, intent);
                        }
                        setResultExtras(reply); setResultCode(reply == null ? 0 : -1);
                        return;
                    }
                    if (UnlockHandoff.RELEASE.equals(intent.getAction())) {
                        ZoomTarget target = sZoomTarget;
                        long generation = intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, 0);
                        boolean completed=intent.getBooleanExtra(UnlockHandoff.COMPLETED,true);
                        boolean workspace=intent.getBooleanExtra(UnlockHandoff.WORKSPACE,false);
                        float retainedScale=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE,0));
                        float retainedX=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X,0));
                        float retainedY=Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y,0));
                        if(workspace && (!Float.isFinite(retainedScale) || retainedScale<=0 || retainedScale>=1
                                || !Float.isFinite(retainedX) || !Float.isFinite(retainedY)))return;
                        if (target != null) target.handler.post(() -> {
                            synchronized (target.globalLock) {
                                if (target != sZoomTarget || generation == 0
                                        || generation != sUnlockPhysicalShadeGeneration) return;
                                sUnlockPhysicalShadeGeneration = 0;
                                try {
                                    Object tx = XposedHelpers.callMethod(target.display, "getPendingTransaction");
                                    boolean authenticated=!sKeyguardZoom.sleeping()&&EntryGuard.canOpen(context);
                                    // Failure/exit has no presented workspace. Restore physical
                                    // geometry before unmasking its main surface in the same tx.
                                    sRetainedWindow.finishAuthenticatedRelease(target.display,tx,authenticated,completed,workspace,()->{
                                        target.owner.applyDisplayZoomLocked(target,1f,0f,0f,null,true);
                                        reapplyFinalMagnification(target.display,tx);
                                    },()->{
                                        String retained=retainedScale+" "+retainedY+" "+retainedX;
                                        if(!sZoomChannels.supersedes(sLiveZoomIssued,retained))
                                            target.owner.applyDisplayZoomLocked(target,retainedScale,retainedY,retainedX);
                                        reapplyFinalMagnification(target.display,tx);
                                    });
                                    reapplyFinalMagnification(target.display, tx);
                                    XposedHelpers.callMethod(tx, "apply");
                                } catch (Throwable error) { log("unlock shade release unavailable: " + error); }
                            }
                        });
                        return;
                    }
                    if (BottomReplayInput.ACTION.equals(intent.getAction())) {
                        BottomReplayInput.setMode(intent.getIntExtra("mode", 0));
                        return;
                    }

                    if (WorkspaceBackdrop.ACTION.equals(intent.getAction())) {
                        ZoomTarget target=sZoomTarget;
                        if(target==null || target.display!=sDisplayContent)return;
                        try {
                            synchronized(target.globalLock) {
                                android.os.Bundle reply=WorkspaceBackdrop.capture(target.display,intent);
                                if(reply!=null) {setResultExtras(reply);setResultCode(-1);}
                            }
                        } catch(Throwable error) {log("workspace wallpaper parent rejected: "+error);}
                        return;
                    }
                    if(SwapOverlayBridge.ACTION.equals(intent.getAction())) {
                        try {
                            if(!sZoomed || sZoomTarget==null || sDisplayContent==null)return;
                            synchronized(sZoomTarget.globalLock) {
                                setResultExtras(SwapOverlayBridge.capture(sDisplayContent,intent));
                                setResultCode(-1);
                            }
                        } catch(Throwable unavailable) {log("live toolbox native capture refused: "+unavailable);}
                        return;
                    }
                    if (LiveSwapZoomProtocol.APPLY.equals(intent.getAction())) {
                        receiveLiveZoom(context, intent);
                        return;
                    }
                    if (InputTrace.ACTION.equals(intent.getAction())) {

                        if (intent.getBooleanExtra("stopInputTrace", false)) {
                            InputTrace.stop();
                            return;
                        }
                        ZoomTarget target = sZoomTarget;
                        if (target != null) target.handler.post(() -> {
                            if (target != sZoomTarget) return;
                            InputTrace.arm(context, true, target.display.getClass().getClassLoader());
                            synchronized (target.globalLock) {
                                InputTrace.captureDisplay(target.display);
                            }
                        });
                        return;
                    }
                    if (DesktopLaunchBridge.PREPARE_TOP.equals(intent.getAction())) {
                        boolean prepared=false;
                        if(sZoomed && sFullscreenRotationActive && sDisplayContent!=null)
                            synchronized(XposedHelpers.getObjectField(service,"mGlobalLock")) {
                                prepared=DesktopLaunchBridge.prepareTop(service,intent);
                            }
                        setResultCode(prepared ? -1 : 0);
                        return;
                    }
                    if (DesktopLaunchBridge.ACTION.equals(intent.getAction())) {
                        if (DesktopLaunchBridge.update(intent)) {
                            Recents.registryChanged(service);
                            DesktopLaunchBridge.refreshTopInput(service);
                            try {
                                if (sZoomed) synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                                    TopInsets.refresh(sDisplayContent, DesktopLaunchBridge::ownsTopTask);
                                    ZoomTarget target = sZoomTarget;
                                    if (sFullscreenRotationActive && target != null
                                            && target.display == sDisplayContent)
                                        target.owner.refreshTaskRotationLocked(sDisplayContent);
                                    XposedHelpers.callMethod(service, "updateFocusedWindowLocked", 0, true);
                                }
                            } catch (Throwable unavailable) {log("workspace lease focus refresh skipped: "+unavailable);}
                        }
                        return;
                    }
                    if (Actions.TRANSFER_TOUCH.equals(intent.getAction())) {
                        SideTouchTransfer.transfer(intent, () -> sZoomTarget, () -> sZoomScale, HostEntry::log);
                        return;
                    }
                    try {
                        synchronized (sZoomChannels) {
                            long now = System.currentTimeMillis();
                            String encoded = intent.getStringExtra("request");
                            if (encoded != null) {
                                sZoomChannels.offerEncoded(encoded, now);
                            } else {
                                // Preserve explicit adb diagnostics and older host senders.
                                float scale = intent.getFloatExtra("scale", 1f);
                                float y = intent.getFloatExtra("offsetY", 0f);
                                float x = intent.getFloatExtra("offsetX", 0f);
                                sZoomChannels.offer(scale + " " + y + " " + x, now, now);
                            }
                            dispatchCurrentZoom();
                        }
                    } catch (Throwable error) {
                        log("zoom broadcast submit failed: " + error);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Actions.ZOOM);
            filter.addAction(LiveSwapZoomProtocol.APPLY);
            filter.addAction(UnlockHandoff.RELEASE);
            filter.addAction(RetainedWindow.ACTION);
            filter.addAction(SwapOverlayBridge.ACTION);
            filter.addAction(Actions.TRANSFER_TOUCH);
            filter.addAction(InputTrace.ACTION);
            filter.addAction(BottomReplayInput.ACTION);
            filter.addAction(DesktopLaunchBridge.ACTION);
            filter.addAction(DesktopLaunchBridge.PREPARE_TOP);
            filter.addAction(WorkspaceBackdrop.ACTION);
            // SystemUI / shell can send; ordinary apps cannot change display magnification.
            // minSdk is 34, so the exported-receiver flag is always available.
            context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    handler, Context.RECEIVER_EXPORTED);
            sZoomBridgeArmed = true;
            BottomReplayInput.install(context, service.getClass().getClassLoader(), handler);
            log("zoom broadcast bridge armed");
            new WorkspaceSettingsClient(context,handler,options->sDragShareEnabled=options.dragShareEnabled);
        } catch (Throwable error) {
            log("zoom broadcast bridge failed: " + error);
        }
    }
    /** Snapshot operations only. Shell merges and applies them with the task handoff. */
    private static Object createMagnificationFinishReplay(Object display) throws Exception {
        Object replay = Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
        try {
            reapplyFinalMagnification(display, replay);
            return replay;
        } catch (Throwable failure) {
            LiveSwapZoom.closeReplay(replay);
            throw new IllegalStateException("current magnification snapshot failed", failure);
        }
    }

    private static void reapplyFinalMagnification(Object display, Object transaction) {
        reapplyFinalMagnification(display, transaction, null);
    }

    private static void reapplyFinalMagnification(Object display, Object transaction, Object info) {
        Boolean previous = sFinalMagnificationSnapshot.get();
        Object previousInfo = sMagnificationFinishInfo.get();
        sFinalMagnificationSnapshot.set(Boolean.TRUE);
        if (info == null) sMagnificationFinishInfo.remove();
        else sMagnificationFinishInfo.set(info);
        try { ShellCompat.call(display, "reapplyMagnificationSpec", transaction); }
        finally {
            if (previous == null) sFinalMagnificationSnapshot.remove();
            else sFinalMagnificationSnapshot.set(previous);
            if (previousInfo == null) sMagnificationFinishInfo.remove();
            else sMagnificationFinishInfo.set(previousInfo);
        }
    }

    private static void receiveLiveZoom(Context context, Intent intent) {
        long generation = intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, 0L);
        long issued = intent.getLongExtra(LiveSwapZoomProtocol.ISSUED, 0L);
        float scale = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0));
        float x = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0));
        float y = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0));
        ZoomTarget target = sZoomTarget;
        if (target == null || !LiveSwapZoomProtocol.valid(generation, scale, x, y,
                issued, System.currentTimeMillis())) return;
        LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(generation, scale, x, y);
        target.handler.post(() -> {
            ZoomCommit commit = new ZoomCommit(context, target, zoom,
                    () -> target == sZoomTarget && zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset),
                    HostEntry::createMagnificationFinishReplay,
                    value -> { if (sLiveZoomCommit == value) sLiveZoomCommit = null; },
                    message -> log(message));
            try {
                synchronized (target.globalLock) {
                    if (target != sZoomTarget || issued < sLiveZoomIssued) {
                        commit.finish(false, "stale zoom target or lease"); return;
                    }
                    synchronized (sZoomChannels) {
                        long now = System.currentTimeMillis();
                        if (!LiveSwapZoomProtocol.valid(generation, scale, x, y, issued, now)) {
                            commit.finish(false, "expired zoom lease"); return;
                        }
                        String command = scale + " " + y + " " + x;
                        sZoomChannels.offer(command, issued, now);
                        ZoomChannelState.Request current = sZoomChannels.current(now);
                        if (current.modified > issued && !command.equals(current.command)) {
                            commit.finish(false, "superseded zoom lease"); return;
                        }
                    }
                    if (sLiveZoomCommit != null) sLiveZoomCommit.finish(false, "superseded generation");
                    if (intent.getBooleanExtra(UnlockHandoff.PHYSICAL_KEYGUARD, false)) {
                        if (!sKeyguardZoom.onAuthenticatedReveal(EntryGuard.canOpen(context))) {
                            commit.finish(false, "unlock admission changed"); return;
                        }
                        sUnlockPhysicalShadeGeneration = generation;
                        sUnlockPhysicalShadeUntil = android.os.SystemClock.uptimeMillis()
                                + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
                    }
                    sLiveZoomIssued = issued;
                    sLiveZoomCommit = commit;
                    target.dispatch.takeOver(target, scale, y, x);
                    target.owner.cancelZoomAnimation(target);
                    // Logical WM parents already have their final roles, while the real
                    // leashes still animate under Host. Applying this traversal's clear
                    // operations now exposes an identity/fullscreen task for one frame.
                    // Commit the WM spec/input state; the exact visual traversal is already
                    // regenerated by ZoomCommit.finish and merged with Shell's reparent.
                    Object staged = Class.forName("android.view.SurfaceControl$Transaction")
                            .getDeclaredConstructor().newInstance();
                    if (!sLiveSwapZoomSurfaces.begin(target.display, staged)) {
                        LiveSwapZoom.closeReplay(staged);
                        throw new IllegalStateException("live zoom surface scope already active");
                    }
                    try { target.owner.applyDisplayZoomLocked(target, scale, y, x, commit); }
                    finally {
                        sLiveSwapZoomSurfaces.finish(target.display, staged);
                        LiveSwapZoom.closeReplay(staged);
                    }
                }
                target.handler.postDelayed(() -> commit.finish(false, "WMS transaction commit timeout"),
                        LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS - 100L);
            } catch (Throwable failure) {
                target.dispatch.invalidateLastAttempt();
                commit.finish(false, "WMS zoom apply failed: " + failure.getClass().getSimpleName());
            }
        });
    }
    /** watcher 与 FileObserver 共用：读文件、按租约解析、投递到 WM handler。 */
    private static void readAndDispatchZoom(java.io.File f) {
        String cur = "";
        long modified = 0L;
        Throwable readError = null;
        try {
            if (f != null) {
                modified = f.lastModified();
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(f));
                    cur = br.readLine();
                    br.close();
                }
            }
        } catch (Throwable error) {
            readError = error;
            if (sZoomReadError == null || !sZoomReadError.equals(String.valueOf(error))) {
                sZoomReadError = String.valueOf(error);
                log("zoom read error: " + error);
            }
            cur = "";
        }
        if (cur == null) cur = "";
        if (!cur.equals(sZoomLastSeen)) {
            sZoomLastSeen = cur;
            log("zoom cmd seen: '" + cur + "' modified=" + modified
                    + " target=" + (sZoomTarget != null) + " readError=" + readError
                    + " canRead=" + (f != null && f.canRead())
                    + " perms=" + (f != null && f.exists() ? Long.toOctalString(f.length()) : "-"));
        }
        long now = System.currentTimeMillis();
        synchronized (sZoomChannels) {
            sZoomChannels.offer(cur, modified, now);
            dispatchCurrentZoom();
        }
    }

    /** Replay on target creation and restore only when the shared host lease expires. */
    private static void dispatchCurrentZoom() {
        synchronized (sZoomChannels) {
            long now = System.currentTimeMillis();
            ZoomChannelState.Request selected = sZoomChannels.current(now);
            ZoomTarget target = sZoomTarget;
            if (target == null) {
                if (!selected.command.equals(sZoomLastTargetNull)) {
                    sZoomLastTargetNull = selected.command;
                    log("zoom request retained until display ready: " + selected.command);
                }
                return;
            }
            ZoomAnimCommand.Plan anim = ZoomAnimCommand.parse(selected.command,
                    selected.modified, sZoomWatcherStarted, now);
            if (anim != null) {
                target.submitAnim(anim, selected.animationKey);
            } else {
                float[] request = ZoomCommand.read(selected.command,
                        selected.modified, sZoomWatcherStarted, now);
                if (!selected.command.equals(sZoomLastDispatched)) {
                    sZoomLastDispatched = selected.command;
                    log("zoom dispatch " + java.util.Arrays.toString(request)
                            + " from leased request '" + selected.command + "'");
                }
                target.dispatch.submit(target, request[0], request[1], request[2]);
            }
        }
    }

    /** Preserve active WM magnification after Shell resets its transition targets. */
    private void hookMagnificationReplayAfterTransition(ClassLoader cl) {
        TransitionMagnificationStartHooks.install(cl, new TransitionMagnificationStartHooks.Host() {
            @Override public boolean zoomed() { return sZoomed; }
            @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
            @Override public Object displayContent() { return sDisplayContent; }
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
            @Override public float zoomScale() { return sZoomScale; }
            @Override public float zoomOffsetX() { return sZoomOffsetX; }
            @Override public float zoomOffsetY() { return sZoomOffset; }
        }, HostEntry::log);
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
                HostEntry.layerWorkspaceWithNativeFreeforms(display, transaction);
            }
        }, HostEntry::log);
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
                    HostEntry.this.onTopRotationTransitionEnd();
                }
                @Override public java.util.Map<Object, android.graphics.Rect> rotationBounds() {
                    return sTopRotationBounds;
                }
                @Override public boolean ownsTaskRotationTransform(Object page) {
                    return HostEntry.this.ownsTaskRotationTransform(page);
                }
                @Override public boolean isUpperLandscapeTask(Object task) {
                    return HostEntry.isUpperLandscapeTask(task);
                }
                @Override public void handoffSurface(Object page) {
                    topRotationHandoffs.add(page);
                }
            }, HostEntry::log);
            log("Transition#buildFinishTransaction magnification replay hooked");
        } catch (Throwable error) {
            log("transition magnification replay hook unavailable: " + error);
        }
    }

    private static void finishNativeFreeformStart(ZoomTarget target, Object start) {
        synchronized (target.globalLock) {
            if (!sNativeFreeformStart.finish(target.display, start) || target != sZoomTarget) return;
            try {
                if (sZoomed) {
                    Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
                    ShellCompat.call(target.display, "reapplyMagnificationSpec", transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } else restoreExitedMagnificationLocked(target);
                log("native freeform start committed; normal magnification writes resumed");
            } catch (Throwable error) { log("native freeform committed handoff skipped: " + error); }
        }
    }

    private static final SystemUiRotation sMainSystemUiRotation = new SystemUiRotation();
    private static final MainWindowClip sMainWindowClip = new MainWindowClip();
    private static final TopSystemBars sTopSystemBars = new TopSystemBars();
    private static final ImeProcessConfiguration sMainImeProcessConfiguration =
            new ImeProcessConfiguration();
    private static final ImeViewport sInteractiveImeViewport = new ImeViewport();
    private static Object sPublishedMainUiState;
    private boolean systemUiRotationFailureLogged;
    private final ImeTargetRefresh imeTargetRefresh = new ImeTargetRefresh();

    private void updateMainSystemUiRotation(Object window) {
        if (!sFullscreenRotationActive || !sZoomed) return;
        try {
            Object app = sFixedRotationApp;
            if (app != null && !sTaskRotations.containsKey(app)) app = null;
            publishMainUiConfiguration(app);
            if (TopRotation.phone(sDisplayContent) && ImeViewport.imeWindow(window)) {
                Object imeOwner = sMainSystemUiRotation.updatePhoneIme(window, sDisplayContent,
                        this::ownsTaskRotationTransform, w -> ImePolicy.isFocusedTopWindow(
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

    private static void publishMainUiConfiguration(Object app) {
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
        installTopSystemBarReturn(cl);
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
                                DesktopLaunchBridge::ownsTopTask, HostEntry.this::updateMainSystemUiRotation);
                    } catch (Throwable error) { log("interactive IME layering refresh unavailable: " + error); }
                }
            });
            Class<?> organizer = XposedHelpers.findClass("com.android.server.wm.WindowOrganizerController", cl);
            XposedBridge.hookAllMethods(organizer, "applyTaskChanges", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || p.args.length < 2) return;
                    captureMainBeforeSideChange(p.args[0],p.args[1]);
                    try { p.setObjectExtra("onesteplite.sideRole", SideRoleChange.enter(
                            p.args[0], p.args[1], sDisplayContent)); }
                    catch (Throwable error) { log("side role change observation unavailable: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object lease=p.getObjectExtra("onesteplite.sideRole");
                    try{if(lease!=null&&!p.hasThrowable()){
                        Object activity=XposedHelpers.callMethod(lease,"topRunningActivity");
                        if(activity!=null)applyFixedRotationLocked(sDisplayContent,activity,"side-role-committed");
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
                    refreshWideVideoSignal(p.thisObject);
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
            @Override public boolean zoomed() { return sZoomed; }
            @Override public Object displayContent() { return sDisplayContent; }
            @Override public ZoomTarget zoomTarget() { return sZoomTarget; }
            @Override public boolean finalSnapshot() {
                return Boolean.TRUE.equals(sFinalMagnificationSnapshot.get());
            }
            @Override public Object finishInfo() { return sMagnificationFinishInfo.get(); }
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
        }, HostEntry::log);
    }

    private static final PassBlurCoordinates sPassBlurCoordinates = new PassBlurCoordinates();

    private static void stagePassBlurCoordinates(Object display, Object transaction, Object spec) {
        ZoomTarget target = sZoomTarget;
        if (target == null || display != target.display || display != sDisplayContent
                || !Thread.holdsLock(target.globalLock)) return;
        try {
            sPassBlurCoordinates.stage(display, transaction,
                    spec == null ? 1f : XposedHelpers.getFloatField(spec, "scale"),
                    spec == null ? 0f : XposedHelpers.getFloatField(spec, "offsetX"),
                    spec == null ? 0f : XposedHelpers.getFloatField(spec, "offsetY"));
        } catch (Throwable error) {
            log("passblur coordinate publication unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void rememberMagnifiedSurface(Object container, Object spec) {
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || target == null || spec == null
                || target.display != sDisplayContent || !Thread.holdsLock(target.globalLock)) return;
        try {
            if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                    || XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != spec
                    || XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != spec) return;
            Object surface = XposedHelpers.getObjectField(container, "mSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                sMagnifiedSurfaces.put(container, surface);
        } catch (Throwable error) {
            log("magnified surface identity skipped: " + error);
        }
    }

    private static int restoreExitedMagnificationLocked(ZoomTarget target) {
        if (target == null || target != sZoomTarget || target.display != sDisplayContent
                || sZoomed || !Thread.holdsLock(target.globalLock)) return 0;
        int restored = 0;
        try {
            if (XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != null) return 0;
            Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
            java.util.Iterator<java.util.Map.Entry<Object, Object>> entries =
                    sMagnifiedSurfaces.entrySet().iterator();
            while (entries.hasNext()) {
                java.util.Map.Entry<Object, Object> entry = entries.next();
                Object container = entry.getKey(), surface = entry.getValue();
                try {
                    if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                            || XposedHelpers.getObjectField(container, "mSurfaceControl") != surface
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        entries.remove();
                        continue;
                    }
                    if (XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != null)
                        continue;
                    if (Integer.valueOf(5).equals(XposedHelpers.callMethod(container,
                                    "getWindowingMode"))
                            && XposedHelpers.callMethod(container, "asTask") != null) {
                        // WM has already released this canonical task leash. An OEM
                        // mini/freeform animation now owns its scale and position;
                        // an old main-region record must not reset it on entry/exit.
                        entries.remove();
                        continue;
                    }
                    Object position = XposedHelpers.getObjectField(container, "mLastSurfacePosition");
                    XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
                    XposedHelpers.callMethod(transaction, "setPosition", surface,
                            (float) XposedHelpers.getIntField(position, "x"),
                            (float) XposedHelpers.getIntField(position, "y"));
                    restored++;
                } catch (Throwable error) {
                    entries.remove();
                    log("exit surface restoration skipped: " + error);
                }
            }
            if (restored > 0) {
                XposedHelpers.callMethod(transaction, "apply");
                log("exit magnification surfaces restored=" + restored);
            }
        } catch (Throwable error) {
            log("exit magnification reconciliation unavailable: " + error);
        }
        return restored;
    }

    /** 宿主写 SystemUI 私有文件时立即处理，避免 250ms 轮询造成的桥/缩放不同帧。 */
    private static void installZoomFileObserver() {
        ZoomFileObserverHooks.install(observer -> sZoomFileObserver = observer,
                HostEntry::readAndDispatchZoom, HostEntry::log);
    }

    private void installTopSystemBarReturn(ClassLoader cl) {
        TopSystemBarReturnHooks.install(cl, sTopSystemBars, () -> sZoomTarget,
                () -> sZoomed, () -> sFullscreenRotationActive, HostEntry::log);
    }
    /** Hold the workspace portrait; app transforms must never announce display rotation. */
    private void installFixedMainRotationHooks(ClassLoader cl) {
        FixedRotationInsetsHooks.install(cl, sTopSystemBars, () -> sDisplayContent,
                () -> sZoomed, () -> sFullscreenRotationActive, HostEntry::log);
        FixedRotationConfigurationHooks.install(cl, () -> sDisplayContent,
                () -> sZoomed, () -> sFullscreenRotationActive,
                this::ownedSideRotationConfigurationLocked,
                this::ownedTopRotationConfigurationLocked, HostEntry::log);
        FixedRotationRelaunchHooks.install(cl, () -> sTopRotationRelaunchScopes,
                this::keepTopActivityForRotationConfig,
                this::preserveTopWindowForRelaunch, HostEntry::log);
        TaskRotationLifecycleHooks.install(cl, new TaskRotationLifecycleHooks.Host() {
            @Override public Boolean applyFixedRotation(
                    Object display, Object activity, String source) {
                return HostEntry.this.applyFixedRotationLocked(display, activity, source);
            }
            @Override public void beforeApplicationOrientationRequest(Object activity, int request) {
                HostEntry.this.beforeApplicationOrientationRequest(activity, request);
            }
            @Override public boolean updatingTaskRotation() { return sUpdatingTaskRotation; }
            @Override public java.util.Map<Object, Integer> taskRotations() { return sTaskRotations; }
            @Override public boolean rotationActive() { return sFullscreenRotationActive; }
            @Override public Object displayContent() { return sDisplayContent; }
            @Override public boolean retainTaskRotationOnNativeFinish(Object activity) {
                return HostEntry.this.retainTaskRotationOnNativeFinishLocked(activity);
            }
            @Override public void setTaskRotationResolver(Method resolver) {
                sTaskRotationResolver = resolver;
            }
            @Override public int heldRotation() { return sHeldDisplayRotation; }
            @Override public void queueWorkspaceRotationRefresh(Object displayRotation) {
                HostEntry.this.queueWorkspaceRotationRefresh(displayRotation);
            }
            @Override public void evaluateNativeLandscape(Object display, String source) {
                HostEntry.this.evaluateNativeLandscapeLocked(display, source);
            }
            @Override public void refreshTaskRotation(Object display) {
                HostEntry.this.refreshTaskRotationLocked(display);
            }
        }, HostEntry::log);
    }

    /** Root-task bounds remain in display orientation while our main token is rotated. */
    private void installFixedMainTouchableCropHook(ClassLoader cl) {
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "cropRegionToRootTaskBoundsIfNeeded",
                    android.graphics.Region.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.graphics.Region
                            && cropFixedMainTouchableRegion(p.thisObject,
                                    (android.graphics.Region) p.args[0])) p.setResult(null);
                }
            });
            log("owned fixed main: root touch crop uses rotated display coordinates");
        } catch (Throwable error) {
            log("fixed main touch crop hook unavailable: " + error);
        }
    }

    private static boolean cropFixedMainTouchableRegion(Object window,
            android.graphics.Region region) {
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || !sFullscreenRotationActive || window == null || region == null
                || target == null || target.display != sDisplayContent
                || !Thread.holdsLock(target.globalLock)) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != sDisplayContent)
                return false;
            Object activity = XposedHelpers.getObjectField(window, "mActivityRecord");
            if (activity == null || activity != sFixedRotationApp) return false;
            Integer rotation = sTaskRotations.get(activity);
            if (rotation == null || ((rotation - sHeldDisplayRotation) & 1) != 1) return false;
            Object task = XposedHelpers.callMethod(window, "getTask");
            if (task == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return false;
            Object root = XposedHelpers.callMethod(task, "getRootTask");
            if (root == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(root, "getWindowingMode"))) return false;
            Rect displayBounds = (Rect) XposedHelpers.callMethod(sDisplayContent, "getBounds");
            Rect rootBounds = (Rect) XposedHelpers.callMethod(root, "getBounds");
            Rect rotatedBounds = (Rect) XposedHelpers.callMethod(activity,
                    "getFixedRotationTransformDisplayBounds");
            if (displayBounds == null || !displayBounds.equals(rootBounds)
                    || rotatedBounds == null || rotatedBounds.isEmpty()
                    || rotatedBounds.width() != displayBounds.height()
                    || rotatedBounds.height() != displayBounds.width()) return false;
            // Keep letterbox exclusions and nonrectangular regions. Only change the
            // coordinate system of this full-display root intersection.
            region.op(rotatedBounds, android.graphics.Region.Op.INTERSECT);
            return true;
        } catch (Throwable error) {
            log("fixed main touch crop skipped: " + error);
            return false;
        }
    }

    /** Capture before WCT changes mode/bounds and dispatches any client configuration. */
    private void captureMainBeforeSideChange(Object task,Object change){
        try{
            if(!SideRoleChange.targetsSide(task,change,sDisplayContent)
                    ||!Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode")))return;
            Object activity=XposedHelpers.callMethod(task,"topRunningActivity");
            if(activity==null)return;
            Integer rotation=sTaskRotations.get(activity);
            if(rotation!=null&&(rotation==1||rotation==3)){
                int request=(Integer)XposedHelpers.callMethod(activity,"getOverrideOrientation");
                recordTaskWindowState(activity,request,rotation,rotation,1,"before-main-to-side-owned");
            }else captureTaskWindowState(sDisplayContent,activity,"before-main-to-side-native");
        }catch(Throwable error){log("main-to-side direction snapshot unavailable: "+error);}
    }

    private void beforeApplicationOrientationRequest(Object activity, int request) {
        if (!sFullscreenRotationActive || sUpdatingTaskRotation || activity == null
                || FullscreenRotationPolicy.requestsLandscape(request) || request == 14) return;
        try {
            if (XposedHelpers.getObjectField(activity, "mDisplayContent") != sDisplayContent) return;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null || Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return;
            if (XposedHelpers.callMethod(task, "topRunningActivity") != activity) return;
            // Relayout may repeat the same request during mode1 -> mode6. It is
            // not an application exit from fullscreen and must not erase direction.
            if (Integer.valueOf(request).equals(XposedHelpers.callMethod(activity,
                    "getOverrideOrientation"))) return;
            TaskWindowState state = sTaskWindowStates.get(task);
            if (state != null) state.forgetRememberedRotation();
        } catch (Throwable error) {
            log("application orientation history reset unavailable: " + error);
        }
    }

    private Boolean applyFixedRotationLocked(Object display, Object activity, String source) {
        if (!sFullscreenRotationActive || display == null || display != sDisplayContent
                || activity == null) return null;
        if (sUpdatingTaskRotation) return Boolean.FALSE;
        sUpdatingTaskRotation = true;
        try {
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "isActivityTypeHome"));
            boolean multiWindow = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "inMultiWindowMode"));
            boolean sideTask = isSideFixedTask(activity);
            Object task = XposedHelpers.callMethod(activity, "getTask");
            int windowingMode = task == null ? 1
                    : (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            if (isUpperLandscapeTask(task)) {
                if (XposedHelpers.callMethod(task,"topRunningActivity")==activity) {
                    TaskRotationResolution upper=resolveTaskRotationLocked(display,activity,task,windowingMode,source);
                    recordTaskWindowState(activity,upper.overrideOrientation,upper.resolvedRotation,
                            upper.taskRotation,6,source+" upper-landscape");
                }
                if (retainSharedTopRotationLocked(activity)) return Boolean.FALSE;
                if (retainClosingTopRotationLocked(activity, task)) return Boolean.FALSE;
                if (isLiveTopPageLocked(activity, task))
                    return applyTopRotationLocked(display, activity, task, source);
                releaseTaskRotationLocked(activity,"upper task uses native landscape bounds");
                return Boolean.FALSE;
            }
            if (windowingMode == FullscreenRotationPolicy.WINDOWING_MODE_FREEFORM) {
                // HyperOS owns native freeform configuration, surfaces and rotation.
                // Never turn its orientation callback into our fullscreen/TaskView path.
                captureTaskWindowState(display, activity, source + " native-freeform");
                releaseTaskRotationLocked(activity, "returned to native freeform owner");
                return null;
            }
            if (sTopRotationBounds.containsKey(activity)
                    && !releaseTaskRotationLocked(activity, "TOP returned to main/ordinary side role"))
                return Boolean.FALSE;
            // Role changes reconfigure the whole back stack. Only its running top
            // activity can define this task's direction or replace the main owner.
            // A portrait page underneath a landscape video must not cancel that
            // video's token transform while mode1/mode6 is being exchanged.
            if (task != null && XposedHelpers.callMethod(task, "topRunningActivity") != activity) {
                return Boolean.FALSE;
            }
            if (home || (multiWindow && !sideTask)) {
                // A promoted token keeps its local transform in the side slot. Do not
                // let WM promote that transform to mFixedRotationLaunchingApp.
                Object previous = sFixedRotationApp;
                if (previous != null) {
                    if (!isSideFixedTask(previous)) {
                        releaseTaskRotationLocked(previous,
                                home ? "main activity left for home" : "main activity left fullscreen");
                    }
                    if (sFixedRotationApp == previous) sFixedRotationApp = null;
                }
                return Boolean.FALSE;
            }
            if (NativeLandscapePolicy.suppressesLocalRotation(
                    sNativeLandscapeState, sideTask, home)) {
                // The workspace is temporarily suspended while the native display owns
                // the full landscape app. Side tasks were returned to system ownership.
                return Boolean.FALSE;
            }
            Object previous = sFixedRotationApp;
            if (!sideTask && previous != null && previous != activity) {
                if (!isSideFixedTask(previous)) {
                    if (!releaseTaskRotationLocked(previous, "main activity changed"))
                        return Boolean.FALSE;
                }
                sFixedRotationApp = null;
            }
            TaskRotationResolution resolution = resolveTaskRotationLocked(
                    display, activity, task, windowingMode, source);
            int overrideOrientation = resolution.overrideOrientation;
            int resolvedRotation = resolution.resolvedRotation;
            int rotation = resolution.taskRotation;
            if (resolution.lands() && (sHeldDisplayRotation & 1) == 0
                    && NativeLandscapePolicy.shouldUseNativeFullscreen(
                    sZoomed, sideTask, home)) {
                recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                        rotation, 1, source + " native-fullscreen");
                requestNativeLandscapeLocked(activity, source);
                return Boolean.FALSE;
            }
            recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                    rotation, sideTask ? 6 : 1, source);
            if (rotation == sHeldDisplayRotation) {
                Integer oldRotation = sTaskRotations.get(activity);
                boolean owned = ownsTaskRotationTransform(activity);
                boolean released = releaseTaskRotationLocked(activity, source + " -> portrait/locked");
                if (released && owned && oldRotation != null)
                    animateTaskRotationLocked(activity, oldRotation, rotation);
                return Boolean.FALSE;
            }
            boolean transformed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "hasFixedRotationTransform"));
            Integer existingRotation = sTaskRotations.get(activity);
            java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
            if (transformed && existingRotation != null && lease != null
                    && lease.get() != XposedHelpers.getObjectField(
                    activity, "mFixedRotationTransformState")) {
                releaseTaskRotationLocked(activity, "native transform owner changed");
                return Boolean.FALSE;
            }
            if (transformed && existingRotation == null) {
                // Leave a native, in-flight rotation under its original owner's lifecycle.
                return Boolean.FALSE;
            }
            if (!transformed || existingRotation == null || existingRotation != rotation) {
                if (sTaskRotations.containsKey(activity)
                        && !releaseTaskRotationLocked(activity, "task direction changed"))
                    return Boolean.FALSE;
                if (!sideTask) sFixedRotationApp = activity;
                sTaskRotations.put(activity, rotation);
                try {
                    Object previousApplying=sApplyingSideRotationActivity;
                    sApplyingSideRotationActivity=sideTask?activity:null;
                    try{XposedHelpers.callMethod(display,"startFixedRotationTransform",activity,rotation);}
                    finally{sApplyingSideRotationActivity=previousApplying;}
                    Object installed = XposedHelpers.getObjectField(
                            activity, "mFixedRotationTransformState");
                    if (installed == null) throw new IllegalStateException("native transform not installed");
                    sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(installed));
                    animateTaskRotationLocked(activity,
                            existingRotation == null ? sHeldDisplayRotation : existingRotation, rotation);
                } catch (Throwable error) {
                    releaseTaskRotationLocked(activity, "failed transform cleanup");
                    throw error;
                }
                log("task rotation: task=" + XposedHelpers.callMethod(activity, "getRootTaskId")
                        + " request=" + overrideOrientation + " resolved=" + resolvedRotation
                        + " rotationLocked=" + resolution.rotationLocked
                        + " held=" + sHeldDisplayRotation
                        + " existing=" + existingRotation + " taskRotation=" + rotation
                        + " localToken=true source=" + source);
            } else {
                if (!sideTask) sFixedRotationApp = activity;
            }
            return Boolean.TRUE;
        } catch (Throwable error) {
            log("fixed rotation apply failed source=" + source + ": " + error);
            XposedBridge.log(error);
            return Boolean.FALSE;
        } finally {
            sUpdatingTaskRotation = false;
        }
    }

    private static Object sApplyingSideRotationActivity;
    private android.content.res.Configuration ownedSideRotationConfigurationLocked(Object activity,Object task){
        if(!SideRoleChange.isSide(task)||isUpperLandscapeTask(task))return null;
        TaskWindowState history=sTaskWindowStates.get(task);Integer rotation=sTaskRotations.get(activity);
        if(history==null||!history.inheritsMainLandscape()||rotation==null||(rotation!=1&&rotation!=3))return null;
        Object state=XposedHelpers.getObjectField(activity,"mFixedRotationTransformState");
        java.lang.ref.WeakReference<Object> lease=sTaskRotationTransforms.get(activity);
        if(state==null||(activity!=sApplyingSideRotationActivity&&(lease==null||lease.get()!=state)))return null;
        return (android.content.res.Configuration)XposedHelpers.getObjectField(state,"mRotatedOverrideConfiguration");
    }

    private TaskRotationResolution resolveTaskRotationLocked(Object display, Object activity,
            Object task, int windowingMode, String source) throws Throwable {
        int overrideOrientation = (Integer) XposedHelpers.callMethod(
                activity, "getOverrideOrientation");
        TaskWindowState state = sTaskWindowStates.get(task);
        if (state == null) {
            captureTaskWindowState(display, activity, source + " initial");
            state = sTaskWindowStates.get(task);
        }
        boolean targetSide=SideRoleChange.isSide(task);
        if(targetSide)windowingMode=6;
        int rememberedRotation = state == null ? TaskWindowState.UNKNOWN_ROTATION
                : targetSide&&state.inheritsMainLandscape()?state.inheritedMainRotation():state.preferredRememberedRotation();
        boolean rotationLocked = isSystemRotationLocked(display);
        int lastRotation = sTaskRotations.containsKey(activity)
                ? sTaskRotations.get(activity)
                : rememberedRotation >= 0 ? rememberedRotation : sHeldDisplayRotation;
        int resolvedRotation = sHeldDisplayRotation;
        if (!rotationLocked
                || !FullscreenRotationPolicy.usesSensorOrUserRotation(overrideOrientation)
                || FullscreenRotationPolicy.requestsLandscape(overrideOrientation)) {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // Invoke WM's original resolver, bypassing only our display-hold hook.
            // This retains OEM policy, current-user settings and sensor filtering.
            resolvedRotation = (Integer) XposedBridge.invokeOriginalMethod(
                    sTaskRotationResolver, displayRotation,
                    new Object[]{overrideOrientation, lastRotation});
        }
        int rotation = FullscreenRotationPolicy.taskRotation(
                overrideOrientation, resolvedRotation, rotationLocked,
                windowingMode, rememberedRotation, sHeldDisplayRotation,
                state!=null&&state.inheritsMainLandscape());
        return new TaskRotationResolution(
                overrideOrientation, resolvedRotation, rotation, rotationLocked);
    }

    private void requestNativeLandscapeLocked(Object activity, String source) {
        int next = NativeLandscapePolicy.onLandscapeRequested(sNativeLandscapeState);
        if (next == sNativeLandscapeState) return;
        sNativeLandscapeState = next;
        sendNativeLandscapeMode(true);
        log("native landscape requested: task="
                + XposedHelpers.callMethod(activity, "getRootTaskId") + " source=" + source);
    }

    private void evaluateNativeLandscapeLocked(Object display, String source) {
        if (sNativeLandscapeState != NativeLandscapePolicy.FULLSCREEN
                || display == null || display != sDisplayContent) return;
        try {
            Object top = XposedHelpers.callMethod(display, "topRunningActivity");
            if (top == null) return;
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    top, "isActivityTypeHome"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int rotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            boolean landscape = rotation == 1 || rotation == 3;
            if (!NativeLandscapePolicy.needsResume(
                    sNativeLandscapeState, landscape, home)) return;
            sNativeLandscapeState = NativeLandscapePolicy.onResumeRequested(
                    sNativeLandscapeState);
            sendNativeLandscapeMode(false);
            log("restore workspace after native landscape: rotation=" + rotation
                    + " home=" + home + " source=" + source);
        } catch (Throwable error) {
            log("native landscape restore check failed: " + error);
        }
    }

    private void sendNativeLandscapeMode(boolean fullscreen) {
        try {
            Object rawContext = sSystemContext != null ? sSystemContext : sWmsContext;
            Context context = (Context) rawContext;
            if (context == null) throw new IllegalStateException("system context unavailable");
            final Intent intent = new Intent(WorkspaceHost.LANDSCAPE_MODE_ACTION)
                    .setPackage(SYSTEMUI).putExtra("fullscreen", fullscreen);
            final Handler bridge = sZoomBridgeHandler;
            final Runnable send = () -> {
                try {
                    context.sendBroadcast(intent,
                            android.Manifest.permission.WRITE_SECURE_SETTINGS);
                } catch (Throwable error) {
                    log("native landscape bridge send failed: " + error);
                }
            };
            // requestNativeLandscapeLocked runs under WindowManagerGlobalLock.
            // A synchronous broadcast can invert with ActivityManagerService and
            // deadlock system_server, so hand it to the existing private bridge thread.
            if (bridge != null) bridge.post(send);
            else new Thread(send, "onesteplite-landscape-bridge").start();
        } catch (Throwable error) {
            sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
            log("native landscape bridge failed: " + error);
        }
    }

    private boolean retainTaskRotationOnNativeFinishLocked(Object activity) {
        if (!sFullscreenRotationActive || sUpdatingTaskRotation || activity == null) return false;
        // This hook belongs to WindowToken, including IME/Toast tokens with no task.
        // WindowContainer returns null; only ActivityRecord returns itself.
        if (XposedHelpers.callMethod(activity, "asActivityRecord") == null) return false;
        // Native linkFixedRotationTransform shares one state with the previous page.
        // Even a finishing old page must not reset the current page's entire association.
        if (retainSharedTopRotationLocked(activity)) return true;
        if (!sTaskRotations.containsKey(activity)) return false;
        try {
            java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (lease == null || state == null || lease.get() != state
                    || XposedHelpers.getObjectField(activity, "mDisplayContent") != sDisplayContent
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                    || XposedHelpers.callMethod(activity, "getParent") == null) return false;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing")))
                return retainClosingTopRotationLocked(activity, task);
            if (isLiveTopPageLocked(activity, task))
                return Integer.valueOf(resolveTopRotationLocked(sDisplayContent,
                        XposedHelpers.callMethod(task, "topRunningActivity")))
                        .equals(sTaskRotations.get(activity));
            if (task == null || XposedHelpers.callMethod(task, "topRunningActivity") != activity
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isActivityTypeHome")))
                return false;
            if (isUpperLandscapeTask(task))
                return sZoomed && TopRotation.phone(sDisplayContent)
                        && Integer.valueOf(resolveTopRotationLocked(sDisplayContent, activity))
                                .equals(sTaskRotations.get(activity));
            int mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            boolean side = isSideFixedTask(activity);
            if ((mode != 1 && !side) || NativeLandscapePolicy.suppressesLocalRotation(
                    sNativeLandscapeState, side, false)) return false;
            if (!side && (activity != sFixedRotationApp || !sZoomed
                    || XposedHelpers.callMethod(sDisplayContent, "topRunningActivity") != activity
                    && !DesktopLaunchBridge.rotationMain(sDisplayContent, activity)))
                return false;
            TaskRotationResolution desired = resolveTaskRotationLocked(
                    sDisplayContent, activity, task, mode, "native-transform-finish");
            return desired.taskRotation != sHeldDisplayRotation
                    && Integer.valueOf(desired.taskRotation).equals(sTaskRotations.get(activity));
        } catch (Throwable error) {
            log("task rotation lifetime check unavailable: " + error);
            return false;
        }
    }

    private final java.util.Set<Object> closingTopRotations =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.Set<Object> topRotationHandoffs =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private boolean retainClosingTopRotationLocked(Object activity, Object task) {
        if (!sZoomed || !sFullscreenRotationActive || !sTopRotationBounds.containsKey(activity)
                || !isUpperLandscapeTask(task) || !TopRotation.phone(sDisplayContent)
                || !ownsTaskRotationTransform(activity)
                || XposedHelpers.getObjectField(activity, "mDisplayContent") != sDisplayContent
                || !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing"))
                || Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                || XposedHelpers.callMethod(activity, "getParent") == null
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "inTransition"))) return false;
        Object surface = XposedHelpers.callMethod(activity, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(
                XposedHelpers.callMethod(surface, "isValid"))) return false;
        closingTopRotations.add(activity);
        return true;
    }

    private void reconcileTopRotationTransitionsLocked(Object display) {
        if (display != sDisplayContent) return;
        if (!sFullscreenRotationActive) {
            releaseAllTaskRotationsLocked("transition completed after workspace exit");
            return;
        }
        for (Object page : new java.util.ArrayList<>(closingTopRotations)) {
            Object task = XposedHelpers.callMethod(page, "getTask");
            if (retainClosingTopRotationLocked(page, task)) continue;
            if (XposedHelpers.callMethod(page, "getParent") == null
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(page, "finishing"))) {
                if (retainSharedTopRotationLocked(page)) continue;
                if (sTaskRotations.containsKey(page)
                        && !releaseTaskRotationLocked(page, "TOP page transition ended")) continue;
            }
            closingTopRotations.remove(page);
        }
        for (Object page : new java.util.ArrayList<>(topRotationHandoffs)) {
            if (RotationAnimation.inTransition(page)) continue;
            topRotationHandoffs.remove(page);
            applyFixedRotationLocked(display, page, "TOP native transition settled");
        }
    }

    private void onTopRotationTransitionEnd() {
        Object display = sDisplayContent;
        Handler handler = sZoomBridgeHandler;
        if (display == null || handler == null) return;
        if (sFullscreenRotationActive
                ? closingTopRotations.isEmpty() && topRotationHandoffs.isEmpty()
                : sTaskRotations.isEmpty() && !RotationAnimation.hasOwners()) return;
        int epoch = inactiveRotationCleanupEpoch;
        handler.post(() -> {
            try {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (epoch != inactiveRotationCleanupEpoch) return;
                    reconcileTopRotationTransitionsLocked(display);
                }
            } catch (Throwable error) { log("TOP transition reconciliation unavailable: " + error); }
        });
    }

    private boolean isLiveTopPageLocked(Object activity, Object task) {
        if (!sZoomed || !sFullscreenRotationActive || activity == null
                || !isUpperLandscapeTask(task) || !TopRotation.phone(sDisplayContent))
            return false;
        Object top = XposedHelpers.callMethod(task, "topRunningActivity");
        return top != null
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "finishing"))
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "mRemovingFromDisplay"))
                && XposedHelpers.callMethod(top, "getParent") != null
                && XposedHelpers.getObjectField(activity, "mDisplayContent") == sDisplayContent
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing"))
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                && XposedHelpers.callMethod(activity, "getParent") != null
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isActivityTypeHome"));
    }

    private boolean adoptSharedTopRotationLocked(Object activity, Object task, Object state,
            int desired) {
        if (state == null || !sZoomed || !sFullscreenRotationActive
                || desired == sHeldDisplayRotation || !isUpperLandscapeTask(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        for (Object peer : new java.util.ArrayList<>(sTopRotationBounds.keySet())) {
            if (!bounds.equals(sTopRotationBounds.get(peer))
                    || !Integer.valueOf(desired).equals(sTaskRotations.get(peer))
                    || XposedHelpers.callMethod(peer, "getTask") != task
                    || !ownsTaskRotationTransform(peer)
                    || XposedHelpers.getObjectField(peer, "mFixedRotationTransformState") != state)
                continue;
            sTaskRotations.put(activity, desired);
            sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(state));
            sTopRotationBounds.put(activity, new Rect(bounds));
            return true;
        }
        return false;
    }

    private boolean retainSharedTopRotationLocked(Object activity) {
        if (!sZoomed || !sFullscreenRotationActive || activity == null) return false;
        try {
            if (XposedHelpers.getObjectField(activity, "mDisplayContent") != sDisplayContent
                    || !TopRotation.phone(sDisplayContent)) return false;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (!isUpperLandscapeTask(task)) return false;
            Object top = XposedHelpers.callMethod(task, "topRunningActivity");
            if (top == null || top == activity
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "finishing"))
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "mRemovingFromDisplay"))
                    || XposedHelpers.callMethod(top, "getParent") == null) return false;
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            return state != null
                    && XposedHelpers.getObjectField(top, "mFixedRotationTransformState") == state
                    && adoptSharedTopRotationLocked(top, task, state,
                            resolveTopRotationLocked(sDisplayContent, top));
        } catch (Throwable error) {
            log("TOP shared rotation lifetime unavailable: " + error);
            return false;
        }
    }

    private android.content.res.Configuration ownedTopRotationConfigurationLocked(
            Object activity, Object task) throws Throwable {
        android.content.res.Configuration applying =
                TopRotation.applyingConfiguration(activity, task);
        if (applying != null) return applying;
        if (!sZoomed || !sFullscreenRotationActive || !isUpperLandscapeTask(task)
                || !TopRotation.phone(sDisplayContent)) return null;
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        if (state == null) return null;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if ((!ownsTaskRotationTransform(activity)
                || !bounds.equals(sTopRotationBounds.get(activity)))
                && !adoptSharedTopRotationLocked(activity, task, state,
                        resolveTopRotationLocked(sDisplayContent, activity))) return null;
        return (android.content.res.Configuration) XposedHelpers.getObjectField(
                state, "mRotatedOverrideConfiguration");
    }

    private boolean releaseTaskRotationLocked(Object activity, String reason) {
        if (!sTaskRotations.containsKey(activity)) return false;
        boolean wasUpdating = sUpdatingTaskRotation;
        sUpdatingTaskRotation = true;
        try {
            java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            java.util.ArrayList<Object> members = new java.util.ArrayList<>();
            members.add(activity);
            if (state != null && lease != null && lease.get() == state) {
                for (Object peer : new java.util.ArrayList<>(sTaskRotations.keySet())) {
                    if (peer != activity && ownsTaskRotationTransform(peer)
                            && XposedHelpers.getObjectField(peer, "mFixedRotationTransformState")
                                    == state) members.add(peer);
                }
            }
            try {
                for (Object member : members) {
                    if (!RotationAnimation.cancel(member)) {
                        log("local rotation animation still owned; defer transform release");
                        return false;
                    }
                }
            } catch (Throwable error) {
                log("local rotation animation cleanup unavailable: " + error);
                return false;
            }
            if (state != null && (lease == null || lease.get() == state)) {
                Object display = XposedHelpers.callMethod(activity, "getDisplayContent");
                for (Object member : members) {
                    if (sTopRotationBounds.containsKey(member))
                        sTopRotationConfigurationRelease.add(member);
                }
                try {
                    if (display != null && TopRotation.phone(display))
                        RotationAnimation.finishLocalRotation(activity);
                    else XposedHelpers.callMethod(activity, "finishFixedRotationTransform");
                } finally {
                    sTopRotationConfigurationRelease.removeAll(members);
                }
            }
            for (Object member : members) {
                closingTopRotations.remove(member);
                topRotationHandoffs.remove(member);
                sTaskRotations.remove(member);
                sTaskRotationTransforms.remove(member);
                sTopRotationBounds.remove(member);
                if (sFixedRotationApp == member) sFixedRotationApp = null;
            }
            log("task rotation released: " + reason);
            return true;
        } catch (Throwable error) {
            log("fixed rotation release failed: " + reason + ": " + error);
            return false;
        } finally {
            sUpdatingTaskRotation = wasUpdating;
        }
    }

    /** Keep orientation/windowing facts with the task, rather than the current top activity. */
    private void recordTaskWindowState(Object activity, int requestedOrientation,
            int resolvedRotation, int taskRotation, int fallbackWindowingMode, String source) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null) return;
            int taskId = (Integer) XposedHelpers.callMethod(activity, "getRootTaskId");
            int windowingMode = fallbackWindowingMode;
            try {
                windowingMode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            // Synchronous WCT callbacks can still report source mode1. The
            // pending target role already owns their configuration observations.
            if(SideRoleChange.isPending(task))windowingMode=6;
            int configurationOrientation = 0;
            try {
                Object config = XposedHelpers.callMethod(activity, "getConfiguration");
                configurationOrientation = XposedHelpers.getIntField(config, "orientation");
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            TaskWindowState state = sTaskWindowStates.get(task);
            if (state == null) {
                state = new TaskWindowState(taskId);
                sTaskWindowStates.put(task, state);
            }
            state.update(-1, configurationOrientation, requestedOrientation,
                    resolvedRotation == -1 ? taskRotation : resolvedRotation,
                    windowingMode, windowingMode == 1, true, taskRotation);
            sendTopTaskDirection(task,taskId,requestedOrientation,resolvedRotation,taskRotation,windowingMode);
            log("task window state task=" + taskId + " mode=" + windowingMode
                    + " configOrientation=" + configurationOrientation
                    + " request=" + requestedOrientation + " resolved=" + resolvedRotation
                    + " localRotation=" + taskRotation + " source=" + source);
        } catch (Throwable error) {
            log("task window state unavailable: " + error);
        }
    }

    private final java.util.Map<Object,String> topTaskSignals=new java.util.WeakHashMap<>();
    private static long topWorkspaceGeneration;

    /** Observe late fullscreen/insets changes too; players often hide bars after orientation. */
    private void refreshWideVideoSignal(Object window) {
        if (!sFullscreenRotationActive) return;
        try {
            Object activity=XposedHelpers.getObjectField(window,"mActivityRecord");
            if(activity==null || XposedHelpers.getObjectField(activity,"mDisplayContent")!=sDisplayContent
                    || XposedHelpers.callMethod(activity,"findMainWindow")!=window) return;
            Object task=XposedHelpers.callMethod(activity,"getTask");
            if(task==null || XposedHelpers.callMethod(task,"topRunningActivity")!=activity)return;
            int mode=(Integer)XposedHelpers.callMethod(task,"getWindowingMode");
            if(mode!=1 && !isUpperLandscapeTask(task))return;
            int request=(Integer)XposedHelpers.callMethod(activity,"getRequestedOrientation");
            int rotation=sTaskRotations.getOrDefault(activity,sHeldDisplayRotation);
            sendTopTaskDirection(task,(Integer)XposedHelpers.callMethod(activity,"getRootTaskId"),
                    request,rotation,rotation,mode);
        } catch(Throwable ignored) { /* A departing window must not interrupt surface preparation. */ }
    }

    private boolean wideVideoFullscreen(Object task) {
        try {
            Object activity=XposedHelpers.callMethod(task,"topRunningActivity");
            if(activity==null || Boolean.TRUE.equals(XposedHelpers.callMethod(activity,"isActivityTypeHome")))return false;
            Object window=XposedHelpers.callMethod(activity,"findMainWindow");
            if(window==null)return false;
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            if((attrs.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN)!=0
                    || (attrs.systemUiVisibility & android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)!=0)return true;
            int visible=(Integer)XposedHelpers.callMethod(window,"getRequestedVisibleTypes");
            return (visible & android.view.WindowInsets.Type.statusBars())==0;
        } catch(Throwable unavailable) {return false;}
    }

    private void sendTopTaskDirection(Object task,int id,int request,int resolved,int rotation,int mode) {
        if(!sZoomed) {topTaskSignals.clear();return;}
        if(task==null || (mode!=1 && !isUpperLandscapeTask(task)))return;
        try {
            // TaskInfo uses a shared TaskInfoHelper; never nest its aggregation in orientation hooks.
            int user=XposedHelpers.getIntField(task,Boolean.TRUE.equals(
                    XposedHelpers.callMethod(task,"isLeafTask"))?"mUserId":"mCurrentUser");
            Object token=XposedHelpers.callMethod(
                    XposedHelpers.getObjectField(task,"mRemoteToken"),"toWindowContainerToken");
            boolean landscape=FullscreenRotationPolicy.requestsLandscape(request)
                    || FullscreenRotationPolicy.usesSensorOrUserRotation(request) && (rotation==1 || rotation==3);
            boolean focused=Boolean.TRUE.equals(XposedHelpers.callMethod(task,"isFocused"));
            boolean fullscreen=wideVideoFullscreen(task);
            boolean portrait=request==1 || request==7 || request==9 || request==12;
            String page="";
            int viewportOrientation=0;
            try {
                Object active=XposedHelpers.callMethod(task,"topRunningActivity");
                Object component=XposedHelpers.getObjectField(active,"mActivityComponent");
                if(component instanceof android.content.ComponentName)
                    page=((android.content.ComponentName)component).flattenToString();
                if(mode==6)viewportOrientation=XposedHelpers.getIntField(
                        XposedHelpers.callMethod(active,"getConfiguration"),"orientation");
            } catch(Throwable unavailable) { /* The task may be switching pages. */ }
            String signature=topWorkspaceGeneration+":"+user+":"+mode+":"+landscape+":"+focused+":"+fullscreen
                    +":"+request+":"+page+":"+viewportOrientation;
            if(signature.equals(topTaskSignals.get(task)))return;
            topTaskSignals.put(task,signature);
            Context context=(Context)(sSystemContext!=null?sSystemContext:sWmsContext);
            Handler bridge=sZoomBridgeHandler;
            if(context==null || bridge==null || !(token instanceof android.os.Parcelable))return;
            Intent signal=new Intent(WorkspaceHost.TOP_TASK_STATE_ACTION).setPackage(SYSTEMUI)
                    .putExtra("task",id).putExtra("user",user).putExtra("token",(android.os.Parcelable)token)
                    .putExtra("landscape",landscape).putExtra("fullscreen",fullscreen)
                    .putExtra("focused",focused).putExtra("mode",mode).putExtra("portrait",portrait)
                    .putExtra("page",page).putExtra("request",request)
                    .putExtra("viewportOrientation",viewportOrientation)
                    .putExtra("issued",android.os.SystemClock.uptimeMillis());
            bridge.post(()->{
                try {context.sendBroadcast(signal,android.Manifest.permission.WRITE_SECURE_SETTINGS);}
                catch(Throwable error){log("upper direction bridge: "+error);}
            });
        } catch(Throwable error) {log("upper task direction unavailable: "+error);}
    }

    /** Only our translucent mode6 role with a wide root opts out of local portrait rotation. */
    private static boolean isUpperLandscapeTask(Object task) {
        try {
            if(!SideRoleChange.isSide(task))return false;
            return DesktopLaunchBridge.ownsTopViewport(task);
        } catch(Throwable unavailable) {return false;}
    }

    private void captureTaskWindowState(Object display, Object activity, String source) {
        if (activity == null) return;
        try {
            int request = (Integer) XposedHelpers.callMethod(activity, "getOverrideOrientation");
            Object configuration = XposedHelpers.callMethod(activity, "getConfiguration");
            int orientation = XposedHelpers.getIntField(configuration, "orientation");
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int physicalRotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            int rotation = FullscreenRotationPolicy.observedTaskRotation(
                    orientation, request, physicalRotation);
            recordTaskWindowState(activity, request, rotation, rotation, 1, source);
        } catch (Throwable error) {
            log("pre-hold task state unavailable: " + error);
        }
    }

    private void releaseAllTaskRotationsLocked(String reason) {
        boolean wasUpdating = sUpdatingTaskRotation;
        sUpdatingTaskRotation = true;
        try {
            topRotationHandoffs.clear();
            try {
                // A return-to-portrait animation can outlive the fixed rotation state itself.
                RotationAnimation.cancelAll();
            } catch (Throwable error) {
                log("local rotation animations cleanup unavailable: " + error);
            }
            for (Object activity : new java.util.ArrayList<>(sTaskRotations.keySet())) {
                releaseTaskRotationLocked(activity, reason);
            }
        } finally {
            sUpdatingTaskRotation = wasUpdating;
        }
    }

    private int inactiveRotationCleanupEpoch;

    private void queueInactiveRotationCleanupLocked(Object display) {
        int epoch = ++inactiveRotationCleanupEpoch;
        queueInactiveRotationCleanupLocked(display, epoch, 3);
    }

    private void queueInactiveRotationCleanupLocked(Object display, int epoch, int remaining) {
        if (sFullscreenRotationActive || sTaskRotations.isEmpty() && !RotationAnimation.hasOwners()
                || display != sDisplayContent) return;
        if (remaining == 0) {
            log("inactive rotation cleanup remains blocked by native animation");
            return;
        }
        Handler bridge = sZoomBridgeHandler;
        if (bridge == null) {
            log("inactive rotation cleanup unavailable: no bridge handler");
            return;
        }
        bridge.postDelayed(() -> {
            try {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (epoch != inactiveRotationCleanupEpoch || sFullscreenRotationActive
                            || display != sDisplayContent) return;
                    releaseAllTaskRotationsLocked("deferred inactive cleanup");
                    queueInactiveRotationCleanupLocked(display, epoch, remaining - 1);
                }
            } catch (Throwable error) {
                log("inactive rotation cleanup failed: " + error);
            }
        }, 400);
    }

    private void refreshTaskRotationLocked(Object display) {
        Object top = XposedHelpers.callMethod(display, "topRunningActivity");
        java.util.ArrayList<Object> activities = new java.util.ArrayList<>(sTaskRotations.keySet());
        // Reconcile old owners first. The confirmed current main must win over a departing main.
        for (Object activity : DesktopLaunchBridge.rotationActivities(display)) {
            activities.remove(activity);
            activities.add(activity);
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (isUpperLandscapeTask(task)) {
                XposedHelpers.callMethod(task, "forAllActivities",
                        (java.util.function.Consumer<Object>) page -> {
                            if (XposedHelpers.callMethod(page, "getTask") == task
                                    && !activities.contains(page)) activities.add(page);
                        }, true);
            }
        }
        for (Object activity : activities) {
            if (activity != top) {
                applyFixedRotationLocked(display, activity, "side-rotation-policy");
            }
        }
        applyFixedRotationLocked(display, top, "system-rotation-policy");
    }

    private boolean workspaceRotationRefreshPending;

    /** A proposal can change while the physical display remains held at zero. */
    private void queueWorkspaceRotationRefresh(Object rotation) {
        if (!sZoomed || !sFullscreenRotationActive) return;
        try {
            Object display = XposedHelpers.getObjectField(rotation, "mDisplayContent");
            if (display != sDisplayContent || sZoomBridgeHandler == null) return;
            synchronized (this) {
                if (workspaceRotationRefreshPending) return;
                workspaceRotationRefreshPending = true;
            }
            boolean posted = sZoomBridgeHandler.post(() -> {
                synchronized (HostEntry.this) { workspaceRotationRefreshPending = false; }
                try {
                    Object service = XposedHelpers.getObjectField(display, "mWmService");
                    synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                        if (sZoomed && sFullscreenRotationActive && display == sDisplayContent
                                && !sUpdatingTaskRotation) refreshTaskRotationLocked(display);
                    }
                } catch (Throwable error) { log("workspace direction proposal refresh failed: " + error); }
            });
            if (!posted) synchronized (this) { workspaceRotationRefreshPending = false; }
        } catch (Throwable error) {
            synchronized (this) { workspaceRotationRefreshPending = false; }
            log("workspace direction proposal unavailable: " + error);
        }
    }

    /** TOP follows the system USER policy, independent of its app's landscape request. */
    private int resolveTopRotationLocked(Object display, Object activity) throws Throwable {
        Object resolver = XposedHelpers.getObjectField(display, "mDisplayRotation");
        int last = sTaskRotations.getOrDefault(activity, sHeldDisplayRotation);
        int resolved = (Integer) XposedBridge.invokeOriginalMethod(sTaskRotationResolver, resolver,
                new Object[]{2, last});
        return TopRotation.rotation(resolved);
    }

    private static final java.util.Map<Object, Rect> sTopRotationBounds = new java.util.WeakHashMap<>();
    private static final java.util.Set<Object> sTopRotationConfigurationRelease =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Object, TopRelaunchScope>
            sTopRotationRelaunchScopes = new java.util.WeakHashMap<>();

    private boolean preserveTopWindowForRelaunch(Object activity, int changes) {
        TopRelaunchScope scope = sTopRotationRelaunchScopes.get(activity);
        return scope != null && scope.previous != null
                && keepTopActivityForRotationConfig(activity, changes, scope.previous);
    }

    private boolean keepTopActivityForRotationConfig(Object activity, int changes, Object previous) {
        if (!sZoomed || !sFullscreenRotationActive || activity == null
                || XposedHelpers.callMethod(activity, "getDisplayContent") != sDisplayContent)
            return false;
        if (!Integer.valueOf(sHeldDisplayRotation).equals(XposedHelpers.callMethod(
                XposedHelpers.getObjectField(sDisplayContent, "mDisplayRotation"), "getRotation")))
            return false;
        Object task = XposedHelpers.callMethod(activity, "getTask");
        if (!isLiveTopPageLocked(activity, task))
            return false;
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        // Native apply dispatches configuration synchronously before it returns the state
        // for our long-lived lease. The helper's exact task/config call scope covers that gap.
        if (TopRotation.isApplying(activity, task))
            return TopRotation.shouldKeepLiveSurface(
                    activity, changes, previous, state, false);
        java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
        Rect expected = sTopRotationBounds.get(activity);
        if (lease == null || lease.get() == null || expected == null
                || !expected.equals(XposedHelpers.callMethod(task, "getBounds"))) return false;
        return TopRotation.shouldKeepLiveSurface(activity, changes, previous,
                lease.get(), sTopRotationConfigurationRelease.contains(activity));
    }

    private boolean ownsTaskRotationTransform(Object activity) {
        java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
        return lease != null && lease.get() != null && lease.get() == XposedHelpers.getObjectField(
                activity, "mFixedRotationTransformState");
    }

    private void animateTaskRotationLocked(Object activity, int oldRotation, int newRotation) {
        if (!sZoomed || oldRotation == newRotation) return;
        try {
            if (isUpperLandscapeTask(XposedHelpers.callMethod(activity, "getTask"))
                    && RotationAnimation.inTransition(activity)) {
                // Shell owns the visible motion; reconcile the final viewport without replaying it.
                topRotationHandoffs.add(activity);
                return;
            }
            if (TopRotation.phone(sDisplayContent)
                    && RotationAnimation.start(activity, oldRotation, newRotation))
                log("local rotation animation native-window=true old=" + oldRotation
                        + " new=" + newRotation);
        } catch (Throwable error) {
            // Animation availability must never break configuration, input or native cleanup.
            log("local rotation animation unavailable: " + error);
        }
    }

    private Boolean applyTopRotationLocked(Object display, Object activity, Object task, String source)
            throws Throwable {
        int desired = resolveTopRotationLocked(display,
                XposedHelpers.callMethod(task, "topRunningActivity"));
        Integer previous = sTaskRotations.get(activity);
        if (desired == sHeldDisplayRotation) {
            boolean owned = ownsTaskRotationTransform(activity);
            boolean released = releaseTaskRotationLocked(activity, "TOP returned to landscape viewport");
            if (released && owned && previous != null)
                animateTaskRotationLocked(activity, previous, desired);
            return Boolean.FALSE;
        }
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        java.lang.ref.WeakReference<Object> lease = sTaskRotationTransforms.get(activity);
        if (state != null && (previous == null || lease == null || lease.get() != state)) {
            if (adoptSharedTopRotationLocked(activity, task, state, desired)) {
                TopRotation.ensureSurface(activity);
                log("TOP joined owned native page rotation source=" + source);
                return Boolean.TRUE;
            }
            if (previous != null) releaseTaskRotationLocked(activity, "TOP native owner replaced");
            return Boolean.FALSE;
        }
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (state != null && Integer.valueOf(desired).equals(previous)
                && bounds.equals(sTopRotationBounds.get(activity))) {
            TopRotation.ensureSurface(activity);
            return Boolean.TRUE;
        }
        if (sTaskRotations.containsKey(activity)
                && !releaseTaskRotationLocked(activity, "TOP direction changed"))
            return Boolean.FALSE;
        sTaskRotations.put(activity, desired);
        try {
            TopRotation.apply(display, activity, task, desired);
            Object installed = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (installed == null) throw new IllegalStateException("TOP native rotation not installed");
            sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(installed));
            sTopRotationBounds.put(activity, new Rect(bounds));
            animateTaskRotationLocked(activity,
                    previous == null ? sHeldDisplayRotation : previous, desired);
        } catch (Throwable error) {
            // The helper may have installed state before its native leash operation failed.
            Object installed = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (installed != null) sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(installed));
            releaseTaskRotationLocked(activity, "TOP rotation failed cleanup");
            throw error;
        }
        log("TOP local viewport rotation=" + desired + " source=" + source);
        return Boolean.TRUE;
    }

    private boolean isSideFixedTask(Object activity) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            return SideRoleChange.isSide(task);
        } catch (Throwable error) {
            log("fixed rotation side-state lookup failed: " + error);
            return false;
        }
    }

    private static boolean isSideTask(Object task) {
        try {
            return Integer.valueOf(6).equals(XposedHelpers.callMethod(
                            task, "getWindowingMode"))
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(
                            task, "isForceTranslucent"));
        } catch (Throwable error) {
            return false;
        }
    }

    private static final ThreadLocal<Boolean> sReadingWorkspaceFocus = new ThreadLocal<>();
    private static final ThreadLocal<Object> sWindowFocusScanDisplay = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> sWorkspaceLauncherTaskQuery = new ThreadLocal<>();

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
                sReadingWorkspaceFocus, HostEntry::isUpperLandscapeTask, HostEntry::log);
    }

    private void installNativeFreeformCoexistence(ClassLoader cl) {
        NativeFreeformTouchExclusionHooks.install(cl,
                () -> sZoomTarget,
                () -> sNativeFreeformLayerAnchor,
                () -> sNativeFreeformHostLayers,
                () -> sZoomed,
                HostEntry::log);
        installNativeFreeformPin(cl);
        try {
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(display, "assignChildLayers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != sDisplayContent || p.args.length != 1) return;
                    try { sModalWindowLayers.reconcile(p.thisObject,p.args[0]); }
                    catch (Throwable error) { log("workspace modal layering skipped: " + error); }
                    if (!sZoomed && sNativeFreeformHostLayers.isEmpty()) return;
                    try { layerWorkspaceWithNativeFreeforms(p.thisObject, p.args[0]); }
                    catch (Throwable error) { log("native freeform layering skipped: " + error); }
                }
            });
            log("native freeform inherits main zoom; workspace masks overflow outside main");
        } catch (Throwable error) { log("native freeform coexistence hook unavailable: " + error); }
    }

    private static Object sNativeFreeformLayerAnchor;
    private static final ModalWindowLayers sModalWindowLayers = new ModalWindowLayers();
    private static final java.util.Map<Object, NativeHostLayer> sNativeFreeformHostLayers =
            new java.util.IdentityHashMap<>();
    private static void installNativeFreeformPin(ClassLoader cl) {
        NativeFreeformPinHooks.install(cl, HostEntry::releaseNativeForegroundPinLayers, HostEntry::log);
    }


    private static boolean isNativeForegroundPin(Object task) {
        Object manager = NativeFreeformPinHooks.manager();
        if(manager==null)manager=NativeFreeformTouchExclusionHooks.manager();
        return manager != null && Boolean.TRUE.equals(XposedHelpers.callMethod(manager,
                "getMiuiFreeformIsForegroundPin", XposedHelpers.getIntField(task, "mTaskId")));
    }

    private static void releaseNativeForegroundPinLayers(Object manager, int taskId) {
        NativeFreeformPinHooks.setManager(manager);
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || target == null) return;
        synchronized (target.globalLock) {
            if (!sZoomed || target != sZoomTarget || sNativeFreeformHostLayers.isEmpty()) return;
            try {
                // The ROM has just stored mIsForegroundPin, but pin2/3 need not
                // assign child layers. Clear BOTH live and pending dependencies
                // synchronously, before the Binder reply permits Shell hideTask.
                Object transaction = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                try {
                    layerWorkspaceWithNativeFreeforms(target.display, transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } finally { XposedHelpers.callMethod(transaction, "close"); }
                log("native foreground pin layers reconciled task=" + taskId);
            } catch (Throwable error) { log("native foreground pin layer release failed: " + error); }
        }
    }

    /** Amend WM's own layer transaction; never reparent or transform an OEM task. */
    private static void layerWorkspaceWithNativeFreeforms(Object display, Object transaction) {
        boolean touchExclusionChanged = false;
        Object[] highestFreeform = {null};
        java.util.List<Object> hosts = workspaceOverlayWindows(display);
        Consumer<Object> pickFreeform = task -> {
            if (!sZoomed || (!Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))
                    && !NativeFreeformHandoff.keepNativeTransform(task, false))
                    || XposedHelpers.callMethod(task, "getRootTask") != task
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisibleRequested"))) return;
            // Foreground pin retains WM visibility while Shell hides its canonical
            // surface. Relative children would otherwise disappear with that task.
            if (isNativeForegroundPin(task)) return;
            Object surface = XposedHelpers.callMethod(task, "getSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                highestFreeform[0] = surface;
        };
        XposedHelpers.callMethod(display, "forAllTasks", pickFreeform, false);
        if (sNativeFreeformLayerAnchor != highestFreeform[0]) {
            // A newly excluded leaf does not undo a spec already on its shared ancestor.
            // Re-split that ancestor in this same WM transaction before showing freeform.
            if (sZoomed) ShellCompat.call(display, "reapplyMagnificationSpec", transaction);
            sNativeFreeformLayerAnchor = highestFreeform[0];
            log("native freeform anchor changed present=" + (highestFreeform[0] != null)
                    + " hostWindows=" + hosts.size());
            ZoomTarget target = sZoomTarget;
            if (target != null && target.display == display) target.handler.post(() -> {
                synchronized (target.globalLock) {
                    if (target == sZoomTarget)
                        applyGestureSurfacesLocked(target, sZoomScale, sZoomOffset, sZoomOffsetX);
                }
            });
        }

        java.util.Iterator<java.util.Map.Entry<Object, NativeHostLayer>> entries =
                sNativeFreeformHostLayers.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<Object, NativeHostLayer> entry = entries.next();
            Object host = entry.getKey();
            NativeHostLayer state = entry.getValue();
            if (XposedHelpers.callMethod(host, "getSurfaceControl") != state.surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(state.surface, "isValid"))) {
                entries.remove();
                touchExclusionChanged = true;
                continue;
            }
            if (highestFreeform[0] != null && hosts.contains(host)) continue;
            rememberNativeHostNormalLayer(host, state);
            restoreNativeHostLayer(host, state, transaction);
            Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
            if (pending != transaction) restoreNativeHostLayer(host, state, pending);
            entries.remove();
            touchExclusionChanged = true;
        }
        if (highestFreeform[0] != null) {
            for (Object host : hosts) {
                Object surface = XposedHelpers.callMethod(host, "getSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid"))) continue;
                NativeHostLayer state = sNativeFreeformHostLayers.get(host);
                if (state == null || state.surface != surface) {
                    state = new NativeHostLayer(surface);
                    sNativeFreeformHostLayers.put(host, state);
                }
                rememberNativeHostNormalLayer(host, state);
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                        XposedHelpers.getObjectField(host, "mAttrs");
                // The final visible root in this bottom-up walk is the highest.
                // Host wallpaper outside its main hole masks OEM drag overflow,
                // while the transparent main hole preserves the real float.
                // A root-relative +1 can still sit below its positive-Z client
                // children. Raise only our identified overlay group above that
                // subtree, preserving the order of our own content/transition layers.
                int layer = attrs.type == WindowTypes.STATUS_BAR ? -5 : 1_000_000 + (
                        "Clipboard".contentEquals(attrs.getTitle()) ? 4
                        : "OneStepLiteTransition".contentEquals(attrs.getTitle()) ? 3
                        : "OneStepLiteMirrorLayer".contentEquals(attrs.getTitle()) ? 2 : 1);
                if (state.nativeAnchor != highestFreeform[0]) touchExclusionChanged = true;
                XposedHelpers.callMethod(host, "assignRelativeLayer", transaction,
                        highestFreeform[0], layer, true);
                state.nativeAnchor = highestFreeform[0];
                state.nativeLayer = layer;
            }
        }
        Object touchManager = NativeFreeformTouchExclusionHooks.manager();
        if (touchExclusionChanged && touchManager != null) {
            try {
                XposedHelpers.callMethod(touchManager,
                        "updateMiuiFreeformTouchExcludeRegion", display);
            } catch (Throwable error) { log("native touch exclusion refresh skipped: " + error); }
        }
    }

    /** Exact Host overlays and main status chrome; unrelated system windows retain their native order. */
    private static java.util.List<Object> workspaceOverlayWindows(Object display) {
        java.util.List<Object> hosts=new java.util.ArrayList<>();
        Consumer<Object> pickHost = window -> {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            String title = String.valueOf(attrs.getTitle());
            boolean hostOverlay = ("WorkspaceHost".equals(title)
                    || "OneStepLiteTransition".equals(title) || "OneStepLiteMirrorLayer".equals(title)
                    || "Clipboard".equals(title))
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            // This status bar has been magnified into the main region. Keep it
            // with main chrome, not above an OEM freeform crossing that region.
            boolean mainStatusBar = sZoomed && attrs.type == WindowTypes.STATUS_BAR && "StatusBar".equals(title);
            if (SYSTEMUI.equals(attrs.packageName) && (hostOverlay || mainStatusBar)
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))) hosts.add(window);
        };
        XposedHelpers.callMethod(display, "forAllWindows", pickHost, false);
        return hosts;
    }

    private static void rememberNativeHostNormalLayer(Object host, NativeHostLayer state) {
        int currentLayer = XposedHelpers.getIntField(host, "mLastLayer");
        Object currentRelative = XposedHelpers.getObjectField(host, "mLastRelativeToLayer");
        if (state.nativeAnchor == null || currentRelative != state.nativeAnchor
                || currentLayer != state.nativeLayer) {
            state.normalLayer = currentLayer;
            state.normalRelativeTo = currentRelative;
        }
    }

    private static void restoreNativeHostLayer(Object host, NativeHostLayer state, Object transaction) {
        // assignLayer may return early while WM builds transition layers. Use its
        // SurfaceAnimator setter for our own window, preserving any animation leash.
        // Never change the OEM task's parent, layer, visibility or transform.
        if (state.normalRelativeTo == null) {
            XposedHelpers.callMethod(host, "setLayer", transaction, state.normalLayer);
        } else {
            XposedHelpers.callMethod(host, "setRelativeLayer", transaction,
                    state.normalRelativeTo, state.normalLayer);
        }
        XposedHelpers.setIntField(host, "mLastLayer", state.normalLayer);
        XposedHelpers.setObjectField(host, "mLastRelativeToLayer", state.normalRelativeTo);
    }

    private void installSideSystemBarPolicy(ClassLoader cl) {
        SideSystemBarHooks.install(cl, () -> sFullscreenRotationActive, () -> sZoomed,
                () -> sDisplayContent, HostEntry::isSideTask, HostEntry::log);
    }

    private void installDesktopIconLaunchBridge(ClassLoader cl) {
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

    private static volatile String sLastDesktopLaunchPackage;
    private static volatile int sLastDesktopLaunchUser = -1;
    private static volatile long sLastDesktopLaunchAt;

    private static int desktopLaunchUser(java.lang.reflect.Method method, Object[] args) {
        int callerUser = (Integer) XposedHelpers.callStaticMethod(android.os.UserHandle.class,
                "getUserId", android.os.Binder.getCallingUid());
        return DesktopIconLaunchPolicy.launchUser(method.getName(), method.getParameterTypes(), args, callerUser);
    }

    private static void forwardDesktopIconLaunch(Object[] args, int launchUser) {
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

    private boolean isSystemRotationLocked(Object display) {
        try {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            try {
                // The target's native getter reads ACCELEROMETER_ROTATION for USER_CURRENT,
                // including the active HyperOS profile/display policy.
                return Boolean.TRUE.equals(XposedHelpers.callMethod(
                        displayRotation, "isRotationFrozen"));
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            try {
                return Integer.valueOf(1).equals(XposedHelpers.callMethod(
                        displayRotation, "getUserRotationMode"));
            } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
        } catch (Throwable error) {
            log("fixed rotation mode lookup unavailable: " + error);
        }
        try {
            Context context = (Context) sSystemContext;
            if (context != null) {
                return Integer.valueOf(0).equals(XposedHelpers.callStaticMethod(
                        android.provider.Settings.System.class, "getIntForUser",
                        context.getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0, -2));
            }
        } catch (Throwable error) {
            log("fixed rotation setting lookup unavailable: " + error);
        }
        // Do not turn an unavailable policy query into permission to use a sensor.
        return true;
    }

    private void beginFixedMainRotationLocked(Object display) {
        if (sFullscreenRotationActive || display == null) return;
        sTaskWindowStates.clear();
        // Capture the application's real direction before the workspace's portrait
        // configuration can overwrite it, including manual fullscreen video under lock.
        captureTaskWindowState(display, XposedHelpers.callMethod(display, "topRunningActivity"),
                "before-workspace-portrait");
        releaseAllTaskRotationsLocked("new OneStepLite session");
        ++inactiveRotationCleanupEpoch;
        sHeldDisplayRotation = workspaceDisplayRotation(display);
        sFullscreenRotationActive = true;
        log("fixed rotation active; OneStepLite display held at workspace rotation "
                + sHeldDisplayRotation);
        recomputeDisplayOrientationLocked(display, "enter OneStepLite");
    }

    private void endFixedMainRotationLocked(Object display) {
        sTopSystemBars.clear();
        sMainImeProcessConfiguration.clear(HostEntry::log);
        sInteractiveImeViewport.clear(HostEntry::log);
        sMainSystemUiRotation.clear(HostEntry::log);
        publishMainUiConfiguration(null);
        if (!sFullscreenRotationActive) {
            sMainWindowClip.clear(HostEntry::log);
            releaseAllTaskRotationsLocked("inactive cleanup");
            queueInactiveRotationCleanupLocked(display);
            return;
        }
        sFullscreenRotationActive = false;
        releaseAllTaskRotationsLocked("host exit");
        queueInactiveRotationCleanupLocked(display);
        sMainWindowClip.clear(HostEntry::log);
        recomputeDisplayOrientationLocked(display, "exit OneStepLite");
    }

    private int workspaceDisplayRotation(Object display) {
        try {
            Object info = XposedHelpers.callMethod(display, "getDisplayInfo");
            boolean tablet = WorkspaceDisplayPolicy.isTablet(
                    XposedHelpers.getIntField(info, "logicalWidth"),
                    XposedHelpers.getIntField(info, "logicalHeight"),
                    XposedHelpers.getIntField(info, "logicalDensityDpi"));
            Object rotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            return WorkspaceDisplayPolicy.rotation(tablet,
                    (Integer) XposedHelpers.callMethod(rotation, "getRotation"));
        } catch (Throwable unavailable) {
            log("workspace display classification unavailable; use portrait: " + unavailable);
            return FullscreenRotationPolicy.portraitDisplayRotation();
        }
    }

    private void recomputeDisplayOrientationLocked(Object display, String reason) {
        try {
            boolean changed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    display, "updateOrientation"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // updateOrientation alone can early-return when the top app has not changed.
            // Re-run WM's regular rotation pipeline, including when entering from landscape.
            changed |= Boolean.TRUE.equals(XposedHelpers.callMethod(
                    displayRotation, "updateRotationUnchecked", true));
            if (changed) {
                XposedHelpers.callMethod(display, "sendNewConfiguration");
            }
        } catch (Throwable error) {
            log("fixed rotation: orientation refresh unavailable on " + reason + ": " + error);
        }
    }

    void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, null);
    }

    private void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX, ZoomCommit commit) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, commit, false);
    }

    private void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX, ZoomCommit commit, boolean nativeVisibility) throws Throwable {
        if ((!nativeVisibility && Looper.myLooper() != target.handler.getLooper()) || !Thread.holdsLock(target.globalLock)) {
            throw new IllegalStateException("zoom requires the WMS handler and its global lock");
        }
        if (sZoomTarget != target || sDisplayContent != target.display) return;
        // Delayed client writes and animation frames cannot resize native keyguard/AOD.
        // Native clear or a separately authenticated, drawn Host handoff releases it.
        if (sKeyguardZoom.blocked()) { scale = 1f; offsetY = 0f; offsetX = 0f; }
        Object dc = target.display;
        // SCREEN_OFF's plain zoom reset can arrive before setKeyguardShown.
        // Cover that ordering too, whether or not keep-on-lock is enabled.
        if (sZoomed && scale == 1f && (sKeyguardZoom.sleeping()||!EntryGuard.canOpen(sWmsContext)))
            sRetainedWindow.holdOpeningMainForLock(dc);
        Class<?> specCls = XposedHelpers.findClass("android.view.MagnificationSpec",
                dc.getClass().getClassLoader());
        // The target's no-arg constructor is the already verified path; obtain() is absent.
        java.lang.reflect.Constructor<?> ctor = specCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object spec = ctor.newInstance();
        XposedHelpers.setFloatField(spec, "scale", scale);
        XposedHelpers.setFloatField(spec, "offsetX", offsetX);
        XposedHelpers.setFloatField(spec, "offsetY", offsetY);
        boolean previous = sZoomed;
        boolean rotationWasActive = sFullscreenRotationActive;
        int previousLandscapeState = sNativeLandscapeState;
        sNativeLandscapeState = NativeLandscapePolicy.onZoom(
                sNativeLandscapeState, scale);
        // Rotation refresh can synchronously call back into activity orientation.
        // Publish the incoming workspace state before it runs, so entering from a
        // fullscreen landscape app cannot request native-fullscreen suspension.
        sZoomed = scale != 1f;
        if(sZoomed && !previous)topWorkspaceGeneration++;
        try {
            if (sZoomed) beginFixedMainRotationLocked(dc);
            else endFixedMainRotationLocked(dc);
            if (commit != null) {
                Object pending = XposedHelpers.callMethod(dc, "getPendingTransaction");
                commit.transactionId = ((Number) XposedHelpers.callMethod(pending, "getId")).longValue();
                if (commit.transactionId == 0L) throw new IllegalStateException("missing WMS transaction id");
                Class<?> callbackType = XposedHelpers.findClass(
                        "android.view.SurfaceControl$TransactionCommittedListener", dc.getClass().getClassLoader());
                Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),
                        new Class<?>[]{callbackType}, (proxy, method, args) -> {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "OneStepLiteZoomCommit";
                            if ("onTransactionCommitted".equals(method.getName())) {
                                boolean exact;
                                try {
                                    synchronized (target.globalLock) {
                                        Object actual = XposedHelpers.getObjectField(dc, "mMagnificationSpec");
                                        commit.actualScale = actual == null ? 1f : XposedHelpers.getFloatField(actual, "scale");
                                        commit.actualX = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetX");
                                        commit.actualY = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetY");
                                        exact = commit.applied && target == sZoomTarget
                                                && sLiveZoomCommit == commit
                                                && commit.zoom.sameTuple(commit.actualScale, commit.actualX, commit.actualY)
                                                && commit.zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset);
                                    }
                                    commit.finish(exact, exact ? null : "zoom changed before commit acknowledgement");
                                } catch (Throwable failure) {
                                    commit.finish(false, "WMS applied-spec read failed");
                                }
                            }
                            return null;
                        });
                java.util.concurrent.Executor executor = runnable -> target.handler.post(runnable);
                XposedHelpers.callMethod(pending, "addTransactionCommittedListener", executor, callback);
            }
            XposedHelpers.callMethod(dc, "applyMagnificationSpec", spec);
        } catch (Throwable error) {
            sZoomed = previous;
            sNativeLandscapeState = previousLandscapeState;
            if (!rotationWasActive) endFixedMainRotationLocked(dc);
            throw error;
        }
        sZoomScale = scale;
        sZoomOffset = offsetY;
        sZoomOffsetX = offsetX;
        if (previous != sZoomed) WallpaperScale.refresh(dc);
        if (commit != null) commit.applied = true;
        // An already visible OEM freeform need not trigger assignChildLayers
        // when only magnification changes. Reconcile at the zoom boundary too,
        // in the same WM transaction, so host input/chrome cannot cover it.
        if (previous != sZoomed || scale == 1f) {
            layerWorkspaceWithNativeFreeforms(dc,
                    XposedHelpers.callMethod(dc, "getPendingTransaction"));
        }
        if (scale == 1f) {
            restoreExitedMagnificationLocked(target);
        }
        RecentsInput.geometryChanged(XposedHelpers.callMethod(dc, "getPendingTransaction"),
                scale, offsetY, offsetX);
        Rect rawDisplayBounds = (Rect) XposedHelpers.callMethod(dc, "getBounds");
        int rawDisplayRotation = (Integer) XposedHelpers.callMethod(dc, "getRotation");
        NativeRawInputBridge.update(scale, offsetY, offsetX,
                rawDisplayBounds.width(), rawDisplayBounds.height(), rawDisplayRotation);
        // Gesture monitors are input geometry, not per-frame visuals. Rewriting their
        // crop/matrix/position every animation frame adds a second SF transaction per
        // frame; the boundary write below keeps them correct at both ends.
        if (!sZoomFrameApply) applyGestureSurfacesLocked(target, scale, offsetY, offsetX);

        // 清掉历史版本遗留的固定 1080x2460 显示裁剪（R2 时代的调试代码）：在 17PM 上会把
        // 窗口层裁成 1080x2460，右侧 120px、底部 148px 变成黑边。每次应用 spec 都显式清空，
        // 能自愈旧版本残留；不再主动设置任何 crop。
        //
        // 只在缩放边界做：这是自愈用的清理，不是逐帧几何。动画期间每帧再开一个
        // SurfaceControl.Transaction 并 apply，会让 SF 每帧多收一次事务，正是掉帧来源。
        Object crop = null;
        if (!sZoomFrameApply) try {
            Object layer = XposedHelpers.callMethod(dc, "getWindowingLayer");
            if (layer != null && Boolean.TRUE.equals(XposedHelpers.callMethod(layer, "isValid"))) {
                crop = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(crop, "setWindowCrop", layer, (Object) null);
                XposedHelpers.callMethod(crop, "apply");
            }
        } catch (Throwable failure) {
            log("display crop clear unavailable: " + failure);
        } finally {
            if (crop != null) {
                try { XposedHelpers.callMethod(crop, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
            }
        }
        // supplies the same matrix and inverse input mapping as the main app windows.
        if (!sZoomFrameApply) {
            log("WM zoom applied scale=" + scale + " offsetY=" + offsetY
                    + " offsetX=" + offsetX
                    + " thread=" + Thread.currentThread().getName() + " globalLock=true");
        }
    }

    /**
     * 整屏缩放动画：从当前生效 spec 插值到目标 spec，每帧仍在 WMS.mH 上、
     * 持真实 mGlobalLock 应用（与静态缩放同一路径）。
     *
     * 曲线与时长必须和宿主的 playWorkspaceFade 完全一致，否则两条同时开始的
     * 动画会在中途分离：Host chrome 不参与 display 放大（HostWindowPolicy 的
     * NOT_MAGNIFIABLE），所以它一旦领先就会直接画在还没缩小的应用画面上。
     */
    void startZoomAnimation(ZoomTarget target, ZoomAnimCommand.Plan plan, String key) {
        if (target == null || plan == null || key == null) return;
        if (sZoomTarget != target) return;
        if (Looper.myLooper() != target.handler.getLooper()) {
            log("zoom animation rejected off the WM handler");
            return;
        }
        if (key.equals(sZoomAnimKey)) return; // 同一命令内容只播放一次
        cancelZoomAnimation(target);
        sZoomAnimKey = key;
        final float fromScale = sZoomScale;
        final float fromY = sZoomOffset;
        final float fromX = sZoomOffsetX;
        if (Math.abs(fromScale - plan.scale) < 0.0005f
                && Math.abs(fromY - plan.offsetY) < 0.5f
                && Math.abs(fromX - plan.offsetX) < 0.5f) {
            log("zoom animation skipped; already at target " + plan.describe());
            return;
        }
        // 动画会改变真实 state；让调度器丢弃旧的 lastAttempt，避免之后“恢复原值”被当成重复请求吞掉。
        target.dispatch.invalidateLastAttempt();
        if (zoomAnimInterpolator == null) {
            // Must stay identical to WorkspaceHost.playWorkspaceFade: the Host chrome is
            // excluded from display magnification, so any curve difference shows up
            // directly as chrome drawn over an application that has not shrunk yet.
            zoomAnimInterpolator = WorkspaceMotionSpec.interpolator();
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(plan.durationMs);
        animator.setInterpolator(zoomAnimInterpolator);
        animator.addUpdateListener(animation -> {
            if (sZoomAnimator != animator || sZoomTarget != target) return;
            float fraction = WorkspaceMotionSpec.frameProgress(plan.startUptimeMs,plan.durationMs,animation.getAnimatedFraction());
            float[] state = ZoomAnimCommand.stateAt(fromScale, fromY, fromX, plan, fraction);
            sZoomFrameApply = true;
            try {
                synchronized (target.globalLock) {
                    if (sZoomTarget != target) return;
                    applyDisplayZoomLocked(target, state[0], state[1], state[2]);
                }
            } catch (Throwable error) {
                log("zoom animation frame stopped: " + error);
                cancelZoomAnimation(target);
            } finally {
                sZoomFrameApply = false;
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (sZoomAnimator != animator) return; // 已被更新的普通请求取消
                sZoomAnimator = null;
                sZoomFrameApply = false;
                try {
                    synchronized (target.globalLock) {
                        if (sZoomTarget == target) {
                            applyDisplayZoomLocked(target, plan.scale, plan.offsetY, plan.offsetX);
                        }
                    }
                } catch (Throwable error) {
                    log("zoom animation final apply failed: " + error);
                }
                log("zoom animation finished " + plan.describe());
            }
        });
        sZoomAnimator = animator;
        if (plan.startUptimeMs > 0L) animator.setStartDelay(Math.max(0L,
                Math.min(1000L, plan.startUptimeMs - android.os.SystemClock.uptimeMillis())));
        animator.start();
        if (plan.startUptimeMs > 0L && android.os.SystemClock.uptimeMillis() > plan.startUptimeMs)
            animator.setCurrentPlayTime(Math.min(plan.durationMs,
                    android.os.SystemClock.uptimeMillis() - plan.startUptimeMs));
        log("zoom animation started from scale=" + fromScale + " y=" + fromY + " x=" + fromX
                + " -> " + plan.describe());
    }

    /** 只能在 WM handler 上取消；其它线程转发过去，不做 inline 调用。 */
    void cancelZoomAnimation(ZoomTarget target) {
        ValueAnimator animator = sZoomAnimator;
        if (animator == null) return;
        if (target != null && Looper.myLooper() != target.handler.getLooper()) {
            target.handler.post(() -> cancelZoomAnimation(target));
            return;
        }
        sZoomAnimator = null;
        sZoomFrameApply = false;
        try { animator.cancel(); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ }
        log("zoom animation cancelled");
    }

    private static void applyGestureSurfacesLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) {
        if (Looper.myLooper() != target.handler.getLooper() || !Thread.holdsLock(target.globalLock)) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            int count = 0;
            synchronized (sGestureSurfaces) {
                java.util.Iterator<Object> it = sGestureSurfaces.keySet().iterator();
                while (it.hasNext()) {
                    Object surface = it.next();
                    if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        it.remove();
                        continue;
                    }
                    // Its InputWindowHandle uses replaceTouchableRegionWithCrop(null).
                    // Navigation and native freeform spies use the task's logical display space.
                    Rect bounds = (Rect) XposedHelpers.callMethod(target.display, "getBounds");
                    float monitorScale = NativeFreeformPolicy.gestureScale(
                            sNativeFreeformLayerAnchor != null, sGestureSurfaces.get(surface), scale);
                    float x = monitorScale == 1f ? 0f : offsetX;
                    float y = monitorScale == 1f ? 0f : offsetY;
                    Rect crop = monitorScale == 1f ? null : new Rect(0, 0, bounds.width(), bounds.height());
                    XposedHelpers.callMethod(tx, "setCrop", surface, crop);
                    XposedHelpers.callMethod(tx, "setMatrix", surface, monitorScale, 0f, 0f, monitorScale);
                    XposedHelpers.callMethod(tx, "setPosition", surface, x, y);
                    count++;
                }
            }
            if (count > 0) {
                XposedHelpers.callMethod(tx, "apply");
                if (!sZoomFrameApply) {
                    log("gesture geometry scale=" + scale + " offset=" + offsetX + "," + offsetY
                            + " monitors=" + count);
                }
            }
        } catch (Throwable error) { log("navigation input transform stopped: " + error); }
        finally { if (tx != null) try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) { /* best effort: the platform may already have released this */ } }
    }

    private void hookApplication(ClassLoader cl) {
        SystemUiHooks.install(cl, context -> {
            sContext=context;Warmup.start(context,cl);
            log("captured Context -> " + context);scheduleStart();
        }, HostEntry::log);
    }


    private void hookBackGestureDiagnostics(ClassLoader cl) {
        new BackGestureDiagnostics(()->sHostZoomed,HostEntry::log).install(cl);
    }

    /**
     * MIUI computes the app open/close animation rectangle before SurfaceControl playback.
     * Keep that temporary visual rectangle inside OneStepLite's main pane; task bounds and the
     * normal full-screen launcher animation are left untouched.
     */
    private void hookTransitionBounds(ClassLoader cl) {
        new WorkspaceWindowHooks(HostEntry::log).hookTransitionBounds(cl);
    }

    private void hookRecentsWallpaper(ClassLoader cl) {
        new WorkspaceWindowHooks(HostEntry::log).hookRecentsWallpaper(cl);
    }

    private void hookShellProviders(ClassLoader cl) {
        ShellProviderHooks.install(cl, new ShellProviderHooks.State() {
            @Override public void rootTaskDisplayArea(Object value) {
                ShellCompat.rootTaskDisplayArea=value;
            }
            @Override public void taskViewTransitions(Object value) {
                sTaskViewTransitions=value;
            }
            @Override public void shellExecutor(Executor value) {
                sShellExecutor=value;log("captured ShellExecutor -> " + value);
            }
            @Override public void taskViewFactory(Object value) {
                sTaskViewFactory=value;log("captured TaskViewFactory -> " + value);
            }
        }, HostEntry::log);
    }


    private void scheduleStart() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 紧急停止阀：只要这个文件存在就什么都不做。
                if (new java.io.File("/data/system/onesteplite-halt").exists()) {
                    log("halt marker present -> skipping test");
                    return;
                }
                // 一次性自锁：本次安装只允许尝试启动一次。
                // 死在启动这OneStepLite会让 SystemUI 崩溃重启，而重启后模块会再次运行；
                // 没有这道锁就会形成崩溃循环（LSPosed 安全模式是外部兜底，不该依赖）。
                // 锁文件放在 SystemUI 自己的数据目录，跨进程重启依然有效。
                log("capabilities: factory=" + sTaskViewFactory
                        + " executor=" + sShellExecutor + " transitions=" + sTaskViewTransitions);
                if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                    log("missing capability, abort");
                    return;
                }
                // v1.2.1：不再用一次性自锁挡触发器（重启后"呼不出/退不出"的根源）。
                // 触发器本身是热区+广播，安全；真正危险的是早期自动启动应用，已删除。
                installTriggers();
            }
        }, LAUNCH_DELAY_MS);
    }

    private static void installRetainedWorkspaceWakeHooks(ClassLoader cl) {
        RetainedWorkspaceHooks.install(cl, HostEntry::log);
    }

    private void installTriggers() {
        TriggerHooks.install(sContext, sShellExecutor, sTaskViewFactory,
                sTaskViewTransitions, HostEntry::log);
    }


    /**
     * 无人值守调试广播（v0.8.0）：
     *   am broadcast -a io.github.zerohomers0.onesteplite.DEBUG --ei slot 1 --es pkg com.android.settings
     *   am broadcast -a io.github.zerohomers0.onesteplite.DEBUG --ei slot 1 --ez swap true
     * 先确保宿主已启动，再转给 WorkspaceHost。
     */
    private void handleDebugBroadcast(android.content.Intent intent) {
        if (sContext == null) return;
        DebugConsole.handle(intent, sContext, sTaskViewFactory, sShellExecutor,
                sTaskViewTransitions, HostEntry::log);
    }


    private static void log(String msg) {
        if (!RoutineLog.allow(msg)) return;
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }

}

