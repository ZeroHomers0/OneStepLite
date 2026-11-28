package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Owns slot lookup, task-user scope and real task/controller identity checks. */
final class WorkspaceSlotRegistry {
    private final WorkspaceHost host;
    private final Consumer<String> log;
    private final Object taskUsersLock = new Object();
    private volatile Set<Integer> taskUserIds;
    private volatile long taskUserIdsReadAt;
    private volatile String taskUserIdsReadError;

    WorkspaceSlotRegistry(WorkspaceHost host, Consumer<String> log) {
        this.host = host;
        this.log = log;
    }

    Rect logicalBounds() {
        return new Rect(0, 0, host.screenW, host.screenH);
    }

    Rect logicalBounds(Slot slot) {
        return logicalBounds(slot, host.topPortrait);
    }

    Rect logicalBounds(Slot slot, boolean portrait) {
        // OEM minimum activity bounds can exceed a cell even when the task accepts it.
        if (slot == null || slot.index != WorkspaceHost.SLOT_TOP) return logicalBounds();
        TopTaskGeometry upper = host.windowLayout.topGeometry();
        if (!portrait) return new Rect(0, 0, upper.logicalWidth, upper.logicalHeight);
        int width = Math.max(1, Math.min(upper.logicalWidth,
                Math.round(upper.logicalHeight * 9f / 16f)));
        return new Rect(0, 0, width, upper.logicalHeight);
    }

    Slot slotForObject(Object object) {
        for (Slot slot : host.slots) if (slot.view == object || slot.controller == object) return slot;
        return null;
    }

    int[] slotBounds(Slot slot) {
        return slot.index == WorkspaceHost.SLOT_TOP ? host.windowLayout.topGeometry().bounds()
                : slot.index >= host.CELL_SLOT_COUNT ? new int[]{0, 0, 1, 1}
                : host.layout.cellBounds(slot.index - 1);
    }

    Set<Integer> supportedTaskUsers() {
        long now = SystemClock.uptimeMillis();
        Set<Integer> cached = taskUserIds;
        if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
        synchronized (taskUsersLock) {
            cached = taskUserIds;
            now = SystemClock.uptimeMillis();
            if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
            Set<Integer> ids = new HashSet<>();
            int currentUser = currentUserId();
            ids.add(currentUser);
            try {
                UserManager users = host.ctx.getSystemService(UserManager.class);
                if (users != null) {
                    java.util.List<UserHandle> profiles = users.getUserProfiles();
                    if (profiles != null) {
                        for (UserHandle profile : profiles) {
                            if (profile != null) {
                                ids.add((Integer) XposedHelpers.callMethod(profile, "getIdentifier"));
                            }
                        }
                    }
                }
                taskUserIdsReadError = null;
            } catch (Throwable error) {
                String message = String.valueOf(error);
                if (!message.equals(taskUserIdsReadError)) {
                    log.accept("associated task profiles unavailable; owner only: " + message);
                    taskUserIdsReadError = message;
                }
            }
            taskUserIds = Collections.unmodifiableSet(ids);
            taskUserIdsReadAt = now;
            log.accept("task profile scope current=" + currentUser + " associated=" + ids);
            return taskUserIds;
        }
    }

    boolean supportsTaskUser(int userId) {
        return TaskUserPolicy.allows(userId, currentUserId(), supportedTaskUsers());
    }

    int currentUserId() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getCurrentUser");
        } catch (Throwable ignored) {
            return (Integer) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId",
                    android.os.Process.myUid());
        }
    }

    boolean ownsViewOrController(Object object) {
        if (object == null) return false;
        for (Slot slot : host.slots)
            if (slot.index > 0 && !slot.parked && (slot.view == object || slot.controller == object)) return true;
        return false;
    }

    boolean mainTaskVisible(Object info) {
        return info != null && TaskUserPolicy.mayBeMainTask(
                XposedHelpers.getIntField(info, "userId"), currentUserId(), supportedTaskUsers(),
                XposedHelpers.getBooleanField(info, "isVisible"),
                XposedHelpers.getBooleanField(info, "isFocused"), false);
    }

    boolean isSideToken(Object token) {
        for (Slot slot : host.slots)
            if (!slot.parked && token != null && token.equals(slot.taskToken)) return true;
        return false;
    }

    boolean isActive(Slot slot) {
        Slot[] current = host.slots;
        return host.root != null && WorkspaceHost.isCurrentInstance(host) && slot.index > 0
                && slot.index < current.length && current[slot.index] == slot
                && (slot.index == WorkspaceHost.SLOT_TOP || slot.index < host.CELL_SLOT_COUNT
                || slot.sideRow >= 0);
    }

    boolean canRender(Slot slot) {
        return isActive(slot) && (slot.sideRow < 0 || slot.sidePrepared)
                && !slot.parked && host.screenOn && !host.keyguardLocked
                && (host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING)
                && host.root.getVisibility() == android.view.View.VISIBLE;
    }

    /** Reject late callbacks from a task the native controller has already relinquished. */
    boolean controllerOwns(Slot slot, int taskId, Object token) {
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (info != null) {
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
                if (base != null && Packages.LAUNCHER.equals(base.getPackageName())) return false;
            }
            return info != null && current != null
                    && XposedHelpers.getIntField(info, "taskId") == taskId
                    && (token == null || token.equals(current));
        } catch (Throwable t) {
            return false;
        }
    }
    LiveTask findMainTask() throws Exception {
        // A closing fullscreen app may remain visible while Home has already taken
        // focus. Visibility alone cannot make that departing app a swap participant.
        // If both claim focus the scene is still changing: retain both task bindings.
        LiveTask candidate = null;
        LiveTask focusedCandidate = null;
        boolean focusedHome = false;
        int visibleCandidates = 0;
        java.util.List<?> roots = rootInfos();
        for (Object info : roots) {
            if (isHomeRoot(info) && XposedHelpers.getBooleanField(info, "isVisible")
                    && XposedHelpers.getBooleanField(info, "isFocused")) focusedHome = true;
            if (!ordinaryRoot(info, 1)) continue;
            int userId = XposedHelpers.getIntField(info, "userId");
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
            boolean sideTask = isSideToken(XposedHelpers.getObjectField(info, "token"));
            if (!TaskUserPolicy.mayBeMainTask(userId, host.slotRegistry.currentUserId(),
                    supportedTaskUsers(), visible, focused, sideTask)) continue;
            if (visible) visibleCandidates++;
            LiveTask task = new LiveTask(info);
            if (focused) {
                if (focusedCandidate != null)
                    throw new IllegalStateException("ambiguous focused main roots");
                focusedCandidate = task;
            }
            candidate = task;
            host.taskStates.updateTaskWindowState(0, info, task.id, true);
        }
        if (focusedHome && focusedCandidate != null)
            throw new IllegalStateException("Home/app focus is changing; retain task bindings");
        if (focusedHome) return null;
        if (focusedCandidate != null) return focusedCandidate;
        if (visibleCandidates > 1) throw new IllegalStateException("ambiguous visible main roots");
        return candidate; // Home（type=2）不会被当主任务。
    }
    Object rootInfo(LiveTask task) throws Exception {
        for (Object info : rootInfos()) if (task.matches(info)) return info;
        return null;
    }
    java.util.List<?> rootInfos() throws Exception {
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
        return (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
    }
    boolean ordinaryRoot(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))) return false;
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        return base != null && !Packages.LAUNCHER.equals(base.getPackageName());
    }
    void dumpRoots(String reason) {
        try {
            java.util.List<?> roots = rootInfos();
            LiveTask main = null;
            try { main = findMainTask(); } catch (Throwable error) {
                WorkspaceHost.log("roots[" + reason + "] findMainTask threw: " + error);
            }
            WorkspaceDiagnostics.dumpRoots(reason,roots,main==null?-1:main.id,
                    this::isSideToken,info->ordinaryRoot(info,1),WorkspaceHost::log);
        } catch (Throwable error) {
            WorkspaceHost.log("roots dump[" + reason + "] failed: " + error);
        }
    }
    LiveTask findPackageTask(String pkg) throws Exception {
        Intent launch = host.ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        return findPackageTask(pkg, launch == null ? null : launch.getComponent());
    }
    LiveTask findPackageTask(String pkg, ComponentName entry) throws Exception {
        if (entry == null) throw new IllegalStateException("task launcher entry unavailable");
        LiveTask candidate = null;
        for (Object info : rootInfos()) {
            if (!TaskUserPolicy.isCurrentUser(
                    XposedHelpers.getIntField(info, "userId"), host.slotRegistry.currentUserId())) continue;
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (base == null || !pkg.equals(base.getPackageName())) continue;
            if (!TaskLaunchIdentity.matches(host.ctx.getPackageManager(), entry, info)) continue;
            if (!ordinaryRoot(info, 1) || isSideToken(XposedHelpers.getObjectField(info, "token")))
                throw new IllegalStateException("existing package task has unsupported owner/mode");
            if (candidate != null) throw new IllegalStateException("multiple package tasks; no implicit relaunch");
            candidate = new LiveTask(info);
        }
        return candidate;
    }
    LiveTask existingHomeTask() throws Exception {
        LiveTask home = null;
        for (Object info : rootInfos()) {
            if (!isHomeRoot(info)) continue;
            if (home != null) throw new IllegalStateException("ambiguous existing Home roots");
            home = new LiveTask(info);
        }
        if (home == null) throw new IllegalStateException("existing Home root unavailable");
        return home;
    }
    boolean isHomeRoot(Object info) {
        return info != null && Integer.valueOf(2).equals(
                    XposedHelpers.callMethod(info, "getActivityType"))
                && XposedHelpers.getIntField(info, "displayId") == 0
                && XposedHelpers.getIntField(info, "userId") == host.slotRegistry.currentUserId()
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"));
    }
    LiveTask existingHomeForCollection(LiveTask task) throws Exception {
        LiveTask home = existingHomeTask();
        HomeTaskReturn saved = host.coveredHome;
        if (saved != null && saved.covers(task.id, task.token)
                && !saved.restores(home.id, home.token))
            throw new IllegalStateException("covered Home identity changed; keep app in main");
        return home;
    }
    Object homeForCollection(LiveTask task) throws Exception {
        return existingHomeForCollection(task).token;
    }
}
