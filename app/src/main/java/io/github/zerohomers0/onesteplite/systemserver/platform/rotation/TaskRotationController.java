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
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns fixed display and per-task rotation under the WMS global lock. */
final class TaskRotationController {
    void installTopSystemBarReturn(ClassLoader cl) {
        TopSystemBarReturnHooks.install(cl, sTopSystemBars, () -> sZoomTarget,
                () -> sZoomed, () -> sFullscreenRotationActive, HostEntry::log);
    }
    /** Hold the workspace portrait; app transforms must never announce display rotation. */
    void installFixedMainRotationHooks(ClassLoader cl) {
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
                return TaskRotationController.this.applyFixedRotationLocked(display, activity, source);
            }
            @Override public void beforeApplicationOrientationRequest(Object activity, int request) {
                TaskRotationController.this.beforeApplicationOrientationRequest(activity, request);
            }
            @Override public boolean updatingTaskRotation() { return sUpdatingTaskRotation; }
            @Override public java.util.Map<Object, Integer> taskRotations() { return sTaskRotations; }
            @Override public boolean rotationActive() { return sFullscreenRotationActive; }
            @Override public Object displayContent() { return sDisplayContent; }
            @Override public boolean retainTaskRotationOnNativeFinish(Object activity) {
                return TaskRotationController.this.retainTaskRotationOnNativeFinishLocked(activity);
            }
            @Override public void setTaskRotationResolver(Method resolver) {
                sTaskRotationResolver = resolver;
            }
            @Override public int heldRotation() { return sHeldDisplayRotation; }
            @Override public void queueWorkspaceRotationRefresh(Object displayRotation) {
                TaskRotationController.this.queueWorkspaceRotationRefresh(displayRotation);
            }
            @Override public void evaluateNativeLandscape(Object display, String source) {
                TaskRotationController.this.evaluateNativeLandscapeLocked(display, source);
            }
            @Override public void refreshTaskRotation(Object display) {
                TaskRotationController.this.refreshTaskRotationLocked(display);
            }
        }, HostEntry::log);
    }

    /** Root-task bounds remain in display orientation while our main token is rotated. */
    void installFixedMainTouchableCropHook(ClassLoader cl) {
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

    static boolean cropFixedMainTouchableRegion(Object window,
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
    void captureMainBeforeSideChange(Object task,Object change){
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

    Boolean applyFixedRotationLocked(Object display, Object activity, String source) {
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
            if (home || (multiWindow && !sideTask)) return releaseMainForHomeLocked(activity,home,sideTask);
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
            return applyLocalTaskRotationLocked(display,activity,sideTask,resolution,source);
        } catch (Throwable error) {
            log("fixed rotation apply failed source=" + source + ": " + error);
            XposedBridge.log(error);
            return Boolean.FALSE;
        } finally {
            sUpdatingTaskRotation = false;
        }
    }

    static Object sApplyingSideRotationActivity;
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
            else BackgroundExecutor.execute(send);
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

    void noteTopRotationHandoff(Object page) {
        topRotationHandoffs.add(page);
    }

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

    void onTopRotationTransitionEnd() {
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

    /** Observe late fullscreen/insets changes too; players often hide bars after orientation. */
    void refreshWideVideoSignal(Object window) {
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
    static boolean isUpperLandscapeTask(Object task) {
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

    void refreshTaskRotationLocked(Object display) {
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
                synchronized (TaskRotationController.this) { workspaceRotationRefreshPending = false; }
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

    static final java.util.Map<Object, Rect> sTopRotationBounds = new java.util.WeakHashMap<>();
    static final java.util.Set<Object> sTopRotationConfigurationRelease =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static final java.util.Map<Object, TopRelaunchScope>
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

    boolean ownsTaskRotationTransform(Object activity) {
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

    static boolean isSideTask(Object task) {
        try {
            return Integer.valueOf(6).equals(XposedHelpers.callMethod(
                            task, "getWindowingMode"))
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(
                            task, "isForceTranslucent"));
        } catch (Throwable error) {
            return false;
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

    void beginFixedMainRotationLocked(Object display) {
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

    void endFixedMainRotationLocked(Object display) {
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
    private Boolean applyLocalTaskRotationLocked(Object display,Object activity,boolean sideTask,
            TaskRotationResolution resolution,String source) throws Throwable {
        int overrideOrientation=resolution.overrideOrientation;
        int resolvedRotation=resolution.resolvedRotation;
        int rotation=resolution.taskRotation;
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
    }

    private Boolean releaseMainForHomeLocked(Object activity,boolean home,boolean sideTask){
        Object previous=sFixedRotationApp;
        if(previous!=null){
            if(!isSideFixedTask(previous))
                releaseTaskRotationLocked(previous,home?"main activity left for home":"main activity left fullscreen");
            if(sFixedRotationApp==previous)sFixedRotationApp=null;
        }
        return Boolean.FALSE;
    }
}
