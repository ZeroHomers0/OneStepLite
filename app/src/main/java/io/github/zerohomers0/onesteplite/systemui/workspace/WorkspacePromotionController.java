package io.github.zerohomers0.onesteplite;

import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;

/** Coordinates promotion, focus handoff, and external notification launches. */
final class WorkspacePromotionController {
    private final WorkspaceHost host;

    WorkspacePromotionController(WorkspaceHost host) {
        this.host = host;
    }

    void resumeNotificationLaunch(Promotion ticket) {
        Runnable dispatch = ticket.notificationLaunch;
        ticket.notificationLaunch = null;
        if (dispatch != null) {
            WorkspaceHost.log("notification resumes original dispatch task=" + ticket.taskId);
            host.main.post(dispatch);
        }
    }

    void queueSidePromotion(Slot slot, boolean desktopLaunch) {
        if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN || !host.slotRegistry.isActive(slot)
                || !slot.occupied || slot.taskId < 0 || slot.taskToken == null
                || slot.parked || slot.frozen || slot.launching) return;
        PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.taskId == slot.taskId
                && pending.token.equals(slot.taskToken)
                && pending.bindingEpoch == slot.taskEpoch
                && pending.sceneEpoch == host.sceneEpoch) {
            pending.desktopLaunch |= desktopLaunch;
            return;
        }
        pending = new PendingSidePromotion(slot, host.sceneEpoch, desktopLaunch,
                SystemClock.uptimeMillis() + WorkspaceHost.SIDE_PROMOTION_TIMEOUT_MS);
        slot.pendingSidePromotion = pending;
        retrySidePromotion(pending);
    }

    void retrySidePromotion(PendingSidePromotion request) {
        Slot slot = request.slot;
        if (slot.pendingSidePromotion != request) return;
        boolean sameTask = host.scene == WorkspaceHost.ACTIVE && host.slotRegistry.isActive(slot) && slot.occupied
                && !slot.parked && !slot.frozen && !slot.launching
                && SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, host.sceneEpoch, slot.taskEpoch, slot.pkg);
        if (!sameTask) {
            slot.pendingSidePromotion = null;
            WorkspaceHost.log("pending side promotion cancelled: task binding changed slot=" + slot.index);
            return;
        }
        if (host.promotion != null && host.promotion.slot == slot
                && host.promotion.taskId == request.taskId && request.token.equals(host.promotion.token)) {
            slot.pendingSidePromotion = null;
            return;
        }
        if (slot.modeAdmissionPending && !request.desktopLaunch) {
            slot.pendingSidePromotion = null;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = SidePromotionPolicy.retryDelay(now, request.deadline,
                slot.modeGuardUntil, host.promotion != null || host.transferBusy || slot.promoting
                        || slot.windowMode != 1);
        if (wait < 0L || request.attempts >= 40) {
            slot.pendingSidePromotion = null;
            WorkspaceHost.log("pending side promotion expired slot=" + slot.index + " task=" + request.taskId
                    + " mode=" + slot.windowMode + " attempts=" + request.attempts);
            if (slot.windowMode == 6) host.scales.reapplyScale(slot);
            return;
        }
        if (wait == 0L) {
            slot.pendingSidePromotion = null;
            WorkspaceHost.log("pending side promotion execute slot=" + slot.index + " task=" + request.taskId
                    + " desktop=" + request.desktopLaunch + " mode=" + slot.windowMode);
            // The system has already made this task fullscreen. Submitting another
            // fullscreen TaskView pending here repeats prepareOpen/updateBounds and
            // suppresses the original Launcher transition. Observe that transition.
            beginExternalPromotion(request);
            return;
        }
        request.attempts++;
        host.main.postDelayed(() -> retrySidePromotion(request), wait);
    }

    void beginExternalPromotion(PendingSidePromotion request) {
        Slot slot = request.slot;
        if (host.promotion != null || host.transferBusy || !host.slotRegistry.isActive(slot)
                || !SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, host.sceneEpoch, slot.taskEpoch, slot.pkg)
                || slot.windowMode != 1) return;
        Promotion ticket = new Promotion(slot, host.slots);
        ticket.sceneEpoch = host.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.externalLaunch = true;
        ticket.previousMainOnRight = host.mainOnRight;
        ticket.targetMainOnRight = host.mainOnRight;
        host.promotion = ticket;
        slot.promoting = true;
        host.recycleBuffered(slot);
        host.statusBar.hideFor(ticket, () -> {
            if (host.promotion == ticket && !ticket.cancelled)
                failPromotion(ticket, "external promotion status-bar lease expired");
        });
        if (!host.executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                ticket.homeBeforeCover = host.slotRegistry.existingHomeTask();
                host.debug.traceSwap(ticket);
                host.observeNativeCommit(ticket.token, () -> afterExternalTransitionIdle(ticket));
                WorkspaceHost.log("observe external fullscreen task=" + ticket.taskId
                        + "; retain original launch, no second fullscreen pending");
            } catch (Throwable error) {
                host.main.post(() -> failPromotion(ticket, "external prepare: " + error));
            }
        })) failPromotion(ticket, "external promotion Shell executor unavailable");
    }

    boolean externalPromotionCurrent(Promotion ticket) {
        return host.promotion == ticket && !ticket.cancelled && !ticket.cancelRequested && host.scene == WorkspaceHost.ACTIVE
                && host.screenOn && !host.keyguardLocked
                && TaskHandoffPolicy.bindingCurrent(host.slotRegistry.isActive(ticket.slot),host.sceneEpoch,ticket.sceneEpoch,
                        ticket.slot.taskEpoch,ticket.bindingEpoch,ticket.slot.taskId,ticket.taskId,
                        ticket.slot.taskToken,ticket.token);
    }

    void afterExternalTransitionIdle(Promotion ticket) {
        if (!externalPromotionCurrent(ticket)) return;
        if (!host.executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                Object shellTransitions = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                // The exact target ROM queues this until pending transitions and all
                // tracks are idle. Its finish observer runs before finishT.apply.
                ShellCompat.call(shellTransitions, "runOnIdle", (Runnable) () ->
                        host.main.post(() -> {
                            if (externalPromotionCurrent(ticket)) checkPromotion(ticket, 0);
                        }));
            } catch (Throwable error) {
                host.main.post(() -> failPromotion(ticket, "external transition idle unavailable: " + error));
            }
        })) failPromotion(ticket, "external transition Shell queue unavailable");
    }

    FocusResult requestLiveMainFocus(Promotion ticket) throws Exception {
        final LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) return FocusResult.UNAVAILABLE;
        final int id = home == null ? ticket.taskId : home.id;
        final Object token = home == null ? ticket.token : home.token;
        final int user = ticket.liveMainUserId;
        if (user < 0) return FocusResult.UNAVAILABLE;
        Object service = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityTaskManager"), "getService");
        java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
        if (home != null) for (Object info : roots) {
            if (XposedHelpers.getIntField(info, "displayId") != 0
                    || !XposedHelpers.getBooleanField(info, "isFocused")) continue;
            Object focused = XposedHelpers.getObjectField(info, "token");
            // A new main app or native freeform has priority over returning Home.
            // The collected task and other exact side tasks never own main input.
            if (!token.equals(focused) && !ticket.token.equals(focused)
                    && !ticket.otherSideTokens.contains(focused))
                return FocusResult.UNAVAILABLE;
        }
        return PromotionFocusTransfer.request(new PromotionFocusTransfer.Tasks() {
            public PromotionFocusTransfer.State read(int taskId) {
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != taskId
                            || (home == null ? !host.slotRegistry.ordinaryRoot(info, 1)
                                    : !host.slotRegistry.isHomeRoot(info) || host.taskStates.windowModeOf(info) != 1)) continue;
                    return new PromotionFocusTransfer.State(XposedHelpers.getObjectField(info, "token"),
                            XposedHelpers.getIntField(info, "displayId"),
                            XposedHelpers.getIntField(info, "userId"), host.taskStates.windowModeOf(info),
                            host.slotRegistry.mainTaskVisible(info),
                            XposedHelpers.getBooleanField(info, "isFocused"));
                }
                return null;
            }
            public void focus(int taskId) { XposedHelpers.callMethod(service, "setFocusedTask", taskId); }
        }, id, token, user);
    }

    void handoffPromotedTask(Promotion ticket) throws Throwable {
        if (ticket.handoffAttempted || ticket.cancelled) return;
        Object controller = ticket.slot.controller;
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        Object lock = XposedHelpers.getObjectField(organizer, "mLock");
        synchronized (lock) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTasks")).get(ticket.taskId);
            if (appeared == null) return;
            Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
            Object token = XposedHelpers.getObjectField(info, "token");
            Object current = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (!ticket.token.equals(token)
                    || XposedHelpers.getIntField(info, "taskId") != ticket.taskId
                    || XposedHelpers.getIntField(info, "displayId") != 0
                    || !host.slotRegistry.supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                    || (!ticket.background && !XposedHelpers.getBooleanField(info, "isVisible"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))) return;
            Object assigned = XposedHelpers.callMethod(organizer, "getTaskListener", info, false);
            Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTaskListeners")).get(-2); // target fullscreen listener type
            HandoffOwner owner = TaskHandoffPolicy.owner(
                    ticket.token, current, assigned, controller, fallback);
            if (ticket.candidateRecovery && assigned == fallback && fallback != null
                    && fallback != controller && !ticket.token.equals(current))
                owner = HandoffOwner.NATIVE_FULLSCREEN;
            if (owner == HandoffOwner.UNAVAILABLE) {
                throw new IllegalStateException("native fullscreen route is unavailable");
            }
            Object area = ShellCompat.rootArea(organizer);
            Object areaInfo = ShellCompat.call(area, "getDisplayAreaInfo", 0);
            Object parent = ShellCompat.call(area, "getDisplayAreaLeash", 0);
            Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
            Object copy = WorkspaceNativeHooks.getSurfaceField(controller, "mTaskLeash");
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0
                    || parent == null || canonical == null
                    || (owner == HandoffOwner.OUR_CONTROLLER
                            && (copy == null || canonical == copy))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isValid"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid"))) {
                throw new IllegalStateException("native parent/canonical surface is unavailable");
            }
            host.commitPromotedHandoff(ticket, organizer, host.transitions, controller, fallback, info,
                    owner, area, canonical);
        }
    }

    void checkPromotion(Promotion ticket, int attempt) {
        if (host.promotion != ticket || ticket.cancelled || !host.slotRegistry.isActive(ticket.slot)) return;
        if (!host.executeOnShell(() -> {
            boolean success = false;
            String detail;
            try {
                Object current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                Object service = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityTaskManager"), "getService");
                java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                        service, "getAllRootTaskInfos");
                detail = "detached=" + (current == null);
                int visibleSides = 0;
                for (Object info : roots) {
                    Object rootToken = XposedHelpers.getObjectField(info, "token");
                    if (ticket.otherSideTokens.contains(rootToken)
                            && XposedHelpers.getBooleanField(info, "isVisible")) visibleSides++;
                }
                detail += " otherSidesVisible=" + visibleSides + "/" + ticket.otherSideTokens.size();
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != ticket.taskId) continue;
                    Object config = XposedHelpers.getObjectField(info, "configuration");
                    Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
                    int mode = (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
                    boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                    boolean ready = ticket.token.equals(XposedHelpers.getObjectField(info, "token"))
                            && mode == 1 && visible
                            && visibleSides == ticket.otherSideTokens.size();
                    if (ready && current != null && !ticket.handoffAttempted) {
                        handoffPromotedTask(ticket);
                        current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                    }
                    success = current == null && mode == 1 && visible
                            && ready && (!ticket.handoffAttempted || ticket.handoffComplete);
                    detail += " mode=" + mode + " visible=" + visible
                            + " detachedNow=" + (current == null)
                            + " nativeHandoff=" + ticket.handoffComplete;
                    break;
                }
            } catch (Throwable t) {
                detail = "observe: " + t;
            }
            final boolean done = success;
            final String result = detail;
            host.main.post(() -> {
                if (host.promotion != ticket || ticket.cancelled || !host.slotRegistry.isActive(ticket.slot)) return;
                WorkspaceHost.log("promote observation task=" + ticket.taskId + " attempt=" + attempt + " " + result);
                if (done) {
                    host.debug.traceSwapEvent("promotion-mode1-confirmed");
                                host.ownedTasks.remove(ticket.token);
                    host.slotLifecycle.clearSlot(ticket.slot);
                    if (ticket.previousMain != null) {
                        WorkspaceLiveHandoffController.releaseCompactSnapshot(ticket);
                        host.adoptExisting(ticket.slot, ticket.previousMain, () -> finishPromotionFocus(ticket, () -> {
                            host.debug.traceSwapEvent("backfill-mode6-and-focus-confirmed");
                            ticket.cancelled = true;
                            host.promotion = null;
                            resumeNotificationLaunch(ticket);
                            // Keep the final captured frame opaque across the task-surface
                            // handoff; closing immediately exposed one black host frame.
                            host.coveredHome = null;
                            host.liveHandoff.finishSwapPreview(ticket, 120L);
                            WorkspaceHost.log("swap confirmed main=" + ticket.taskId + " side="
                                    + ticket.previousMain.id + " sameSlot=" + ticket.slot.index);
                            // 提升后的新主任务之前在副格里被设过 crop，必须清掉；否则主区顶部会残一条应用画面。
                            host.carriers.resetMainTaskLeash(ticket.taskId);
                            host.carriers.scheduleMainCropClear(400L);
                            host.resumes.finishPendingSuspend();
                        }, 0), error -> finishPromotionFocus(ticket, () -> {
                            ticket.cancelled = true;
                            host.promotion = null;
                            resumeNotificationLaunch(ticket);
                            host.liveHandoff.closeSwapPreview(ticket);
                            WorkspaceHost.log("swap backfill failed; both system tasks retained, no relaunch: " + error);
                            host.resumes.finishPendingSuspend();
                        }, 0));
                    } else {
                        finishPromotionFocus(ticket, () -> completeHomePromotion(ticket), 0);
                    }
                } else if (attempt < 2) {
                    host.main.postDelayed(() -> checkPromotion(ticket, attempt + 1), 700L);
                } else {
                    failPromotion(ticket, result);
                }
            });
        })) failPromotion(ticket, "Shell executor unavailable during observation");
    }

    void completeHomePromotion(Promotion ticket) {
        if (host.promotion != ticket || ticket.cancelled) return;
        host.debug.traceSwapEvent("Home-cover-and-focus-confirmed");
        ticket.cancelled = true;
        host.promotion = null;
        if(ticket.keepTopBarAppearance)host.chrome.updateTopBarState();
        resumeNotificationLaunch(ticket);
        host.coveredHome = new HomeTaskReturn(ticket.taskId, ticket.token,
                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token);
        WorkspaceLiveHandoffController.releaseCompactSnapshot(ticket);
        host.liveHandoff.finishSwapPreview(ticket, 0L);
        // Home and main geometry remain anchored. Only side owner positions change;
        // their controllers, task tokens and window/input state travel together.
        host.cells.compactCells();
        if (host.root != null) host.root.requestLayout();
        WorkspaceHost.log("home promotion confirmed task=" + ticket.taskId + "; side gap compacted");
        host.resumes.finishPendingSuspend();
    }

    void finishPromotionFocus(Promotion ticket, Runnable complete, int attempt) {
        if (host.promotion != ticket || ticket.cancelled) return;
        if (ticket.liveMainFocusPrepared) {
            // The live Shell callback already requested the exact main task.
            // Restore Host now; final-frame ownership still protects the handoff.
            complete.run();
            return;
        }
        final long epoch = host.sceneEpoch;
        final LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) { complete.run(); return; }
        final int focusId = home == null ? ticket.taskId : home.id;
        final Object focusToken = home == null ? ticket.token : home.token;
        final int focusUser = ticket.liveMainUserId >= 0 ? ticket.liveMainUserId : host.slotRegistry.currentUserId();
        if (host.scene != WorkspaceHost.ACTIVE || host.suspendRequested || !host.screenOn) {
            complete.run();
            return;
        }
        if (!host.executeOnShell(() -> {
            FocusResult result = FocusResult.UNAVAILABLE;
            try {
                if (host.promotion == ticket && !ticket.cancelled && host.sceneEpoch == epoch
                        && host.scene == WorkspaceHost.ACTIVE && !host.suspendRequested && host.screenOn) {
                    Object service = XposedHelpers.callStaticMethod(
                            Class.forName("android.app.ActivityTaskManager"), "getService");
                    PromotionFocusTransfer.Tasks tasks = new PromotionFocusTransfer.Tasks() {
                        public PromotionFocusTransfer.State read(int id) {
                            java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                                    service, "getAllRootTaskInfos");
                            for (Object info : roots) {
                                if (XposedHelpers.getIntField(info, "taskId") != id
                                        || (home == null ? !host.slotRegistry.ordinaryRoot(info, 1)
                                                : !host.slotRegistry.isHomeRoot(info) || host.taskStates.windowModeOf(info) != 1)) continue;
                                return new PromotionFocusTransfer.State(
                                        XposedHelpers.getObjectField(info, "token"),
                                        XposedHelpers.getIntField(info, "displayId"),
                                        XposedHelpers.getIntField(info, "userId"), host.taskStates.windowModeOf(info),
                                        host.slotRegistry.mainTaskVisible(info),
                                        XposedHelpers.getBooleanField(info, "isFocused"));
                            }
                            return null;
                        }
                        public void focus(int id) {
                            XposedHelpers.callMethod(service, "setFocusedTask", id);
                        }
                    };
                    PromotionFocusTransfer.State initial = tasks.read(focusId);
                    if (initial != null) {
                        // Collection already revealed Home in its WCT. Never refocus it here:
                        // a new main app or native freeform may have taken focus during finish.
                        result = attempt == 0 && home == null
                                ? PromotionFocusTransfer.transfer(tasks, focusId,
                                        focusToken, focusUser)
                                : PromotionFocusTransfer.observe(tasks, focusId,
                                        focusToken, focusUser);
                    }
                }
            } catch (Throwable error) {
                WorkspaceHost.log("promotion focus request failed task=" + focusId + ": " + error);
            }
            final FocusResult observed = result;
            host.main.post(() -> {
                if (host.promotion != ticket || ticket.cancelled) return;
                WorkspaceHost.log("promotion focus task=" + focusId + " attempt=" + attempt
                        + " result=" + observed + " afterBackfill=true");
                if (observed == FocusResult.PENDING && attempt == 0
                        && host.sceneEpoch == epoch && host.scene == WorkspaceHost.ACTIVE && !host.suspendRequested && host.screenOn) {
                    host.main.postDelayed(() -> finishPromotionFocus(ticket, complete, 1), 120L);
                } else complete.run();
            });
        })) {
            WorkspaceHost.log("promotion focus unavailable: no Shell queue task=" + focusId);
            complete.run();
        }
    }

    void failPromotion(Promotion ticket, String reason) {
        host.debug.traceSwapEvent("promotion-failure");
        if (host.promotion != ticket || ticket.cancelled) return;
        if (ticket.nativeSwap) {
            host.liveHandoff.requestLiveSwapCancellation(ticket, reason);
            return;
        }
        ticket.cancelled = true;
        host.promotion = null;
        WorkspaceLiveHandoffController.releaseCompactSnapshot(ticket);
        if (ticket.liveBackdrop != null) { ticket.liveBackdrop.close(); ticket.liveBackdrop = null; }
        // 桥没有把主任务交给系统就失败时，必须把 view 级动画的变换还原，避免主窗留在格子里。
        if (ticket.mainLeash != null && !ticket.handoffAttempted) {
            try {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(ticket.mainLeash, "isValid"))) {
                    host.carriers.applyLeashTransform(ticket.mainLeash, 0f, 0f, 1f, 1f);
                    WorkspaceHost.log("live main shrink restored after failure task=" + ticket.taskId);
                }
            } catch (Throwable error) {
                WorkspaceHost.log("live main shrink restore failed: " + error);
            }
        }
        ticket.mainLeash = null;
        if (!ticket.handoffAttempted && ticket.targetMainOnRight != ticket.previousMainOnRight
                && host.mainOnRight != ticket.previousMainOnRight) {
            host.mainSide.setMainSide(ticket.previousMainOnRight);
        }
        if(ticket.keepTopBarAppearance)host.chrome.updateTopBarState();
        host.liveHandoff.closeSwapPreview(ticket);
        Slot slot = ticket.slot;
        resumeNotificationLaunch(ticket);
        WorkspaceHost.log("promote unconfirmed task=" + ticket.taskId + "; retain all slots: " + reason);
        if (ticket.externalLaunch) {
            // The native Launcher launch must never be undone by a mode6 rollback.
            // Retain the exact binding for the ordinary exit recovery path.
            slot.frozen = true;
            slot.promoting = true;
            WorkspaceHost.log("external fullscreen handoff unconfirmed; retain binding for exit, no mode6 rollback");
            host.main.post(host.resumes::finishPendingSuspend);
            return;
        }
        if (ticket.handoffAttempted) {
            slot.frozen = true;
            WorkspaceHost.log("promote native handoff was attempted; freeze until exit, without reclaiming task");
            host.main.post(host.resumes::finishPendingSuspend);
            return;
        }
        // Only restore a task still owned by this controller. Never remove/relaunch other cells.
        try {
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (ticket.token.equals(current) && host.slotRegistry.isActive(slot)) {
                Object rollback = Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(rollback, "setWindowingMode", ticket.token, 6);
                XposedHelpers.callMethod(rollback, "setBounds", ticket.token, host.slotRegistry.logicalBounds(slot));
                WorkspaceNativeHooks.applyTopConfiguration(host, rollback, ticket.token, slot);
                XposedHelpers.callMethod(rollback, "setForceTranslucent", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setAlwaysOnTop", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setFocusable", ticket.token, true);
                WorkspaceNativeHooks.applyWct("swap-rollback-same-task", rollback);
                slot.promoting = false;
                slot.scaleSurface = null;
                slot.scaleTaskId = Integer.MIN_VALUE;
                slot.scaleApplied = -1f;
                host.scales.applyFullscreenBounds(slot);
                host.scales.applySlotScale(slot);
            } else {
                WorkspaceHost.log("promote task already detached; freeze cell until exit, without relaunching task");
            }
        } catch (Throwable t) {
            WorkspaceHost.log("promote restore side failed: " + t);
        }
        host.main.post(host.resumes::finishPendingSuspend);
    }
}
