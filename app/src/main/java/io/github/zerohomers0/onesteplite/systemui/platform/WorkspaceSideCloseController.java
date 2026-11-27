package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Owns side-task close admission and delayed task-removal verification. */
final class WorkspaceSideCloseController {
    private static final int SLOT_MAIN = WorkspaceHost.coordinatorSlotMain();
    private static final int SLOT_TOP = WorkspaceHost.coordinatorSlotTop();
    private static final int SLOT_COUNT = WorkspaceHost.coordinatorSlotCount();

    private final WorkspaceHost host;

    WorkspaceSideCloseController(WorkspaceHost host) {
        this.host = host;
    }

    java.util.List<SideClose> closingTasks(Object info) {
        java.util.List<SideClose> result = new java.util.ArrayList<>();
        if (host.scene != WorkspaceHost.ACTIVE || host.promotion != null || host.transferBusy || host.cells.motion() != null || host.root == null) return result;
        try {
            LiveSwapSession.ShellBackend backend = new LiveSwapSession.ShellBackend(
                    XposedHelpers.getObjectField(host.transitions, "mTransitions"), host.ctx.getClassLoader());
            backend.assertShellThread();
            for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
                if ((Integer) XposedHelpers.callMethod(change, "getMode") != 2) continue;
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task == null) continue;
                int id = XposedHelpers.getIntField(task, "taskId");
                Object token = XposedHelpers.getObjectField(task, "token");
                for (Slot slot : host.slots) {
                    if (slot.index == 0 || !host.cellSurfaces.presentsSlot(slot) || slot.index >= SLOT_COUNT
                            || slot.taskId != id || !java.util.Objects.equals(slot.taskToken, token)
                            || !slot.occupied || slot.promoting || slot.parked || slot.closing || slot.frozen
                            || !host.controllerOwns(slot, id, token)) continue;
                    Object parent = WorkspaceHost.getSurfaceField(slot.controller, "mSurfaceControl");
                    Object leash = XposedHelpers.callMethod(change, "getLeash");
                    if (parent == null || leash == null || slot.rect == null) continue;
                    long epoch = slot.taskEpoch;
                    FrameLayout closeRoot = host.root;
                    final WorkspaceLayout closeGeometry = host.layout;
                    Rect strip = host.liveSideStrip(closeGeometry);
                    LiveBackdrop wallpaper = LiveBackdrop.create(host.liveHostRootSurface(), host.wallpaperBackdrop,
                            host.screenW, host.screenH, strip, strip,
                            canvas -> host.drawLiveEmptyHints(canvas, closeGeometry, closeGeometry,
                                    1 << slot.index), WorkspaceHost::log);
                    slot.closing = true;
                    result.add(new SideClose(backend, slot.controller, leash, parent, slot.cellBackground, host.scales.scaleFor(slot),
                            slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1],
                            () -> host.isActive(slot) && host.scene == WorkspaceHost.ACTIVE && slot.taskId == id && slot.taskEpoch == epoch,
                            () -> { slot.closing = false; host.main.post(() -> {
                                if (!host.isActive(slot) || slot.taskId != id || slot.taskEpoch != epoch) {
                                    wallpaper.closeAfterFrame(closeRoot);
                                    return;
                                }
                                if (!host.executeOnShell(() -> {
                                    boolean stillOwned = host.controllerOwns(slot, id, token);
                                    if (!stillOwned) host.resetIdleTaskView(slot);
                                    host.main.post(() -> {
                                        if (host.isActive(slot) && slot.taskId == id && slot.taskEpoch == epoch) {
                                            if (stillOwned) onTaskRemovalStarted(slot.index, id);
                                            else { host.clearSlot(slot); host.cells.compactCells(); }
                                        }
                                        wallpaper.closeAfterFrame(closeRoot);
                                    });
                                })) {
                                    onTaskRemovalStarted(slot.index, id);
                                    wallpaper.closeAfterFrame(closeRoot);
                                }
                            }); }, WorkspaceHost::log));
                    WorkspaceHost.log("real side close task=" + id + " slot=" + slot.index);
                }
            }
        } catch (Throwable error) {
            for (SideClose close : result) close.abandon();
            result.clear();
            WorkspaceHost.log("side close admission: " + error);
        }
        return result;
    }

    void onTaskRemovalStarted(int slotIndex, int taskId) {
        Slot slot = host.slots[slotIndex];
        WorkspaceHost.log("onTaskRemovalStarted slot=" + slotIndex + " taskId=" + taskId
                + " current=" + slot.taskId);
        if (slot.closing || slot.nativeReleasePending) return;
        if (slot.promoting || slot.parked) {
            WorkspaceHost.log("early removal notification retained for host.promotion task=" + taskId);
            return;
        }
        if (slot.taskId != taskId || slot.removalCheckTask == taskId) return;
        slot.removalCheckTask = taskId;
        final long expectedEpoch = slot.taskEpoch;
        host.main.postDelayed(() -> {
            if (!host.isActive(slot) || slot.promoting || slot.taskId != taskId
                    || slot.taskEpoch != expectedEpoch) return;
            host.executeOnShell(() -> {
                boolean stillOwned = host.controllerOwns(slot, taskId, slot.taskToken);
                if (!stillOwned) host.resetIdleTaskView(slot);
                host.postForSlot(slot, owner -> {
                    if (owner.taskId != taskId || owner.taskEpoch != expectedEpoch || owner.promoting) return;
                    owner.removalCheckTask = -1;
                    if (stillOwned) {
                        WorkspaceHost.log("removal not detached; retain task=" + taskId);
                        return;
                    }
                    host.clearSlot(owner);
                    host.cells.compactCells();
                });
            });
        }, 250L);
    }
}
