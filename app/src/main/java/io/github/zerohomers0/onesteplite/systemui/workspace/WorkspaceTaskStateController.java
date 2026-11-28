package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.os.SystemClock;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import de.robv.android.xposed.XposedHelpers;

/** Owns TaskView listener dispatch, task binding and TaskWindowState synchronization. */
final class WorkspaceTaskStateController {
    private static final int SLOT_MAIN = WorkspaceHost.SLOT_MAIN;
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;
    private static final int SLOT_COUNT = WorkspaceHost.SLOT_COUNT;

    private final WorkspaceHost host;
    private final Object taskWindowStatesLock = new Object();
    private static final int MAX_TASK_STATES = 96;
    private final Map<Integer, TaskWindowState> taskWindowStates =
            new LinkedHashMap<Integer, TaskWindowState>(16, 0.75f, true);
    private static boolean legacyTaskInfoCallbacks;

    WorkspaceTaskStateController(WorkspaceHost host) {
        this.host = host;
    }

    static void setLegacyTaskInfoCallbacks(boolean value) { legacyTaskInfoCallbacks = value; }
    static boolean legacyTaskInfoCallbacks() { return legacyTaskInfoCallbacks; }

    private void trimTaskWindowStatesLocked() {
        if (taskWindowStates.size() <= MAX_TASK_STATES) return;
        Iterator<Map.Entry<Integer, TaskWindowState>> iterator = taskWindowStates.entrySet().iterator();
        while (taskWindowStates.size() > MAX_TASK_STATES && iterator.hasNext()) {
            Map.Entry<Integer, TaskWindowState> entry = iterator.next();
            boolean active = false;
            for (Slot slot : host.slots) {
                if (slot != null && slot.taskId == entry.getKey()) { active = true; break; }
            }
            if (!active) iterator.remove();
        }
        WorkspaceRuntimeCounters.TASK_STATES.set(taskWindowStates.size());
    }

    void clearWindowState(Slot slot) {
        if (slot == null || slot.windowState == null) return;
        slot.windowState.clear();
        synchronized (taskWindowStatesLock) {
            taskWindowStates.remove(slot.windowState.taskId);
        }
        slot.windowState = null;
        WorkspaceRuntimeCounters.TASK_STATES.set(taskWindowStates.size());
    }

    Object makeListener(final int slotIndex) {
        final Slot owner = host.slots[slotIndex];
        try {
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.wm.shell.taskview.TaskView$Listener", host.ctx.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if ("onTaskCreated".equals(name) && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    host.postForSlot(owner, s -> onTaskIdKnown(s.index, taskId));
                                } else if ("onTaskInfoChanged".equals(name)
                                        && args != null && args.length >= 1) {
                                    final Object info = args[0];
                                    host.postForSlot(owner, s -> onTaskInfo(s.index, info));
                                } else if ("onTaskVisibilityChanged".equals(name)
                                        && args != null && args.length >= 2) {
                                    final int taskId = (Integer) args[0];
                                    final boolean visible = (Boolean) args[1];
                                    host.postForSlot(owner, s -> onTaskVisibility(s.index, taskId, visible));
                                } else if ("onTaskRemovalStarted".equals(name)
                                        && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    host.postForSlot(owner, s -> host.sideCloses.onTaskRemovalStarted(s.index, taskId));
                                } else if ("onInitialized".equals(name)) {
                                    WorkspaceHost.log("listener#onInitialized slot=" + owner.index);
                                    host.postForSlot(owner, s -> { host.slotLifecycle.updateHint(s); host.windowPresentation.setWorkspaceAlpha(host.workspaceAlpha()); });
                                } else {
                                    WorkspaceHost.log("listener#" + name + " slot=" + slotIndex);
                                }
                            } catch (Throwable t) {
                                WorkspaceHost.log("listener dispatch failed: " + t);
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            WorkspaceHost.log("makeListener failed: " + t);
            return null;
        }
    }

    private void onTaskIdKnown(int slotIndex, int taskId) {
        Slot slot = host.slots[slotIndex];
        if (slot.promoting || slot.parked || slot.retiredTaskId == taskId || !host.slotRegistry.controllerOwns(slot, taskId, null)) return;
        Object token = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
        if (token == null || !host.slotRegistry.controllerOwns(slot, taskId, token)) return;
        bindTask(slot, taskId, token);
        slot.occupied = true;
        slot.launching = false;
        host.launches.onTaskCreated(slot);
        WorkspaceHost.log("onTaskCreated slot=" + slotIndex + " taskId=" + taskId);
        if (legacyTaskInfoCallbacks) {
            Object info = ShellCompat.currentTaskInfo(slot.controller, taskId);
            if (info != null) onTaskInfo(slotIndex, info);
        }
        if(slotIndex==SLOT_TOP)TopNativeCaption.refresh();
        host.slotLifecycle.updateHint(slot);
        host.chrome.updateTouchRegion(slot);
        host.resumes.finishPendingSuspend();
        host.main.postDelayed(host.cells::compactCellsAfterCollection, 120L);
        if (slotIndex > SLOT_MAIN) scheduleSideModeResolution(slot, taskId);
    }

    void onTaskInfo(int slotIndex, Object info) {
        Slot slot = host.slots[slotIndex];
        try {
            int taskId = XposedHelpers.getIntField(info, "taskId");
            if (slot.promoting || slot.parked || slot.retiredTaskId == taskId) return;
            Object token = XposedHelpers.getObjectField(info, "token");
            if (slot.sideRow >= 0 && taskId == slot.taskId && token != null
                    && token.equals(slot.taskToken) && windowModeOf(info) == 1) {
                // A fullscreen callback can still be queued when the collect transaction
                // has already given the same task back to this TaskView.
                Object current = currentOrganizedTaskInfo(slot, taskId, token);
                if (windowModeOf(current) == 6) info = current;
            }
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean running = XposedHelpers.getBooleanField(info, "isRunning");
            int mode = windowModeOf(info);
            if (host.retainedOnExit && (host.scene == WorkspaceHost.SUSPENDING || host.scene == WorkspaceHost.SUSPENDED) && running && mode == 1
                    && slot.occupied && taskId == slot.taskId && token != null
                    && token.equals(slot.taskToken)) {
                if (host.scene == WorkspaceHost.SUSPENDED) host.exits.releaseRetainedTaskToMain(slot, true);
                return;
            }
            if(slotIndex==SLOT_TOP && mode==1 && slot.occupied && taskId==slot.taskId
                    && token!=null && token.equals(slot.taskToken)) {
                if (running && visible) host.exits.finishExternalTopLaunch(slot);
                return;
            }
            if (slotIndex > SLOT_MAIN && mode == 5 && slot.occupied
                    && taskId == slot.taskId && token != null && token.equals(slot.taskToken)) {
                releaseNativeFreeformSide(slot, taskId, token);
                return;
            }
            if (token == null || !host.slotRegistry.controllerOwns(slot, taskId, token)) return;
            boolean surfaceGeometryChanged = slot.taskId != taskId || slot.windowMode != mode;
            boolean changed = slot.taskId != taskId || slot.occupied != running
                    || slot.taskVisible != visible || slot.windowMode != mode;
            bindTask(slot, taskId, token);
            ComponentName actual = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (actual != null) slot.pkg = actual.getPackageName();
            slot.occupied = running;
            slot.taskVisible = visible;
            slot.windowMode = mode;
            if(slotIndex==SLOT_TOP && running && mode==6)TopNativeCaption.refresh();
            if (!running && changed) host.cells.requestCompaction();
            if (slotIndex > SLOT_MAIN && mode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
            }
            updateTaskWindowState(slotIndex, info, taskId, running);
            if (changed) {
                WorkspaceHost.log("taskInfo slot=" + slotIndex + " task=" + taskId
                        + " running=" + running + " visible=" + visible
                        + " mode=" + mode
                        + " appBounds=" + host.cells.appBoundsOf(info));
                host.slotLifecycle.updateHint(slot);
                host.chrome.updateTouchRegion(slot);
            }
            if (running) {
                // Wait for the native leash first. Early bounds writes can interrupt prepareOpen.
                host.scales.reapplyScale(slot);
            }
            if (running && slotIndex > SLOT_MAIN && mode == 6 && surfaceGeometryChanged) {
                host.scheduleSideSurfaceRestore(slot, taskId, token, slot.taskEpoch, host.sceneEpoch);
            }
            if (running && !visible) host.retainedSurfaces.scheduleSideVisibilityRepair(slot);
            if (running && mode == 1 && slotIndex > SLOT_MAIN
                    && !slot.promoting && !slot.launching && !slot.parked) {
                if (slot.modeAdmissionPending) {
                    if (SystemClock.uptimeMillis() >= slot.modeGuardUntil)
                        repairSideWindowMode(slot, taskId);
                    else scheduleSideModeResolution(slot, taskId);
                } else promoteExternalLaunch(slot);
            } else if (slot.pendingSidePromotion != null
                    && !slot.pendingSidePromotion.desktopLaunch && mode != 1) {
                slot.pendingSidePromotion = null;
            }
            if (changed) host.publishDesktopSideTasks();
        } catch (Throwable t) {
            WorkspaceHost.log("onTaskInfo slot=" + slotIndex + " failed: " + t);
        }
    }

    Object currentOrganizedTaskInfo(Slot slot, int taskId, Object token) {
        try {
            Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
                Object appeared = XposedHelpers.callMethod(tasks, "get", taskId);
                Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                if (info != null && XposedHelpers.getIntField(info, "taskId") == taskId
                        && token.equals(XposedHelpers.getObjectField(info, "token"))) return info;
            }
        } catch (Throwable unavailable) { /* Ignore stale or unsupported organizer state. */ }
        return null;
    }

    private void releaseNativeFreeformSide(Slot slot, int taskId, Object token) {
        if (slot.nativeReleasePending) return;
        final long binding = slot.taskEpoch;
        final Object controller = slot.controller;
        slot.nativeReleasePending = true;
        slot.frozen = true;
        slot.windowMode = 5;
        slot.pendingSidePromotion = null;
        host.recycleBuffered(slot);
        if (!host.executeOnShell(() -> {
            boolean released = false;
            try {
                if (slot.taskEpoch == binding && slot.taskId == taskId && token.equals(slot.taskToken))
                    released = NativeSideRelease.release(controller, host.transitions, taskId, token);
            } catch (Throwable error) { WorkspaceHost.log("native side release task=" + taskId + " failed: " + error); }
            final boolean done = released;
            host.postForSlot(slot, owner -> {
                if (owner.taskEpoch != binding || owner.taskId != taskId
                        || !token.equals(owner.taskToken) || owner.controller != controller) return;
                owner.nativeReleasePending = false;
                owner.frozen = false;
                if (done) {
                    host.ownedTasks.remove(token);
                    host.slotLifecycle.clearSlot(owner);
                    host.cells.compactCells();
                    WorkspaceHost.log("native freeform released side task=" + taskId + "; slot cleared, task retained");
                }
            });
        })) {
            slot.nativeReleasePending = false;
            slot.frozen = false;
        }
    }

    void onTaskVisibility(int slotIndex, int taskId, boolean visible) {
        Slot slot = host.slots[slotIndex];
        if (slot.taskId != taskId || slot.promoting || slot.parked) return;
        slot.taskVisible = visible;
        WorkspaceHost.log("taskVisibility slot=" + slotIndex + " task=" + taskId + " visible=" + visible);
        if (!visible) host.retainedSurfaces.scheduleSideVisibilityRepair(slot);
    }

    void bindTask(Slot slot, int taskId, Object token) {
        if (slot.taskId != taskId || !java.util.Objects.equals(slot.taskToken, token)) {
            slot.pendingSidePromotion = null;
            slot.taskEpoch++;
            slot.removalCheckTask = -1;
            slot.visibilityRepair.reset();
            slot.scaleSurface = null;
            slot.scaleOnLeash = false;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
            slot.scaleAttempts = 0;
            slot.taskUid = 0;
            slot.reassertPending = false;
            slot.windowMode = -1;
            slot.modeRepairAttempts = 0;
            host.recycleBuffered(slot);
        }
        slot.taskId = taskId;
        slot.taskToken = token;
        synchronized (taskWindowStatesLock) {
            slot.windowState = taskWindowStates.get(taskId);
            if (slot.windowState == null) {
                slot.windowState = new TaskWindowState(taskId);
                taskWindowStates.put(taskId, slot.windowState);
            }
            trimTaskWindowStatesLocked();
        }
        slot.windowState.slotIndex = slot.index;
        OwnedTask retained = host.retainOwnedTask(token, taskId);
        if (retained != null) retained.recoveryController = slot.controller;
    }

    static int windowModeOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
            return (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int configurationOrientationOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            return XposedHelpers.getIntField(config, "orientation");
        } catch (Throwable ignored) {
            return TaskWindowState.UNKNOWN_ORIENTATION;
        }
    }

    static int requestedOrientationOf(Object info) {
        try {
            return XposedHelpers.getIntField(info, "requestedOrientation");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) {
        if (info == null || taskId < 0) return;
        try {
            int mode = windowModeOf(info);
            int configurationOrientation = configurationOrientationOf(info);
            int requestedOrientation = requestedOrientationOf(info);
            TaskWindowState state;
            synchronized (taskWindowStatesLock) {
                state = taskWindowStates.get(taskId);
                if (state == null) {
                    state = new TaskWindowState(taskId);
                    taskWindowStates.put(taskId, state);
                }
                trimTaskWindowStatesLocked();
            }
            state.update(slotIndex, configurationOrientation, requestedOrientation,
                    TaskWindowState.UNKNOWN_ROTATION, mode, mode == 1, active);
            if (slotIndex > SLOT_MAIN && slotIndex < SLOT_COUNT) {
                host.slots[slotIndex].windowState = state;
            }
        } catch (Throwable error) {
            WorkspaceHost.log("task window state update failed task=" + taskId + ": " + error);
        }
    }

    void syncSlotWindowState(Slot slot) {
        if (slot != null && slot.windowState != null) {
            slot.windowState.slotIndex = slot.index;
        }
    }

    void resetIdleTaskView(Slot slot) {
        if (slot == null || slot.controller == null) return;
        try {
            IdleTaskView.reset(slot.controller, text -> WorkspaceHost.log(text + " slot=" + slot.index));
        } catch (Throwable error) {
            WorkspaceHost.log("reset idle TaskView slot=" + (slot == null ? -1 : slot.index) + " failed: " + error);
        }
    }

    void promoteExternalLaunch(Slot slot) {
        if (slot != null && host.retainedOnExit && host.scene == WorkspaceHost.SUSPENDED) {
            host.exits.releaseRetainedTaskToMain(slot, true);
            return;
        }
        if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN || !host.slotRegistry.isActive(slot)) return;
        WorkspaceHost.log("external launcher launch detected slot=" + slot.index
                + " task=" + slot.taskId + " mode=" + slot.windowMode + "; promote");
        host.promotionController.queueSidePromotion(slot, false);
    }

    private void promoteUnsupportedSide(Slot slot) {
        if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN || !host.slotRegistry.isActive(slot)
                || slot.promoting || host.promotion != null || host.transferBusy) return;
        String pkg = slot.pkg == null ? "" : slot.pkg;
        slot.modeAdmissionPending = false;
        WorkspaceHost.log("side mode unsupported slot=" + slot.index + " task=" + slot.taskId
                + " mode=" + slot.windowMode + " pkg=" + pkg + "; open in main");
        android.widget.Toast.makeText(host.ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
        slot.modeGuardUntil = 0L;
        host.mainTaskActions.onSlotTapped(slot.index);
    }

    void scheduleSideModeResolution(Slot slot, int taskId) {
        long delay = Math.max(220L,
                slot.modeGuardUntil - SystemClock.uptimeMillis() + 150L);
        host.main.postDelayed(() -> {
            if (!host.slotRegistry.isActive(slot) || slot.taskId != taskId || slot.promoting
                    || slot.launching || slot.parked || !slot.occupied) return;
            if (slot.windowMode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
                host.scales.reapplyScale(slot);
            } else if (slot.windowMode == 1 && slot.modeAdmissionPending) {
                repairSideWindowMode(slot, taskId);
            }
        }, delay);
    }

    void repairSideWindowMode(Slot slot, int taskId) {
        if (!host.slotRegistry.isActive(slot) || slot.taskId != taskId || slot.promoting
                || slot.launching || slot.parked || !slot.modeAdmissionPending) return;
        long now = SystemClock.uptimeMillis();
        if (now < slot.modeGuardUntil) return;
        if (slot.modeRepairAttempts >= 2) {
            promoteUnsupportedSide(slot);
            return;
        }
        slot.modeRepairAttempts++;
        slot.modeGuardUntil = now + 850L;
        try {
            Object wct = TransactionCompat.newWindowTransaction();
            XposedHelpers.callMethod(wct, "setWindowingMode", slot.taskToken, 6);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken, host.slotRegistry.logicalBounds(slot));
            WorkspaceNativeHooks.applyTopConfiguration(host, wct, slot.taskToken, slot);
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setHidden", slot.taskToken, false);
            XposedHelpers.callMethod(wct, "reorder", slot.taskToken, true);
            boolean applied = WorkspaceNativeHooks.applyWct("initial-side-mode-repair-" + slot.modeRepairAttempts, wct);
            WorkspaceHost.log("initial side mode repair slot=" + slot.index + " task=" + taskId
                    + " attempt=" + slot.modeRepairAttempts + " applied=" + applied);
        } catch (Throwable error) {
            WorkspaceHost.log("initial side mode repair failed slot=" + slot.index + ": " + error);
        }
        host.main.postDelayed(() -> scheduleSideModeResolution(slot, taskId), 900L);
    }
}
