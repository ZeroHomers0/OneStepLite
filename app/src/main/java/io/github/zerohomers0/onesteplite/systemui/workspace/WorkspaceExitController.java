package io.github.zerohomers0.onesteplite;

import android.view.View;
import de.robv.android.xposed.XposedHelpers;

/** Owns retained-exit cleanup, hidden TaskView release and unbound task recovery. */
final class WorkspaceExitController {
    private static final int SLOT_MAIN = WorkspaceHost.SLOT_MAIN;
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;

    private final WorkspaceHost host;
    private Runnable dormantTaskRelease;

    WorkspaceExitController(WorkspaceHost host) {
        this.host = host;
    }

    boolean canRetainBoundTasks() {
        if (host.root == null || (host.root.getVisibility() != View.VISIBLE&&!host.lockExitPending) || !host.screenOn || host.retainedForLock
                || host.promotion != null || host.transferBusy || host.workspaceEntering || host.workspaceAnimator != null
                || host.topTransition != null
                || host.cells.motion() != null
                || (host.sideDismiss != null && host.sideDismiss.isTracking())) return false;
        int bound = 0;
        for (Slot slot : host.slots) {
            if (slot == null || !slot.occupied) continue;
            if (!host.slotRegistry.isActive(slot) || slot.parked || slot.frozen || slot.promoting || slot.launching
                    || slot.closing || slot.nativeReleasePending || slot.windowMode != 6
                    || slot.taskToken == null || !host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) return false;
            bound++;
        }
        if (bound == 0) return false;
        for (OwnedTask task : host.ownedTasks.values()) if (task.recoveryCandidate) return false;
        return true;
    }

    void finishRetainedSuspend(long epoch) {
        if (host.scene != WorkspaceHost.SUSPENDING || host.sceneEpoch != epoch) return;
        host.retainedOnExit = true;
        host.windowPresentation.retireWorkspaceBackdrop();
        host.scales.cancelAllScaleRepairs();
        host.scales.clearAllFrameRateHints();
        host.root.setVisibility(View.GONE);
        host.carriers.setMirrorLayerVisible(false);
        host.scene = WorkspaceHost.SUSPENDED;
        if (dormantTaskRelease == null)
            dormantTaskRelease = () -> releaseHiddenRetainedTasks(1);
        host.main.removeCallbacks(dormantTaskRelease);
        host.main.postDelayed(dormantTaskRelease, 60_000L);
        host.publishDesktopSideTasks();
        host.lockExitPending=false;
        WorkspaceHost.log("host hidden; live TaskView bindings retained: " + host.debug.describeTasks());
        releaseVisibleNativeRetainedTask();
    }

    /** Under memory pressure, favor task retention over keeping hidden TaskViews resident. */
    void releaseHiddenRetainedTasksForMemory() {
        if (host.scene == WorkspaceHost.SUSPENDED && host.retainedOnExit && !host.retainedForLock) {
            releaseHiddenRetainedTasks(1);
        }
    }

    private void releaseHiddenRetainedTasks(int fromIndex) {
        if (host.scene != WorkspaceHost.SUSPENDED || !host.retainedOnExit || host.retainedForLock
                || host.transferBusy || host.promotion != null) return;
        for (int index = Math.max(1, fromIndex); index < host.slots.length; index++) {
            Slot slot = host.slots[index];
            if (slot == null || !slot.occupied || slot.parked || slot.promoting
                    || slot.closing || slot.nativeReleasePending || slot.taskId < 0
                    || slot.taskToken == null) continue;
            final int taskId = slot.taskId;
            final Object token = slot.taskToken;
            final int next = index + 1;
            WorkspaceHost.log("hidden retention timeout; release task=" + taskId + " slot=" + index);
            host.returnToSystem(slot, false, () -> {
                if (slot.taskId == taskId && token.equals(slot.taskToken)) host.slotLifecycle.clearSlot(slot);
                host.retainedOnExit = hasBoundTasks();
                host.publishDesktopSideTasks();
                host.main.postDelayed(() -> releaseHiddenRetainedTasks(next), 50L);
            }, error -> WorkspaceHost.log("hidden retained task return stopped task=" + taskId + ": " + error));
            return;
        }
        host.retainedOnExit = false;
        host.publishDesktopSideTasks();
        WorkspaceHost.log("hidden retained task views released after timeout");
    }

    private void releaseVisibleNativeRetainedTask() {
        for (Slot slot : host.slots) {
            if (slot.index <= SLOT_MAIN || !slot.occupied || slot.parked || slot.controller == null) continue;
            try {
                Object info = ShellCompat.currentTaskInfo(slot.controller, slot.taskId);
                if (host.slotRegistry.ordinaryRoot(info, 1) && XposedHelpers.getBooleanField(info, "isRunning")) {
                    releaseRetainedTaskToMain(slot, true);
                    return;
                }
            } catch (Throwable unavailable) {
                WorkspaceHost.log("retained native launch check unavailable slot=" + slot.index + ": " + unavailable);
            }
        }
    }

    void returnUnboundOwnedCandidates(long epoch, Runnable complete) {
        OwnedTask next = null;
        for (OwnedTask candidate : host.ownedTasks.values()) {
            if (!candidate.recoveryCandidate) continue;
            boolean inSlot = false;
            for (Slot slot : host.slots) if (slot.index > 0 && slot.occupied
                    && candidate.token.equals(slot.taskToken)) { inSlot = true; break; }
            if (!inSlot) { next = candidate; break; }
        }
        if (next == null) { complete.run(); return; }
        returnUnboundOwnedCandidate(next, epoch, 0, complete);
    }

    private void returnUnboundOwnedCandidate(OwnedTask candidate, long epoch, int attempt, Runnable complete) {
        if (host.scene != WorkspaceHost.SUSPENDING || host.sceneEpoch != epoch || host.ownedTasks.get(candidate.token) != candidate) return;
        if (!host.executeOnShell(() -> {
            if (host.scene != WorkspaceHost.SUSPENDING || host.sceneEpoch != epoch || host.ownedTasks.get(candidate.token) != candidate) return;
            boolean ready = false;
            String error = "owned candidate not yet returned";
            try {
                Object controller = candidate.recoveryController;
                if (controller == null || candidate.userId < 0) throw new IllegalStateException("candidate owner/user absent");
                LiveTask identity = new LiveTask(candidate.id, candidate.token, null);
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object core = host.slotRegistry.rootInfo(identity);
                Object appeared;
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(candidate.id);
                    if (appeared != null && !identity.matches(XposedHelpers.callMethod(appeared, "getTaskInfo")))
                        throw new IllegalStateException("candidate canonical token changed");
                }
                if (core == null) {
                    if (appeared != null) throw new IllegalStateException("candidate still in organizer outside host.root list");
                    Object pending = XposedHelpers.getObjectField(controller, "mPendingInfo");
                    if (candidate.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))
                            || (pending != null && identity.matches(pending)))
                        throw new IllegalStateException("candidate still referenced by its controller");
                    ready = true; // Exact task is gone from both sources, never infer death from roots alone.
                } else {
                    if (!identity.matches(core) || XposedHelpers.getIntField(core, "userId") != candidate.userId
                            || (!host.slotRegistry.ordinaryRoot(core, 1) && !host.slotRegistry.ordinaryRoot(core, 6)))
                        throw new IllegalStateException("candidate identity/user/display/role changed");
                    Slot recovery = new Slot(-1);
                    recovery.controller = controller; recovery.taskId = candidate.id;
                    recovery.taskToken = candidate.token;
                    Promotion handback = new Promotion(recovery, new Slot[0]);
                    handback.background = true; handback.candidateRecovery = true;
                    if (attempt == 0 || host.slotRegistry.ordinaryRoot(core, 6)) {
                        if (!WorkspaceNativeHooks.applyWct("return-unbound-owned-candidate", host.taskTransfer.fullscreenExitTransaction(handback)))
                            throw new IllegalStateException("candidate fullscreen transaction refused");
                    } else {
                        host.promotionController.handoffPromotedTask(handback);
                        ready = handback.handoffComplete;
                    }
                }
            } catch (Throwable failure) { error = failure.toString(); }
            final boolean returned = ready;
            final String detail = error;
            host.main.post(() -> {
                if (host.scene != WorkspaceHost.SUSPENDING || host.sceneEpoch != epoch || host.ownedTasks.get(candidate.token) != candidate) return;
                if (returned) {
                    host.ownedTasks.remove(candidate.token);
                    WorkspaceHost.log("unbound owned candidate returned alive task=" + candidate.id);
                    returnUnboundOwnedCandidates(epoch, complete);
                } else if (attempt < 3) {
                    host.main.postDelayed(() -> returnUnboundOwnedCandidate(candidate, epoch, attempt + 1, complete), 350L);
                } else {
                    // Preserve the exact A/B ledger and attached surfaces for a later explicit exit.
                    host.scene = WorkspaceHost.ACTIVE;
                    if (host.screenOn && !host.keyguardLocked) { host.windowPresentation.setWorkspaceAlpha(1f); host.zoomSession.writeZoom(true); host.zoomSession.setZoomState(true); }
                    WorkspaceHost.log("suspend stopped; unresolved owned candidate retained task=" + candidate.id + ": " + detail);
                }
            });
        })) {
            host.scene = WorkspaceHost.ACTIVE;
            if (host.screenOn && !host.keyguardLocked) { host.windowPresentation.setWorkspaceAlpha(1f); host.zoomSession.writeZoom(true); host.zoomSession.setZoomState(true); }
            WorkspaceHost.log("suspend stopped; owned candidate Shell executor unavailable task=" + candidate.id);
        }
    }

    void parkNext(int index) {
        if (index >= host.slots.length) {
            host.windowPresentation.retireWorkspaceBackdrop();
            host.scales.cancelAllScaleRepairs();
            host.scales.clearAllFrameRateHints();
            host.root.setVisibility(View.GONE);
            host.carriers.setMirrorLayerVisible(false);
            host.scene = WorkspaceHost.SUSPENDED;
            host.publishDesktopSideTasks();
            host.lockExitPending=false;
            WorkspaceHost.log("host suspended; all side tasks returned alive: " + host.debug.describeTasks());
            if(DeviceProfile.isTablet(host.ctx))host.displays.scheduleDisplayGeometryRefresh();
            return;
        }
        Slot slot = host.slots[index];
        if (!slot.occupied || slot.parked || slot.taskToken == null) {
            parkNext(index + 1);
            return;
        }
        host.returnToSystem(slot, false, () -> {
            if(index==SLOT_TOP) {
                host.topTaskState.sceneChanged();host.slotLifecycle.clearSlot(slot);host.topFace=false;
                if(host.topBar!=null && !host.layout.tabletLandscape) {host.topBar.setVisibility(View.VISIBLE);host.topBar.bringToFront();}
            }
            parkNext(index + 1);
        }, error -> {
            // Some peers may already be parked. Restore them before exposing ACTIVE again.
            host.scene = WorkspaceHost.RESUMING;
            host.windowPresentation.setWorkspaceAlpha(1f);
            host.zoomSession.writeZoom(true);
            host.zoomSession.setZoomState(true);
            WorkspaceHost.log("suspend incomplete; restore parked peers before resuming: " + error);
            host.resumes.resumeNext(1, host.sceneEpoch);
        });
    }

    void releaseForegroundRetainedTasks(long epoch, java.util.Set<Object> foreground,
            int index, Runnable complete) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch) return;
        if (index >= host.slots.length) { complete.run(); return; }
        Slot slot = host.slots[index];
        if (!host.retainedOnExit || slot == null || !slot.occupied || slot.taskToken == null
                || !foreground.contains(slot.taskToken)) {
            releaseForegroundRetainedTasks(epoch, foreground, index + 1, complete);
            return;
        }
        int taskId = slot.taskId;
        Object token = slot.taskToken;
        if (!host.slotRegistry.controllerOwns(slot, taskId, token)) {
            TaskWindowState state = slot.windowState;
            slot.windowState = null;
            host.slotLifecycle.clearSlot(slot);
            if (state != null) { state.slotIndex = SLOT_MAIN; state.active = true; }
            WorkspaceHost.log("resume kept externally opened task=" + taskId + " in main");
            host.retainedOnExit = hasBoundTasks();
            releaseForegroundRetainedTasks(epoch, foreground, index + 1, complete);
            return;
        }
        host.returnToSystem(slot, true, () -> {
            host.slotLifecycle.clearSlot(slot);
            host.retainedOnExit = hasBoundTasks();
            WorkspaceHost.log("resume released externally opened task=" + taskId + " to main");
            releaseForegroundRetainedTasks(epoch, foreground, index + 1, complete);
        }, error -> {
            if (host.scene == WorkspaceHost.RESUMING && host.sceneEpoch == epoch) {
                host.scene = WorkspaceHost.SUSPENDED;
                WorkspaceHost.log("resume kept hidden; foreground task handoff failed id=" + taskId + ": " + error);
            }
        });
    }

    boolean hasBoundTasks() {
        for (Slot slot : host.slots) if (slot != null && slot.occupied && !slot.parked
                && slot.taskToken != null) return true;
        return false;
    }

    void releaseRetainedTaskToMain(Slot slot) {
        releaseRetainedTaskToMain(slot, false);
    }

    void releaseRetainedTaskToMain(Slot slot, boolean nativeAlreadyFullscreen) {
        if (!host.retainedOnExit || host.scene != WorkspaceHost.SUSPENDED || slot == null || !slot.occupied
                || slot.parked || slot.promoting || host.transferBusy || slot.taskId < 0 || slot.taskToken == null) return;
        int taskId = slot.taskId;
        Object token = slot.taskToken;
        WorkspaceHost.log("desktop opened retained task=" + taskId + "; keep it in the main area");
        host.returnToSystem(slot, true, false, nativeAlreadyFullscreen, () -> {
            if (slot.taskId == taskId && token.equals(slot.taskToken)) host.slotLifecycle.clearSlot(slot);
            host.retainedOnExit = host.exits.hasBoundTasks();
            WorkspaceHost.log("desktop task released from hidden workspace id=" + taskId);
            host.publishDesktopSideTasks();
        }, error -> WorkspaceHost.log("desktop task remains bound after handoff failure id=" + taskId + ": " + error));
    }

    void finishExternalTopLaunch(Slot upper) {
        if (host.scene != WorkspaceHost.ACTIVE || host.promotion != null || host.transferBusy || upper.promoting) return;
        host.returnToSystem(upper, true, false, true, () -> {
            host.slotLifecycle.clearSlot(upper);
            if (host.topFace) host.topPresentationHost.settleTopFace(false);
            host.resumes.finishPendingSuspend();
        }, error -> {
            WorkspaceHost.log("native TOP launch handback failed: " + error);
            host.resumes.finishPendingSuspend();
        });
    }
}
