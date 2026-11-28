package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.NativeWindowRuntime.*;

import static io.github.zerohomers0.onesteplite.LauncherRuntime.*;

import static io.github.zerohomers0.onesteplite.ZoomRuntime.*;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import de.robv.android.xposed.IXposedHookLoadPackage;
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

    static final String TAG = Tags.PROBE;

    /** Generated from version.json by both build paths. */
    static final String RUNTIME_MARKER = BuildIdentity.RUNTIME_MARKER;
    static final String SYSTEMUI = Packages.SYSTEM_UI;
    static final String DESKTOP_ICON_LAUNCH_ACTION = Actions.DESKTOP_ICON_LAUNCH;
    static volatile Context sContext;

    static long topWorkspaceGeneration;

    private final TaskRotationController rotations = new TaskRotationController();
    private final SystemServerHooks systemServer = new SystemServerHooks(this, rotations);
    private final ZoomTransactions zoomTransactions = new ZoomTransactions(this);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
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
            systemServer.install(lpparam.classLoader);
            return;
        }
        if ("com.xiaomi.type".equals(lpparam.packageName)) {
            // Optional keyboard policy only; ordinary app layout/input uses the system hooks.
            hookMiTypeAutoFloating(lpparam.classLoader);
            return;
        }
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            if (lpparam.appInfo != null && ViewImageSource.supportsUid(lpparam.appInfo.uid)
                    && !HostEntry.class.getPackageName().equals(lpparam.packageName)) {
                try { ViewImageSource.install(); }
                catch (Throwable unavailable) { log("View content source unavailable: " + unavailable); }
            }
            return;
        }
        SystemUiEntry.install(lpparam.classLoader);
    }

    static void hookMiTypeAutoFloating(ClassLoader cl) {
        MiTypeHooks.install(cl, RUNTIME_MARKER, HostEntry::log);
    }

    // ---------------------------------------------------------------- v1.1.0：系统框架整屏缩放（R2 同款）

    static volatile Object sDisplayContent;
    static volatile Object sSystemContext;
    static volatile boolean sZoomed;
    static volatile ZoomTarget sZoomTarget;
    /** 缩放动画只在 WM handler 上创建/取消；帧内矩阵仍在 mGlobalLock 内应用。 */
    static volatile ValueAnimator sZoomAnimator;
    static volatile String sZoomAnimKey;
    static volatile boolean sZoomFrameApply;
    static volatile PathInterpolator zoomAnimInterpolator;
    static volatile float sZoomScale = 1f, sZoomOffset, sZoomOffsetX;
    static final long sZoomWatcherStarted = System.currentTimeMillis();
    static final KeyguardZoom sKeyguardZoom = new KeyguardZoom();
    static final ZoomChannelState sZoomChannels = new ZoomChannelState(sZoomWatcherStarted);
    static volatile android.os.FileObserver[] sZoomFileObservers;
    static volatile boolean sZoomObserversArmed;
    static final Object sZoomWatchLock=new Object();
    static volatile boolean sZoomWatchScreenOn = true;
    static java.util.concurrent.ScheduledFuture<?> sZoomWatch;
    static long sZoomWatchGeneration;
    static volatile Handler sZoomBridgeHandler;
    static final ZoomHostLease sZoomHostLease = new ZoomHostLease(ZoomRuntime::onZoomHostLost);
    static volatile boolean sHostZoomed;
    static volatile long sUnlockPhysicalShadeGeneration, sUnlockPhysicalShadeUntil;
    static final RetainedWindow sRetainedWindow = new RetainedWindow();
    static volatile String sZoomReadError;
    static volatile String sZoomLastSeen;
    static volatile String sZoomLastDispatched;
    static volatile String sZoomLastTargetNull;
    static ZoomCommit sLiveZoomCommit;
    static long sLiveZoomIssued;
    static volatile boolean sFullscreenRotationActive;
    static volatile int sHeldDisplayRotation;
    static volatile Object sFixedRotationApp;
    static volatile int sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
    /** Accessed only under the WMS global lock; these are per-token, not display launches. */
    static final java.util.Map<Object, Integer> sTaskRotations =
            new java.util.WeakHashMap<>();
    static final java.util.Map<Object, java.lang.ref.WeakReference<Object>> sTaskRotationTransforms =
            new java.util.WeakHashMap<>();
    /** Only surfaces actually written by our WM magnification, under its global lock. */
    static final java.util.Map<Object, Object> sMagnifiedSurfaces =
            new java.util.WeakHashMap<>();
    static final java.util.Map<Object, Object> sMagnificationFinishReplays =
            new java.util.WeakHashMap<>();
    static final java.util.Map<Object, Object> sHomeTransitionInfos =
            new java.util.WeakHashMap<>();
    static final ThreadLocal<Boolean> sFinalMagnificationSnapshot = new ThreadLocal<>();
    static final ThreadLocal<Object> sMagnificationFinishInfo = new ThreadLocal<>();
    static final NativeFreeformStartGate sNativeFreeformStart = new NativeFreeformStartGate();
    static final NativeFreeformStartGate sLiveSwapZoomSurfaces = new NativeFreeformStartGate();
    /** Per-task state survives top-activity changes and main/side ownership swaps. */
    static final java.util.Map<Object, TaskWindowState> sTaskWindowStates =
            new java.util.WeakHashMap<>();
    static java.lang.reflect.Method sTaskRotationResolver;
    static boolean sUpdatingTaskRotation;
    static final java.util.Map<Object, String> sGestureSurfaces =
            new java.util.WeakHashMap<>();


    static volatile boolean sZoomBridgeArmed;
    static volatile android.content.Context sWmsContext;

    /** Settings.Global observer avoids a permanent Binder polling thread. */


    static volatile boolean sDragShareEnabled=true;

    static boolean dragShareActive() {
        return sDragShareEnabled && sZoomScale>0 && sZoomScale<1f && !sKeyguardZoom.sleeping();
    }


    static synchronized void armZoomBridge(Object service) {
        SystemServerZoomBridge.arm(service);
    }

    /** Snapshot operations only. Shell merges and applies them with the task handoff. */
    static Object createMagnificationFinishReplay(Object display) throws Exception {
        Object replay = TransactionCompat.newSurfaceTransaction();
        try {
            reapplyFinalMagnification(display, replay);
            return replay;
        } catch (Throwable failure) {
            LiveSwapZoom.closeReplay(replay);
            throw new IllegalStateException("current magnification snapshot failed", failure);
        }
    }

    static void reapplyFinalMagnification(Object display, Object transaction) {
        reapplyFinalMagnification(display, transaction, null);
    }

    static void reapplyFinalMagnification(Object display, Object transaction, Object info) {
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

    static void receiveLiveZoom(Context context, Intent intent) {
        SystemServerZoomBridge.receiveLive(context, intent);
    }

    /** watcher 与 FileObserver 共用：读文件、按租约解析、投递到 WM handler。 */


    /** Replay on target creation and restore only when the shared host lease expires. */


    /** Preserve active WM magnification after Shell resets its transition targets. */
    static void finishNativeFreeformStart(ZoomTarget target, Object start) {
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

    static final SystemUiRotation sMainSystemUiRotation = new SystemUiRotation();
    static final MainWindowClip sMainWindowClip = new MainWindowClip();
    static final TopSystemBars sTopSystemBars = new TopSystemBars();
    static final ImeProcessConfiguration sMainImeProcessConfiguration =
            new ImeProcessConfiguration();
    static final ImeViewport sInteractiveImeViewport = new ImeViewport();
    static Object sPublishedMainUiState;
    static final PassBlurCoordinates sPassBlurCoordinates = new PassBlurCoordinates();

    static void stagePassBlurCoordinates(Object display, Object transaction, Object spec) {
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

    static void rememberMagnifiedSurface(Object container, Object spec) {
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

    static int restoreExitedMagnificationLocked(ZoomTarget target) {
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


    static final ThreadLocal<Boolean> sReadingWorkspaceFocus = new ThreadLocal<>();
    static final ThreadLocal<Object> sWindowFocusScanDisplay = new ThreadLocal<>();
    static final ThreadLocal<Boolean> sWorkspaceLauncherTaskQuery = new ThreadLocal<>();

    void applyDisplayZoomLocked(ZoomTarget target, float scale, float offsetY, float offsetX)
            throws Throwable {
        zoomTransactions.applyDisplayZoomLocked(target, scale, offsetY, offsetX);
    }

    void applyDisplayZoomLocked(ZoomTarget target, float scale, float offsetY, float offsetX,
            ZoomCommit commit, boolean nativeVisibility) throws Throwable {
        zoomTransactions.applyDisplayZoomLocked(target, scale, offsetY, offsetX, commit, nativeVisibility);
    }

    void cancelZoomAnimation(ZoomTarget target) {
        zoomTransactions.cancelZoomAnimation(target);
    }

    void startZoomAnimation(ZoomTarget target, ZoomAnimCommand.Plan plan, String key) {
        zoomTransactions.startZoomAnimation(target, plan, key);
    }

    static void log(String msg) { ModuleLog.diagnostic("entry", msg); }


    void beginFixedMainRotationLocked(Object display) {
        rotations.beginFixedMainRotationLocked(display);
    }

    void refreshTaskRotationLocked(Object display) {
        rotations.refreshTaskRotationLocked(display);
    }

    void endFixedMainRotationLocked(Object display) {
        rotations.endFixedMainRotationLocked(display);
    }

    static void publishMainUiConfiguration(Object app) {
        SystemServerHooks.publishMainUiConfiguration(app);
    }

    static void resetForLock() throws Throwable {
        SystemServerHooks.resetForLock();
    }

}

