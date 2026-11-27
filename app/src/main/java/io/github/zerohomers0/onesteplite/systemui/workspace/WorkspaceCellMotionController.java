package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Owns logical cell compaction and moving-cell visual transitions. */
final class WorkspaceCellMotionController {
    private static final int SLOT_MAIN = WorkspaceHost.SLOT_MAIN;
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;
    private static final int SLOT_COUNT = WorkspaceHost.SLOT_COUNT;

    private final WorkspaceHost host;
    private boolean compactPending;
    private CellMotion cellMotion;

    WorkspaceCellMotionController(WorkspaceHost host) { this.host = host; }

    boolean pending() { return compactPending; }
    CellMotion motion() { return cellMotion; }
    void finishMotion() { if (cellMotion != null) cellMotion.finish(); }

    void compactCellsAfterCollection() {
        // Collection has committed its real task binding. Fill earlier empty
        // cells now instead of waiting for the next workspace resume.
        compactCells();
    }

    void compactCells() {
        if (host.root == null || host.layout == null) return;
        if (host.scene != WorkspaceHost.ACTIVE || host.promotion != null || host.transferBusy || cellMotion != null
                || host.sideRows.isArmed() || host.sideRows.isCaptured() || host.sideRows.motionRunning()) {
            compactPending = true;
            return;
        }
        // Pending callbacks and an active host gesture keep their owner geometry fixed.
        for (Slot s : host.slots) {
            if (s.promoting || s.launching || s.closing || s.nativeReleasePending
                    || s.bufferedDown != null || s.injecting) {
                compactPending = true;
                return;
            }
        }
        compactPending = false;
        if (host.sideRows.compactRows()) {
            host.publishDesktopSideTasks();
            return;
        }
        host.publishDesktopSideTasks();
        java.util.List<CellMovePlan> moves = new java.util.ArrayList<>();
        int initialEmptySlots = 0;
        for (Slot slot : host.slots) if (slot.index > SLOT_MAIN && !slot.occupied)
            initialEmptySlots |= 1 << slot.index;
        for (int i = 1; i < host.CELL_SLOT_COUNT - 1; i++) {
            Slot cur = host.slots[i];
            if (cur.occupied) {
                continue;
            }
            for (int j = i + 1; j < host.CELL_SLOT_COUNT; j++) {
                Slot next = host.slots[j];
                if (!next.occupied) {
                    continue;
                }
                WorkspaceHost.log("compactCells: slot" + j + " -> slot" + i + " (" + next.pkg + ")");
                host.recycleBuffered(cur);
                host.recycleBuffered(next);
                Rect oldRect = next.rect == null ? null : new Rect(
                        next.rect[0], next.rect[1], next.rect[2], next.rect[3]);
                SlotPositions.swap(host.slots, i, j);
                host.taskStates.syncSlotWindowState(host.slots[i]);
                host.taskStates.syncSlotWindowState(host.slots[j]);
                cur.rect = host.layout.cellBounds(cur.index - 1);
                next.rect = host.layout.cellBounds(next.index - 1);
                if (oldRect != null) {
                    moves.add(new CellMovePlan(next, oldRect));
                }
                break;
            }
        }
        if (!moves.isEmpty()) animateCellMoves(moves, initialEmptySlots,false);
    }

    void requestCompaction() {
        if (host.scene != WorkspaceHost.ACTIVE || host.root == null) return;
        compactPending = true;
        host.main.post(this::compactCells);
    }

    void finishCellLayout() {
        host.topPresentationHost.updateTopTaskGeometry();
        for (int i = 1; i < SLOT_COUNT; i++) {
            Slot slot = host.slots[i];
            host.sideRows.applyRect(slot);
            host.slotLifecycle.updateHint(slot);
            host.chrome.updateTouchRegion(slot);
            host.scales.reapplyScale(slot);
        }
        host.wallpapers.applyWallpaperBackdrops();
        host.publishDesktopSideTasks();
        if (host.root != null) { host.root.requestLayout(); host.root.invalidate(); }
    }

    private boolean cellMoveCurrent(Slot slot, FrameLayout movingRoot, long epoch, long binding,
            int id, Object token, Object controller, View movingView, Object surface) {
        String changed = host.root != movingRoot ? "host.root" : host.sceneEpoch != epoch ? "host.scene"
                : !host.slotRegistry.isActive(slot) ? "slot" : slot.taskEpoch != binding ? "binding"
                : slot.taskId != id ? "task" : !java.util.Objects.equals(slot.taskToken, token) ? "token"
                : slot.controller != controller ? "controller" : slot.view != movingView ? "view"
                : WorkspaceNativeHooks.getSurfaceField(controller, "mSurfaceControl") != surface ? "surface"
                : !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")) ? "invalid surface" : null;
        if (changed != null) WorkspaceHost.log("real cell motion binding changed: " + changed + " slot=" + slot.index
                + " task=" + id + " current=" + slot.taskId);
        return changed == null;
    }

    private static int compactionHintMask(int initialEmptySlots, int occupiedSlots) {
        return (initialEmptySlots | ~occupiedSlots) & ((1 << SLOT_COUNT) - 2);
    }

    void animateCellMoves(java.util.List<CellMovePlan> plans, int initialEmptySlots, boolean paired) {
        final FrameLayout movingRoot = host.root;
        final long epoch = host.sceneEpoch;
        final java.util.List<CellMotion.Move> moves = new java.util.ArrayList<>();
        final java.util.List<LiveSwapVisualLease> hints = new java.util.ArrayList<>();
        LiveBackdrop wallpaper = null;
        try {
            int occupiedSlots = 0;
            for (Slot slot : host.slots) if (slot.index > SLOT_MAIN && slot.occupied)
                occupiedSlots |= 1 << slot.index;
            for (CellMovePlan plan : plans) {
                Slot slot = plan.slot;
                final long binding = slot.taskEpoch;
                final int id = slot.taskId;
                final Object token = slot.taskToken, controller = slot.controller;
                final Object surface = WorkspaceNativeHooks.getSurfaceField(controller, "mSurfaceControl");
                if (!host.slotRegistry.canRender(slot) || surface == null) continue;
                final View movingView = slot.view;
                moves.add(new CellMotion.Move(movingView, plan.from.left, plan.from.top,
                        slot.rect[0], slot.rect[1], () -> cellMoveCurrent(slot, movingRoot, epoch, binding,
                        id, token, controller, movingView, surface)));
            }
            if (moves.isEmpty() || paired && moves.size()!=plans.size()) { finishCellLayout(); return; }
            // Preserve existing empty pluses and reveal vacated sources. An occupied
            // intermediate destination never acquires a one-frame '+' during travel.
            final int revealEmptySlots = compactionHintMask(initialEmptySlots, occupiedSlots);
            final WorkspaceLayout motionGeometry = host.layout;
            Rect strip = WorkspaceLiveGeometry.liveSideStrip(motionGeometry);
            wallpaper = LiveBackdrop.create(host.liveGeometry.liveHostRootSurface(), host.wallpaperBackdrop, host.screenW, host.screenH,
                    strip, strip, canvas -> host.drawLiveEmptyHints(canvas, motionGeometry, motionGeometry,
                            revealEmptySlots), WorkspaceHost::log);
            // One wallpaper/plus owner covers the complete strip during compaction.
            // Static backings otherwise cover its pluses and reappear between layouts.
            for (Slot slot : host.slots) if (slot.index > SLOT_MAIN && slot.index < host.CELL_SLOT_COUNT)
                hints.add(new LiveSwapVisualLease(host.sideBackingViews[slot.index], slot.hint));
            final LiveBackdrop backing = wallpaper;
            final CellMotion[] run = new CellMotion[1];
            run[0] = new CellMotion(movingRoot, moves,
                    () -> {
                        if (host.root != movingRoot || host.sceneEpoch != epoch) return;
                        finishCellLayout();
                        // Restore Host chrome in the SAME draw that retires the temporary
                        // layer, before waiting for that frame's committed callback.
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        backing.closeAfterFrame(movingRoot);
                    },
                    () -> {
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        movingRoot.invalidate();
                        backing.closeAfterFrame(movingRoot);
                        if (cellMotion != run[0]) return;
                        cellMotion = null;
                        host.transferBusy = false;
                        host.chrome.updateTopBarState();
                        // finishCellLayout ran while cellMotion still owned visibility.
                        // Reconcile now, including the empty old source's resize background.
                        for (Slot cell : host.slots) host.cellSurfaces.updateCellSurfaceVisibility(cell);
                        if (compactPending) compactCells();
                        host.resumes.finishPendingSuspend();
                    }, WorkspaceHost::log);
            cellMotion = run[0];
            host.transferBusy = true;
            run[0].start();
        } catch (Throwable error) {
            for (LiveSwapVisualLease hint : hints) hint.restore();
            if (wallpaper != null) wallpaper.closeAfterFrame(movingRoot);
            cellMotion = null;
            host.transferBusy = false;
            finishCellLayout();
            WorkspaceHost.log("real cell motion unavailable: " + error);
        }
    }

    static String appBoundsOf(Object info) {
        try {
            Object compat = XposedHelpers.getObjectField(info, "appCompatTaskInfo");
            if (compat == null) {
                return "null";
            }
            Object bounds = XposedHelpers.getObjectField(compat, "topActivityAppBounds");
            return String.valueOf(bounds);
        } catch (Throwable t) {
            return "unreadable(" + t + ")";
        }
    }
}
