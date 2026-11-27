package io.github.zerohomers0.onesteplite;

import static io.github.zerohomers0.onesteplite.HostEntry.*;
import static io.github.zerohomers0.onesteplite.NativeWindowRuntime.*;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Looper;
import de.robv.android.xposed.XposedHelpers;

/** Applies WMS zoom transactions and runs their frame and commit callbacks. */
final class ZoomTransactions {
    private final HostEntry owner;

    ZoomTransactions(HostEntry owner) {
        this.owner = owner;
    }

    void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, null);
    }

    private void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX, ZoomCommit commit) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, commit, false);
    }

    void applyDisplayZoomLocked(ZoomTarget target, float scale,
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
            if (sZoomed) owner.beginFixedMainRotationLocked(dc);
            else owner.endFixedMainRotationLocked(dc);
            installZoomCommit(target,dc,commit);
            XposedHelpers.callMethod(dc, "applyMagnificationSpec", spec);
        } catch (Throwable error) {
            sZoomed = previous;
            sNativeLandscapeState = previousLandscapeState;
            if (!rotationWasActive) owner.endFixedMainRotationLocked(dc);
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
        clearLegacyDisplayCrop(dc);
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

    static void applyGestureSurfacesLocked(ZoomTarget target, float scale,
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

    private static void installZoomCommit(ZoomTarget target,Object dc,ZoomCommit commit) throws Throwable {
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
    }

    private static void clearLegacyDisplayCrop(Object dc) {
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
    }
}
