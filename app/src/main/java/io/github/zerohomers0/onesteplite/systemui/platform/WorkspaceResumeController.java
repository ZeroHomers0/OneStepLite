package io.github.zerohomers0.onesteplite;

import android.view.View;
import de.robv.android.xposed.XposedHelpers;

/** Owns workspace resume confirmation, retained task readoption and deferred suspend completion. */
final class WorkspaceResumeController {
    private static final int SLOT_MAIN = WorkspaceHost.coordinatorSlotMain();
    private static final int SLOT_TOP = WorkspaceHost.coordinatorSlotTop();

    private final WorkspaceHost host;

    WorkspaceResumeController(WorkspaceHost host) {
        this.host = host;
    }

    void resumeSession() {
        host.cancelDormantTrim();
        if (host.scene != WorkspaceHost.SUSPENDED || !host.screenOn || host.root == null || !EntryGuard.canOpen(host.ctx)) return;
        host.landscapeSuspended = false;
        host.scene = WorkspaceHost.RESUMING;
        if (host.wallpaperBackdropDirty) host.loadWallpaperBackdrop();
        final long epoch = ++host.sceneEpoch;
        // Read the foreground before showing our overlay or reclaiming any saved task.
        // A desktop launch while suspended is the user's new main-task choice.
        if (!host.executeOnShell(() -> {
            try {
                final java.util.Set<Object> foreground = new java.util.HashSet<>();
                for (Object info : host.rootInfos()) {
                    if (RetainedTaskRestorePolicy.preserveAsMain(host.ordinaryRoot(info, 1),
                            XposedHelpers.getBooleanField(info, "isVisible"),
                            XposedHelpers.getBooleanField(info, "isFocused"))) {
                        foreground.add(XposedHelpers.getObjectField(info, "token"));
                    }
                }
                host.main.post(() -> {
                    if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch) return;
                    if (host.retainedOnExit) {
                        host.exits.releaseForegroundRetainedTasks(epoch, foreground, 0,
                                () -> showResumingSession(epoch));
                        return;
                    }
                    for (Slot saved : host.slots) {
                        if (saved.parked && saved.occupied && saved.taskToken != null
                                && !RetainedTaskRestorePolicy.reclaim(
                                        saved.occupied, saved.parked, saved.taskToken, foreground)) {
                            int id = saved.taskId;
                            TaskWindowState state = saved.windowState;
                            saved.windowState = null;
                            host.clearSlot(saved); // Forget the slot; never remove or move the live task.
                            if (state != null) { state.slotIndex = SLOT_MAIN; state.active = true; }
                            WorkspaceHost.log("resume kept desktop foreground task=" + id + " in main; old cell released");
                        }
                    }
                    showResumingSession(epoch);
                });
            } catch (Throwable error) {
                host.main.post(() -> {
                    if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch) return;
                    host.scene = WorkspaceHost.SUSPENDED;
                    WorkspaceHost.log("resume stopped before reclaiming tasks: " + error);
                });
            }
        })) { host.scene = WorkspaceHost.SUSPENDED; WorkspaceHost.log("resume stopped: no Shell queue"); }
    }

    private void showResumingSession(long epoch) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch) return;
        if (host.retainedForLock) { host.keyguardResumeIndex = 1; return; }
        if (!EntryGuard.canOpen(host.ctx)) { host.scene = WorkspaceHost.SUSPENDED; return; }
        if (DeviceProfile.isTablet(host.ctx)) host.refreshDisplayGeometry();
        host.consumePendingMainSide();
        host.setWorkspaceAlpha(0f);
        host.root.setVisibility(View.VISIBLE);
        host.setMirrorLayerVisible(true);
        host.main.postDelayed(host::collapseStatusBarPanels, 220L);
        if (host.retainedOnExit) {
            // Restore every bound task while all SurfaceViews are still hidden.
            // The old path waited until the main zoom/fade had already finished.
            host.workspaceEntering = true;
            awaitRetainedWorkspaceFrame(epoch);
        } else host.beginWorkspaceEntry(() -> resumeNext(1, epoch));
    }

    private void awaitRetainedWorkspaceFrame(long epoch) {
        final long generation = ++host.workspaceGeneration;
        Runnable ready = () -> host.main.post(() -> {
            if (host.scene == WorkspaceHost.RESUMING && host.sceneEpoch == epoch && host.workspaceGeneration == generation
                    && host.retainedOnExit && !host.retainedForLock && !host.keyguardLocked && host.screenOn && !host.suspendRequested)
                confirmRetainedResume(epoch, 0);
        });
        if (host.root.isAttachedToWindow() && host.root.isHardwareAccelerated()) {
            host.root.getViewTreeObserver().registerFrameCommitCallback(ready);
            host.root.invalidate();
        } else ready.run();
    }

    private void confirmRetainedResume(long epoch, int attempt) {
        confirmRetainedResume(epoch, attempt, false);
    }

    private void confirmRetainedResume(long epoch, int attempt, boolean visibilityRestored) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch || !host.retainedOnExit) return;
        if (host.retainedForLock) { host.keyguardResumeIndex = 1; return; }
        if (host.suspendRequested || !host.screenOn) {
            host.scene = WorkspaceHost.ACTIVE;
            host.suspendSession("navigation-during-retained-resume");
            return;
        }
        if (!host.executeOnShell(() -> {
            try {
                Object shell = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                ShellCompat.call(shell, "runOnIdle", (Runnable) () -> {
                    boolean ready = true;
                    String detail = "ok";
                    java.util.ArrayList<Object> hiddenRows = new java.util.ArrayList<>();
                    try {
                        for (Slot slot : host.slots) {
                            if (slot == null || !slot.occupied || slot.parked) continue;
                            Object core = host.rootInfo(new LiveTask(slot));
                            if (!host.isActive(slot) || !host.controllerOwns(slot, slot.taskId, slot.taskToken)
                                    || !host.ordinaryRoot(core, 6)) {
                                ready = false;
                                detail = "task/controller mismatch slot=" + slot.index;
                                hiddenRows.clear();
                                break;
                            }
                            if (slot.view == null || slot.view.getVisibility() != View.VISIBLE) continue;
                            Object leash = WorkspaceHost.getSurfaceField(slot.controller, "mTaskLeash");
                            if (leash == null || !Boolean.TRUE.equals(
                                    XposedHelpers.callMethod(leash, "isValid"))) {
                                ready = false;
                                detail = "task leash unavailable slot=" + slot.index;
                                hiddenRows.clear();
                                break;
                            }
                            if (!XposedHelpers.getBooleanField(core, "isVisible")) {
                                ready = false;
                                detail = "task hidden slot=" + slot.index;
                                if (slot.sideRow >= 0 ? slot.sidePrepared
                                        : slot.index > SLOT_MAIN && slot.index < host.CELL_SLOT_COUNT)
                                    hiddenRows.add(slot.taskToken);
                            }
                        }
                        boolean restore = !visibilityRestored && attempt < 2 && !hiddenRows.isEmpty()
                                && host.scene == WorkspaceHost.RESUMING && host.sceneEpoch == epoch && host.retainedOnExit
                                && host.screenOn && !host.suspendRequested && !host.retainedForLock;
                        // Shell's TO_FRONT on SurfaceView recreation can leave the roots below
                        // the current fullscreen task. Re-show the same bound roots after Shell is idle.
                        if (restore) {
                            Object wct = Class.forName("android.window.WindowContainerTransaction")
                                    .getDeclaredConstructor().newInstance();
                            for (Object token : hiddenRows) {
                                XposedHelpers.callMethod(wct, "setHidden", token, false);
                                XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, true);
                                XposedHelpers.callMethod(wct, "reorder", token, true);
                            }
                            host.applyWct("resume-retained-side-rows", wct);
                        }
                        final boolean restored = visibilityRestored || restore;
                        final boolean confirmed = ready;
                        final String result = detail;
                        host.main.post(() -> finishRetainedResumeCheck(epoch, attempt, restored, confirmed, result));
                    } catch (Throwable error) {
                        host.main.post(() -> finishRetainedResumeCheck(
                                epoch, attempt, visibilityRestored, false, error.toString()));
                    }
                });
            } catch (Throwable error) {
                host.main.post(() -> finishRetainedResumeCheck(
                        epoch, attempt, visibilityRestored, false, error.toString()));
            }
        })) finishRetainedResumeCheck(epoch, attempt, visibilityRestored, false, "no Shell queue");
    }

    private void finishRetainedResumeCheck(long epoch, int attempt, boolean visibilityRestored,
            boolean ready, String detail) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch || !host.retainedOnExit) return;
        if (host.retainedForLock) { host.keyguardResumeIndex = 1; return; }
        if (host.suspendRequested || !host.screenOn) {
            host.scene = WorkspaceHost.ACTIVE;
            host.suspendSession("navigation-during-retained-resume");
            return;
        }
        if (ready) {
            prepareRetainedWorkspaceEntry(epoch);
        } else if (attempt < 2) {
            host.main.postDelayed(() -> confirmRetainedResume(epoch, attempt + 1, visibilityRestored), 250L);
        } else {
            WorkspaceHost.log("retained resume fell back to native task handback: " + detail);
            host.retainedOnExit = false;
            host.scene = WorkspaceHost.ACTIVE;
            host.suspendSession("retained-resume-fallback");
        }
    }

    private void prepareRetainedWorkspaceEntry(long epoch) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch || !host.retainedOnExit
                || host.retainedForLock || host.keyguardLocked || !host.screenOn || host.suspendRequested) return;
        final long generation = host.workspaceGeneration;
        final boolean[] committed = {false};
        Object tx = null;
        try {
            host.setWorkspaceAlpha(0f);
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            host.appendWorkspaceSurfaces(tx, java.util.Collections.singletonList(epoch));
            ((android.view.SurfaceControl.Transaction) tx).addTransactionCommittedListener(
                    action -> host.main.post(action), () -> {
                if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch || !host.retainedOnExit
                        || host.workspaceGeneration != generation
                        || host.retainedForLock || host.keyguardLocked || !host.screenOn || host.suspendRequested) return;
                committed[0] = true;
                host.beginWorkspaceEntry(() -> completeWorkspaceResume(epoch, true));
            });
            XposedHelpers.callMethod(tx, "apply");
            host.main.postDelayed(() -> {
                if (!committed[0] && host.scene == WorkspaceHost.RESUMING && host.sceneEpoch == epoch
                        && host.workspaceGeneration == generation && host.retainedOnExit
                        && !host.retainedForLock && !host.keyguardLocked && host.screenOn && !host.suspendRequested) {
                    WorkspaceHost.log("retained entry parent commit timed out; keep partial tasks hidden");
                    host.retainedOnExit = false;
                    host.scene = WorkspaceHost.ACTIVE;
                    host.suspendSession("retained-entry-commit-timeout");
                }
            }, 1500L);
        } catch (Throwable error) {
            WorkspaceHost.log("retained entry parent preparation failed: " + error);
            host.retainedOnExit = false;
            host.scene = WorkspaceHost.ACTIVE;
            host.suspendSession("retained-entry-preparation-failed");
        } finally { host.closeSurfaceObject(tx, "close"); }
    }

    private void completeWorkspaceResume(long epoch, boolean retained) {
        if (host.scene != WorkspaceHost.RESUMING || host.sceneEpoch != epoch) return;
        host.scene = WorkspaceHost.ACTIVE;
        if(host.lockExitPending){if(!host.retainedForLock)host.suspendSession("unlock-exit");return;}
        host.retainedOnExit = false;
        host.configureTabletTools();
        host.publishDesktopSideTasks();
        host.cells.requestCompaction();
        host.topBar.applyOptions(host.workspaceOptions);
        host.refreshQuickApps();
        host.reassertSideTaskViews(retained ? "resume retained bindings" : "resume complete");
        // Leaving the workspace tears the exit plate down with the host.scene; a retained
        // upper task needs it back as soon as the host.scene is active again.
        host.updateTopExitWindow();
        WorkspaceHost.log((retained ? "host resumed with retained task bindings: "
                : "host resumed from retained task identities: ") + host.describeTasks());
        finishPendingSuspend();
    }

    void resumeNext(int index, long epoch) {
        if (host.scene != WorkspaceHost.RESUMING || epoch != host.sceneEpoch) return;
        if (host.retainedForLock) { host.keyguardResumeIndex = index; return; }
        if (host.suspendRequested || !host.screenOn) {
            host.scene = WorkspaceHost.ACTIVE;
            host.suspendSession("navigation-during-resume");
            return;
        }
        if (index >= host.slots.length) {
            completeWorkspaceResume(epoch, false);
            return;
        }
        Slot slot = host.slots[index];
        if (!slot.occupied || !slot.parked) { resumeNext(index + 1, epoch); return; }
        LiveTask saved = new LiveTask(slot);
        host.adoptExisting(slot, saved, () -> resumeNext(index + 1, epoch), error -> {
            // External task removal is a real empty cell; a failed admission never launches it.
            host.updateHint(slot);
            WorkspaceHost.log("resume task unavailable; keep existing system task, no relaunch: " + error);
            resumeNext(index + 1, epoch);
        });
    }

    void finishPendingSuspend() {
        if (host.suspendRequested && host.scene == WorkspaceHost.ACTIVE && host.promotion == null && !host.transferBusy) {
            String reason = host.pendingSuspendReason;
            host.pendingSuspendReason = null;
            host.suspendRequested = false;
            host.suspendSession(reason == null ? "deferred" : reason);
        }
    }
}
