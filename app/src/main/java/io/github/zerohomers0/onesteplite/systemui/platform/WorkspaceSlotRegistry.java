package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
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

    Rect logicalBounds(Slot slot, boolean portrait) {
        if (slot == null || slot.index <= WorkspaceHost.coordinatorSlotMain()) return logicalBounds();
        if (slot.index != WorkspaceHost.coordinatorSlotTop()) {
            if (slot.rect == null || slot.rect[2] <= slot.rect[0] || slot.rect[3] <= slot.rect[1])
                return logicalBounds();
            return new Rect(0, 0, slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
        }
        TopTaskGeometry upper = host.topGeometry();
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
        return slot.index == WorkspaceHost.coordinatorSlotTop() ? host.topGeometry().bounds()
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
                && (slot.index == WorkspaceHost.coordinatorSlotTop() || slot.index < host.CELL_SLOT_COUNT
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
}
