package io.github.zerohomers0.onesteplite;

import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;

/** Routes screen state, authentication, and native navigation events. */
final class WorkspaceSystemEvents {
    private WorkspaceSystemEvents() {}

    static void onScreenState(boolean on) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null) {
            return;
        }
        h.main.post(() -> {
            h.screenOn = on;
            h.publishDesktopSideTasks();
            if (!on) {
                UnlockHandoff pending = h.unlockHandoff;
                h.unlockHandoff = null;
                if (pending != null) pending.cancel();
                h.debug.stopTrace();
                h.liveHandoff.closeSwapPreview();
                h.statusBar.restore();
                h.liveHandoff.closeCellMovePreview();
                if (!h.keyguard.retainWorkspaceForLock())
                    h.resumes.suspendSession("screen-lock");
            } else {
                if (h.retainedForLock) h.keyguard.leaveKeyguardPrivacy();
                h.chrome.updateTopBarState();
            }
        });
    }

    static void onUserPresent() {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h != null) h.main.post(h.keyguard::leaveKeyguardPrivacy);
    }

    static void onRetainedNativeReady(long generation) {
        WorkspaceHost h=WorkspaceHost.currentInstance();if(h==null)return;
        h.main.post(()->{
            if(h.retainedForLock&&generation>0&&generation==h.retainedWindowGeneration
                    &&EntryGuard.canOpen(h.ctx))h.keyguard.leaveKeyguardPrivacy();
        });
    }

    static void onNativeWake() {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null) return;
        Runnable restore = () -> {
            if (!h.retainedForLock) return;
            h.keyguard.leaveKeyguardPrivacy();
            if (!h.retainedForLock) WorkspaceHost.log("retained workspace restored from native wake");
        };
        if (Looper.myLooper() == h.main.getLooper()) restore.run();
        else h.main.post(restore);
    }

    static boolean handoffNativeUnlock(Runnable nativeHide) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null || Looper.myLooper() != h.main.getLooper() || !h.retainedForLock
                || !h.keyguardWindowHidden || h.root == null || h.layout == null
                || (h.scene != WorkspaceHost.ACTIVE && h.scene != WorkspaceHost.RESUMING)
                || !EntryGuard.canOpen(h.ctx) || h.unlockHandoff != null) return false;
        if(!h.workspaceOptions.keepOnLock)return h.keyguard.handoffLockExit(nativeHide);
        final long epoch = h.sceneEpoch;
        final boolean[] frameReady = {false};
        final UnlockHandoff[] owner = {null};
        h.screenOn = true;
        WorkspaceLayout geometry = h.layout;
        UnlockHandoff handoff = new UnlockHandoff(h.ctx, h.main,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY, ack -> {
            if (h.unlockHandoff != owner[0] || h != WorkspaceHost.currentInstance() || epoch != h.sceneEpoch || !h.retainedForLock
                    || !h.keyguardWindowHidden || !EntryGuard.canOpen(h.ctx)) {
                owner[0].finish(); return;
            }
            if(!h.workspaceOptions.keepOnLock){owner[0].finish();return;}
            h.keyguardLocked = false;
            h.workspaceGeneration++;
            h.workspaceEntering = false;
            h.zoomAnimUntil = 0;
            h.windowPresentation.setWorkspaceAlpha(1f);
            Object tx = null;
            try {
                h.unlockGeometryReplay.capture((android.view.SurfaceControl.Transaction) ack.finishReplay,
                        epoch, geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
                tx = TransactionCompat.newSurfaceTransaction();
                XposedHelpers.callMethod(tx, "merge", ack.finishReplay);
                h.retainedSurfaces.appendWorkspaceSurfaces(tx, java.util.Collections.singletonList(epoch));
                ((android.view.SurfaceControl.Transaction) tx).addTransactionCommittedListener(
                        action -> h.main.post(action), () -> {
                    if (h.unlockHandoff != owner[0] || h != WorkspaceHost.currentInstance() || epoch != h.sceneEpoch
                            || !h.retainedForLock || !EntryGuard.canOpen(h.ctx)) return;
                    try {
                        if(!h.workspaceOptions.keepOnLock){owner[0].finish();return;}
                        h.zoomSession.setZoomState(true);
                        if (!h.keyguard.setKeyguardWindowHidden(false)) throw new IllegalStateException("Host reveal failed");
                        h.root.getViewTreeObserver().registerFrameCommitCallback(() -> h.main.post(() -> {
                            UnlockHandoff current = h.unlockHandoff;
                            if (current == owner[0] && h == WorkspaceHost.currentInstance() && h.sceneEpoch == epoch
                                    && h.retainedForLock && EntryGuard.canOpen(h.ctx) && current.active()) {
                                h.unlockFinishGate.afterHostFrame(epoch, () -> {
                                    if (h.unlockHandoff != current || h != WorkspaceHost.currentInstance() || h.sceneEpoch != epoch
                                            || !h.retainedForLock || !EntryGuard.canOpen(h.ctx) || !current.active()) return;
                                    frameReady[0] = true;
                                    WorkspaceHost.log("Host frame and native unlock finish committed before main reveal");
                                    current.presented();
                                });
                            }
                        }));
                        h.root.invalidate();
                    } catch (Throwable error) {
                        WorkspaceHost.log("retained committed reveal failed: " + error);
                        owner[0].finish();
                    }
                });
                XposedHelpers.callMethod(tx, "apply");
            } catch (Throwable error) {
                WorkspaceHost.log("retained unlock handoff stopped: " + error);
                owner[0].finish();
            } finally { h.sideRows.closeSurfaceObject(tx, "close"); }
        }, () -> {
            if (h.unlockHandoff != owner[0]) return;
            h.unlockHandoff = null;
            if(h==WorkspaceHost.currentInstance()&&h.sceneEpoch==epoch&&h.retainedForLock&&!h.workspaceOptions.keepOnLock
                    &&EntryGuard.canOpen(h.ctx)){h.keyguard.handoffLockExit(nativeHide);return;}
            if (EntryGuard.canOpen(h.ctx)) {
                if (h == WorkspaceHost.currentInstance() && h.sceneEpoch == epoch && h.retainedForLock) {
                    if (frameReady[0]) {
                        h.retainedGeometryConfirmed = true;
                        h.keyguard.leaveKeyguardPrivacy();
                    } else {
                        // No valid WM replay/frame: never expose a clipped fullscreen
                        // buffer as a workspace. Return through normal task cleanup.
                        h.root.setVisibility(View.GONE);
                        h.retainedForLock = false;
                        h.retainedGeometryConfirmed = false;
                        h.keyguardLocked = false; // Full authentication was checked above.
                        h.keyguard.setKeyguardWindowHidden(false);
                        h.resumes.suspendSession("retained-unlock-failed");
                    }
                }
                nativeHide.run();
            }
        });
        owner[0] = handoff;
        h.unlockHandoff = handoff;
        WorkspaceHost.log("authenticated retained unlock waiting for WMS commit and Host frame");
        handoff.start();
        return true;
    }

    static void onSystemNavigation(String reason) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h != null) h.main.post(() -> {
            if (h.retainedForLock) return;
            if ("fs_gesture".equals(reason) || "gestureNav".equals(reason)) {
                h.emptyMainGesture.begin(h.sceneEpoch, SystemClock.uptimeMillis(),
                        h.resumes.emptyMainHomeFocused());
                return;
            }
            if ("recentapps".equals(reason)) {
                h.emptyMainGesture.clear();
                return;
            }
            if ("homekey".equals(reason) && h.scene == WorkspaceHost.ACTIVE
                    && h.emptyMainGesture.duplicateHome(h.sceneEpoch, SystemClock.uptimeMillis())) {
                WorkspaceHost.log("native overview follow-up Home retains empty workspace");
                return;
            }
            if ("homekey".equals(reason)
                    && h.emptyMainGesture.consume(h.sceneEpoch, SystemClock.uptimeMillis())
                    && h.resumes.emptyMainHomeFocused()) {
                boolean requested = NativeRecents.show();
                if (requested) h.emptyMainGesture.requested(SystemClock.uptimeMillis());
                WorkspaceHost.log("empty-main upward gesture retains workspace; native overview=" + requested);
                return;
            }
            if (NativeLandscapePolicy.shouldRestoreFromHome(
                    h.landscapeSuspended, h.scene == WorkspaceHost.SUSPENDED, reason)) {
                h.resumes.resumeSession();
            } else {
                h.resumes.suspendSession(reason);
            }
        });
    }

    static void onLandscapeMode(boolean fullscreen) {
        WorkspaceHost h = WorkspaceHost.currentInstance();
        if (h == null) return;
        h.main.post(() -> {
            if (h.retainedForLock) return;
            if (fullscreen) {
                if (h.scene == WorkspaceHost.SUSPENDED && h.retainedOnExit) {
                    h.landscapeSuspended = false;
                    return;
                }
                h.landscapeSuspended = true;
                if (h.scene == WorkspaceHost.ACTIVE) h.resumes.suspendSession("native-landscape");
            } else {
                h.landscapeSuspended = false;
                if (!(h.scene == WorkspaceHost.SUSPENDED && h.retainedOnExit)) h.resumes.resumeSession();
            }
        });
    }
}
