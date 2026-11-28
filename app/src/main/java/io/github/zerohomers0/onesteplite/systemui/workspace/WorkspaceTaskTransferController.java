package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.os.SystemClock;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Transfers existing tasks between a workspace cell and their native owner. */
final class WorkspaceTaskTransferController {
    private final WorkspaceHost host;

    WorkspaceTaskTransferController(WorkspaceHost host) {
        this.host = host;
    }

    void adoptExisting(Slot slot, LiveTask task, Runnable success, Consumer<String> failure) {
        adoptExisting(slot, task, false, success, failure);
    }

    void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) {
        if (!host.slotRegistry.isActive(slot) || !WorkspaceHost.boundsHooksReady || host.transferBusy || slot.frozen || slot.controller == null) {
            failure.accept("adoption busy/unavailable");
            return;
        }
        host.transferBusy = true;
        slot.promoting = true;
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.parked = false;
        slot.retiredTaskId = -1;
        host.cellSurfaces.prepareCellSurface(slot);
        final long epoch = host.sceneEpoch;
        if (!host.executeOnShell(() -> {
            boolean claimed = false;
            NativeObservation observation = null;
            try {
                Object core = host.slotRegistry.rootInfo(task);
                if (core == null && absentFromShellAndController(slot, task)) {
                    host.main.post(() -> {
                        host.transferBusy = false;
                        slot.promoting = false;
                        if (slot.taskId == task.id && task.token.equals(slot.taskToken)) host.slotLifecycle.clearSlot(slot);
                        failure.accept("task confirmed gone from Core/Shell/controller");
                    });
                    return;
                }
                if (!host.slotRegistry.ordinaryRoot(core, 1) || (collectFromMain && !XposedHelpers.getBooleanField(core, "isVisible")))
                    throw new IllegalStateException("required fullscreen source no longer available");
                Object controller = slot.controller;
                host.taskStates.resetIdleTaskView(slot);
                if (XposedHelpers.getObjectField(controller, "mTaskToken") != null)
                    throw new IllegalStateException("destination controller is occupied");
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))
                        || WorkspaceNativeHooks.getSurfaceField(controller, "mSurfaceControl") == null)
                    throw new IllegalStateException("destination surface unavailable");
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object lock = XposedHelpers.getObjectField(organizer, "mLock");
                synchronized (lock) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
                    if (appeared == null) throw new IllegalStateException("canonical task not in existing organizer");
                    Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
                    Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
                    Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(-2);
                    Object explicit = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(task.id);
                    if (!task.matches(info) || !host.slotRegistry.ordinaryRoot(info, 1) || explicit != null
                            || fallback == null || XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback
                            || canonical == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid")))
                        throw new IllegalStateException("canonical identity/fullscreen ownership gate rejected");
                    Object wct = TransactionCompat.newWindowTransaction();
                    XposedHelpers.callMethod(wct, "setWindowingMode", task.token, 6);
                    XposedHelpers.callMethod(wct, "setBounds", task.token, host.slotRegistry.logicalBounds(slot));
                    WorkspaceNativeHooks.applyTopConfiguration(host, wct, task.token, slot);
                    XposedHelpers.callMethod(wct, "setForceTranslucent", task.token, true);
                    XposedHelpers.callMethod(wct, "setAlwaysOnTop", task.token, true);
                    XposedHelpers.callMethod(wct, "setFocusable", task.token, true);
                    XposedHelpers.callMethod(wct, "setHidden", task.token, false);
                    if (collectFromMain) XposedHelpers.callMethod(wct, "reorder", host.slotRegistry.homeForCollection(task), true);
                    XposedHelpers.callMethod(wct, "reorder", task.token, true);
                    // Existing listener is retired by the organizer, never globally replaced.
                    claimed = true;
                    ShellCompat.call(organizer, "addListenerForTaskId", controller, task.id);
                    observation = host.observeNativeCommit(task.token,
                            () -> checkAdoption(slot, task, epoch, 0, success, failure));
                    ShellCompat.call(host.transitions, "startRootTask", controller, info, canonical, wct);
                    WorkspaceHost.log("adopt existing task=" + task.id + " into slot=" + slot.index + " logical=" + host.slotRegistry.logicalBounds(slot));
                }
            } catch (Throwable error) {
                host.cancelNativeObservation(observation);
                final boolean changed = claimed;
                host.main.post(() -> adoptionFailed(slot, task, changed, "adopt: " + error, failure));
            }
        })) adoptionFailed(slot, task, false, "no Shell queue", failure);
    }

    private void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,
            Runnable success, Consumer<String> failure) {
        if (!host.slotRegistry.isActive(slot) || epoch != host.sceneEpoch) {
            adoptionFailed(slot, task, true, "scene changed during adoption", failure);
            return;
        }
        if (!host.executeOnShell(() -> {
            boolean ready = false;
            String detail;
            try {
                Object core = host.slotRegistry.rootInfo(task);
                ready = host.slotRegistry.ordinaryRoot(core, 6) && XposedHelpers.getBooleanField(core, "isVisible")
                        && host.slotRegistry.controllerOwns(slot, task.id, task.token)
                        && WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash") != null;
                detail = "core/controller/leash=" + ready;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean confirmed = ready;
            final String result = detail;
            host.main.post(() -> {
                if (epoch != host.sceneEpoch) {
                    adoptionFailed(slot, task, true, "stale adoption completion", failure);
                } else if (confirmed) {
                    host.taskStates.bindTask(slot, task.id, task.token);
                    slot.pkg = task.pkg;
                    slot.occupied = true;
                    slot.taskVisible = true;
                    slot.launching = false;
                    slot.windowMode = 6; // Confirmed from Core and this controller above.
                    slot.promoting = false;
                    slot.parked = false;
                    host.transferBusy = false;
                    host.slotLifecycle.updateHint(slot);
                    host.chrome.updateTouchRegion(slot);
                    host.scales.reapplyScale(slot);
                    host.publishDesktopSideTasks();
                    WorkspaceHost.log("adoption confirmed same task=" + task.id + " slot=" + slot.index);
                    if(slot.index==WorkspaceHost.SLOT_TOP)TopNativeCaption.refresh();
                    success.run();
                    host.main.postDelayed(() -> {
                        host.cells.compactCellsAfterCollection();
                        // TaskView insertion can briefly hide sibling surfaces while the
                        // native transition settles. Re-show every already-owned cell
                        // without moving tasks or changing ownership.
                        host.main.postDelayed(() -> host.retainedSurfaces.reassertSideTaskViews("adoption-settled"), 420L);
                    }, 120L);
                } else if (attempt < 2) {
                    host.main.postDelayed(() -> checkAdoption(slot, task, epoch, attempt + 1, success, failure), 500L);
                } else adoptionFailed(slot, task, true, result, failure);
            });
        })) adoptionFailed(slot, task, true, "no Shell queue for confirmation", failure);
    }

    private void adoptionFailed(Slot slot, LiveTask task, boolean claimed, String error, Consumer<String> failure) {
        host.transferBusy = false;
        slot.launching = false;
        if (!claimed) {
            slot.promoting = slot.frozen;
            if (slot.occupied && slot.taskId == task.id && task.token.equals(slot.taskToken))
                slot.parked = true;
            host.slotLifecycle.updateHint(slot);
            failure.accept(error);
            return;
        }
        host.taskStates.bindTask(slot, task.id, task.token);
        slot.pkg = task.pkg;
        slot.occupied = true;
        returnToSystem(slot, false, () -> {
            host.slotLifecycle.clearSlot(slot);
            failure.accept(error + "; handed back alive");
        }, rollback -> {
            // Do not clear a still-owned controller or let a later selection overwrite it.
            slot.frozen = true;
            slot.promoting = true;
            WorkspaceHost.log("adoption rollback incomplete; frozen live task=" + task.id + ": " + rollback);
            failure.accept(error + "; binding frozen");
        });
    }

    void returnToSystem(Slot slot, boolean front, Runnable success, Consumer<String> failure) {
        returnToSystem(slot,front,false,success,failure);
    }

    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            Runnable success, Consumer<String> failure) {
        returnToSystem(slot, front, liveMainReveal, false, success, failure);
    }

    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            boolean nativeAlreadyFullscreen, Runnable success, Consumer<String> failure) {
        Promotion ticket = new Promotion(slot, new Slot[0]);
        ticket.background = !front;
        ticket.liveMainReveal = liveMainReveal;
        if(liveMainReveal && host.topTransition!=null) {
            host.topTransition.setNativeRestore(()->{
                ticket.liveMainReveal=false;
                host.taskTransfer.restoreTopMainOpacity(ticket);
            });
        }
        ticket.sceneEpoch = host.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        slot.promoting = true;
        host.transferBusy = true;
        host.recycleBuffered(slot);
        if (!host.executeOnShell(() -> {
            NativeObservation observation = null;
            try {
                if (!returnTicketCurrent(ticket)) throw new IllegalStateException("stale return ticket");
                LiveTask identity = new LiveTask(ticket.taskId, ticket.token, slot.pkg);
                Object core = host.slotRegistry.rootInfo(identity);
                if (core == null) {
                    if (!absentFromShellAndController(slot, identity))
                        throw new IllegalStateException("task missing from roots but still alive in Shell/controller");
                    host.main.post(() -> {
                        if (!returnTicketCurrent(ticket)) return;
                        host.transferBusy = false; host.slotLifecycle.clearSlot(slot); success.run();
                    });
                    return;
                }
                Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
                if (nativeAlreadyFullscreen) {
                    if (!host.slotRegistry.ordinaryRoot(core, 1) || (current != null && !ticket.token.equals(current)))
                        throw new IllegalStateException("native fullscreen handback changed identity or mode");
                    observation = host.observeNativeCommit(ticket.token,
                            () -> finishNativeReturnWhenIdle(ticket, success, failure));
                    return;
                }
                if (current == null && host.slotRegistry.ordinaryRoot(core, 1)) {
                    host.main.post(() -> checkReturned(slot, ticket, 0, success, failure));
                    return;
                }
                if (!ticket.token.equals(current))
                    throw new IllegalStateException("return controller identity changed");
                if (!ticket.background && host.retainedOnExit && host.scene == WorkspaceHost.SUSPENDED && host.slotRegistry.ordinaryRoot(core, 6)) {
                    applyRetainedDesktopReturn(ticket, success, failure);
                    return;
                }
                observation = host.observeNativeCommit(ticket.token, () -> checkReturned(slot, ticket, 0, success, failure));
                if (!WorkspaceNativeHooks.applyWct("return-live-task", fullscreenExitTransaction(ticket)))
                    throw new IllegalStateException("return transaction failed");
            } catch (Throwable error) {
                host.cancelNativeObservation(observation);
                host.main.post(() -> {
                    host.transferBusy = false;
                    slot.promoting = slot.frozen;
                    failure.accept(error.toString());
                });
            }
         })) { host.transferBusy = false; slot.promoting = slot.frozen; failure.accept("no Shell queue"); }
    }

    private void finishNativeReturnWhenIdle(Promotion ticket, Runnable success, Consumer<String> failure) {
        Slot slot = ticket.slot;
        Consumer<String> failed = error -> {
            host.transferBusy = false;
            if (returnTicketCurrent(ticket)) slot.promoting = slot.frozen;
            failure.accept(error);
        };
        if (!host.executeOnShell(() -> {
            try {
                Object shell = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                ShellCompat.call(shell, "runOnIdle", (Runnable) () -> {
                    try {
                        if (!returnTicketCurrent(ticket))
                            throw new IllegalStateException("stale native return ticket");
                        Object core = host.slotRegistry.rootInfo(new LiveTask(ticket.taskId, ticket.token, slot.pkg));
                        if (core != null) {
                            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
                            if (!host.slotRegistry.ordinaryRoot(core, 1) || (current != null && !ticket.token.equals(current)))
                                throw new IllegalStateException("native return no longer owns this binding");
                            Object cleanup = TransactionCompat.newWindowTransaction();
                            appendTaskViewExitOverrides(cleanup, ticket);
                            if (host.scene == WorkspaceHost.SUSPENDED && host.retainedOnExit && ticket.token.equals(current)
                                    && !XposedHelpers.getBooleanField(core, "isVisible")) {
                                // The old, hidden TaskView can carry its hidden flag into mode 1.
                                XposedHelpers.callMethod(cleanup, "setHidden", ticket.token, false);
                            }
                            if (!WorkspaceNativeHooks.applyWct("clear-native-launch-overrides", cleanup))
                                throw new IllegalStateException("native return override cleanup failed");
                        }
                        host.main.post(() -> checkReturned(slot, ticket, 0, success, failure));
                    } catch (Throwable error) {
                        host.main.post(() -> failed.accept(error.toString()));
                    }
                });
            } catch (Throwable error) {
                host.main.post(() -> failed.accept(error.toString()));
            }
        })) failed.accept("no Shell queue for native fullscreen handback");
    }

    private void applyRetainedDesktopReturn(Promotion ticket, Runnable success,
            Consumer<String> failure) throws Exception {
        ticket.restoreMainOpacityAfterHandoff = true;
        ShellCompat.applySynchronized(host.transitions, ticket.slot.controller,
                fullscreenExitTransaction(ticket), sync -> {
                    Runnable confirm = () -> checkReturned(ticket.slot, ticket, 0, success, failure);
                    try {
                        XposedHelpers.callMethod(sync, "addTransactionCommittedListener",
                                (Executor) host.main::post,
                                (android.view.SurfaceControl.TransactionCommittedListener) confirm::run);
                    } catch (Throwable unavailable) {
                        WorkspaceHost.log("retained desktop draw commit signal unavailable task=" + ticket.taskId);
                        host.main.post(confirm);
                    }
                });
        WorkspaceHost.log("retained desktop return waiting for window draw task=" + ticket.taskId);
    }

    void restoreRetainedMainOpacityAfterHandoff(Promotion ticket) {
        restoreMainOpacity(ticket, "restore-retained-main-opacity", true);
    }

    boolean returnTicketCurrent(Promotion ticket) {
        Slot slot = ticket.slot;
        return TaskHandoffPolicy.bindingCurrent(host.slotRegistry.isActive(slot),host.sceneEpoch,ticket.sceneEpoch,
                slot.taskEpoch,ticket.bindingEpoch,slot.taskId,ticket.taskId,slot.taskToken,ticket.token);
    }

    private boolean absentFromShellAndController(Slot slot, LiveTask task) {
        Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
            if (appeared != null && task.matches(XposedHelpers.callMethod(appeared, "getTaskInfo"))) return false;
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            Object pending = XposedHelpers.getObjectField(slot.controller, "mPendingInfo");
            return !task.token.equals(current) && (pending == null || !task.matches(pending));
        }
    }

    private void checkReturned(Slot slot,Promotion ticket,int attempt,Runnable success,Consumer<String> failure) {
        new TaskReturnCoordinator(new TaskReturnCoordinator.Host() {
            @Override public boolean current() { return returnTicketCurrent(ticket); }
            @Override public boolean executeOnShell(Runnable action) { return host.executeOnShell(action); }
            @Override public void post(Runnable action) { host.main.post(action); }
            @Override public void later(Runnable action,long delay) { host.main.postDelayed(action,delay); }
            @Override public boolean handoffAttempted() { return ticket.handoffAttempted; }
            @Override public TaskReturnCoordinator.Result inspect() throws Throwable {
                boolean ready=false,gone=false;
                LiveTask identity=new LiveTask(ticket.taskId,ticket.token,slot.pkg);
                Object core=host.slotRegistry.rootInfo(identity);
                if(core==null) { gone=absentFromShellAndController(slot,identity);ready=gone; }
                else if(host.slotRegistry.ordinaryRoot(core,1)) {
                    host.promotionController.handoffPromotedTask(ticket);
                    ready=ticket.handoffComplete && XposedHelpers.getObjectField(slot.controller,"mTaskToken")==null;
                }
                return new TaskReturnCoordinator.Result(ready,gone,"native handback="+ready+" gone="+gone);
            }
            @Override public void complete(boolean removed) {
                host.ownedTasks.remove(ticket.token);
                if(removed)host.slotLifecycle.clearSlot(slot);
                else {
                    slot.promoting=false;slot.frozen=false;slot.parked=true;slot.taskVisible=false;
                    slot.scaleSurface=null;slot.scaleTaskId=Integer.MIN_VALUE;slot.scaleApplied=-1f;host.slotLifecycle.updateHint(slot);
                }
                host.transferBusy=false;host.cellSurfaces.removeCellBackground(slot);
                WorkspaceHost.log("returned task id="+ticket.taskId+" gone="+removed+" front="+!ticket.background);
                success.run();
            }
            @Override public void fail(String detail,boolean freeze) {
                host.transferBusy=false;
                if(freeze) { slot.frozen|=ticket.handoffAttempted;slot.promoting=slot.frozen; }
                failure.accept(detail);
            }
        }).observe(attempt);
    }

    Object fullscreenExitTransaction(Promotion ticket) throws Exception {
        Object wct = TransactionCompat.newWindowTransaction();
        Object token = ticket.token;
        XposedHelpers.callMethod(wct, "setWindowingMode", token, 0);
        XposedHelpers.callMethod(wct, "setBounds", token, new Rect());
        XposedHelpers.callMethod(wct, "setHidden", token, false);
        appendTaskViewExitOverrides(wct, ticket);
        XposedHelpers.callMethod(wct, "reorder", token, !ticket.background);
        // Side roots must remain above the opaque main task in WM's visibility traversal.
        // Their surfaces stay inside their TaskViews and do not cover the main area.
        for (Object sideToken : ticket.otherSideTokens) {
            XposedHelpers.callMethod(wct, "setForceTranslucent", sideToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", sideToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", sideToken, true);
            XposedHelpers.callMethod(wct, "reorder", sideToken, true);
        }
        return wct;
    }

    private void appendTaskViewExitOverrides(Object wct, Promotion ticket) throws Exception {
        Object token = ticket.token;
        ShellCompat.transaction(XposedHelpers.getObjectField(ticket.slot.controller, "mTaskOrganizer"),
                wct, "setInterceptBackPressedOnTaskRoot", token, false);
        ShellCompat.transaction(XposedHelpers.getObjectField(ticket.slot.controller, "mTaskOrganizer"),
                wct, "setTaskForceExcludedFromRecents", token, false);
        ShellCompat.transaction(XposedHelpers.getObjectField(ticket.slot.controller, "mTaskOrganizer"),
                wct, "setDisablePip", token, false);
        ShellCompat.transaction(XposedHelpers.getObjectField(ticket.slot.controller, "mTaskOrganizer"),
                wct, "setDisableLaunchAdjacent", token, false);
        XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, false);
        XposedHelpers.callMethod(wct, "setFocusable", token, true);
        if (ticket.slot.index != WorkspaceHost.SLOT_MAIN) {
            XposedHelpers.callMethod(wct, "setDensityDpi", token, 0);
        }
        if (ticket.slot.index == WorkspaceHost.SLOT_TOP) {
            XposedHelpers.callMethod(wct, "setAppBounds", token, (Object) null);
            XposedHelpers.callMethod(wct, "setScreenSizeDp", token, 0, 0);
            XposedHelpers.callMethod(wct, "setSmallestScreenWidthDp", token, 0);
        }
        // Keep the actual desktop/app below visible while the returning live surface is small.
        // Surface scaling alone does not prevent WM from occluding that underlying task.
        XposedHelpers.callMethod(wct, "setForceTranslucent", token,
                ticket.liveMainReveal || ticket.restoreMainOpacityAfterHandoff);
        ShellCompat.transaction(XposedHelpers.getObjectField(ticket.slot.controller, "mTaskOrganizer"),
                wct, "setTaskTrimmableFromRecents", token, true);
        Object owner = XposedHelpers.getObjectField(ticket.slot.controller, "mCaptionInsetsOwner");
        if (owner != null) {
            XposedHelpers.callMethod(wct, "removeInsetsSource", token, owner, 0,
                    android.view.WindowInsets.Type.captionBar());
        }
    }

    void restoreTopMainOpacity(Promotion ticket) {
        restoreMainOpacity(ticket, "upper-live-main-opacity-restore", false);
    }

    private void restoreMainOpacity(Promotion ticket, String operation, boolean retainedReturn) {
        if(!host.executeOnShell(()->{
            try {
                if (retainedReturn && (!ticket.restoreMainOpacityAfterHandoff || !ticket.handoffComplete)) return;
                Object info=host.slotRegistry.rootInfo(new LiveTask(ticket.taskId,ticket.token,ticket.slot.pkg));
                if (!host.slotRegistry.ordinaryRoot(info, 1)
                        || (retainedReturn && host.slotRegistry.isSideToken(ticket.token) && !returnTicketCurrent(ticket))) return;
                Object wct=TransactionCompat.newWindowTransaction();
                XposedHelpers.callMethod(wct,"setForceTranslucent",ticket.token,false);
                if (WorkspaceNativeHooks.applyWct(operation, wct)) ticket.restoreMainOpacityAfterHandoff = false;
                else WorkspaceHost.log(operation + " not committed task=" + ticket.taskId);
            } catch(Throwable error) {WorkspaceHost.log(operation + ": " + error);}
        }))WorkspaceHost.log(operation + " unavailable: no Shell queue");
    }
}
