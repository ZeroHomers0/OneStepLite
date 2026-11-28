package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Owns task-surface scale, viewport, bounds and frame-rate hints. */
final class WorkspaceScaleController {
    private static final int SLOT_MAIN = WorkspaceHost.SLOT_MAIN;
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;
    private static final long SCALE_REPAIR_FIRST_MS = 300L;
    private static final long SCALE_REPAIR_FINAL_MS = 900L;

    private final WorkspacePorts.SlotPort slots;
    private final WorkspacePorts.FramePort frames;
    private final WorkspacePorts.TopPort top;
    private boolean frameRateHintUnavailableLogged;

    WorkspaceScaleController(WorkspacePorts.SlotPort slots, WorkspacePorts.FramePort frames,
            WorkspacePorts.TopPort top) {
        this.slots=slots;this.frames=frames;this.top=top;
    }

    float mainScale() {
        return frames.layout().mainScale;
    }

    float cellScale() {
        return frames.layout().cellScale(0);
    }

    float scaleFor(Slot slot) {
        if (slot.index == SLOT_TOP) return frames.windowLayout().topGeometry().scale;
        if (slot.rect != null) {
            return Math.min((slot.rect[2] - slot.rect[0]) / (float) frames.screenWidth(),
                    (slot.rect[3] - slot.rect[1]) / (float) frames.screenHeight());
        }
        return frames.layout().cellScale(slot.index - 1);
    }


    /** Coalesces TaskInfo bursts into one cancellable first/final repair chain per slot. */
    void requestScaleRepair(Slot slot) {
        if (slot == null || slot.scaleRepairRunning) return;
        if (slot.scaleRepair != null) {
            frames.main().removeCallbacks(slot.scaleRepair);
            slot.scaleRepair = null;
            WorkspaceRuntimeCounters.SCALE_REPAIRS_COALESCED.incrementAndGet();
        }
        scheduleScaleRepair(slot, slot.taskId, slot.taskToken, slot.taskEpoch, 0,
                SCALE_REPAIR_FIRST_MS);
    }

    private void scheduleScaleRepair(Slot slot, int taskId, Object token, long taskEpoch,
            int step, long delay) {
        if (!scaleRepairCurrent(slot, taskId, token, taskEpoch)) {
            WorkspaceRuntimeCounters.SCALE_REPAIRS_SKIPPED.incrementAndGet();
            return;
        }
        Runnable repair = () -> {
            slot.scaleRepair = null;
            if (!scaleRepairCurrent(slot, taskId, token, taskEpoch)) {
                WorkspaceRuntimeCounters.SCALE_REPAIRS_SKIPPED.incrementAndGet();
                return;
            }
            slot.scaleRepairRunning = true;
            try {
                WorkspaceRuntimeCounters.SCALE_REPAIRS_EXECUTED.incrementAndGet();
                reapplyScale(slot);
            } finally {
                slot.scaleRepairRunning = false;
            }
            if (step == 0 && scaleRepairCurrent(slot, taskId, token, taskEpoch)) {
                scheduleScaleRepair(slot, taskId, token, taskEpoch, 1, SCALE_REPAIR_FINAL_MS);
            }
        };
        slot.scaleRepair = repair;
        WorkspaceRuntimeCounters.SCALE_REPAIRS_SCHEDULED.incrementAndGet();
        frames.main().postDelayed(repair, delay);
    }

    private boolean scaleRepairCurrent(Slot slot, int taskId, Object token, long taskEpoch) {
        return slot != null && slots.slotRegistry().canRender(slot)
                && slot.taskId == taskId && slot.taskEpoch == taskEpoch
                && java.util.Objects.equals(slot.taskToken, token);
    }

    void cancelScaleRepair(Slot slot) {
        if (slot == null || slot.scaleRepair == null) return;
        frames.main().removeCallbacks(slot.scaleRepair);
        slot.scaleRepair = null;
    }

    void cancelAllScaleRepairs() {
        for (Slot slot : slots.slots()) cancelScaleRepair(slot);
    }

    /** Force a fresh geometry pass after a hidden/restored window; caches can outlive the zoom owner. */
    void invalidateAllScaleGeometry() {
        for (Slot slot : slots.slots()) {
            if (slot == null) continue;
            slot.scaleSurface = null;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
            slot.scaleOnLeash = false;
            slot.scaleAttempts = 0;
        }
    }

    void applySlotScale(Slot slot) {
        if (frames.liveGeometry().holdsLiveFlipZoom() || !slots.slotRegistry().canRender(slot) || slot.promoting || !slot.occupied || slot.view == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        float k = scaleFor(slot);
        Object liveLeash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
        if (!slots.slotRegistry().controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        if (liveLeash != null && slot.scaleSurface == liveLeash
                && slot.scaleTaskId == slot.taskId && Math.abs(slot.scaleApplied - k) < 0.0005f) {
            return;
        }
        // 顺序很重要（真机实测）：先读 leash 做缩放，再改整屏边界 —— 反过来会让
        // TaskViewTransitions 把 mTaskLeash 清空重建，900ms 内都读不到。
        if (liveLeash == null) {
            if (slot.scaleAttempts >= 8) return;
            slot.scaleAttempts++;
            WorkspaceHost.log("applySlotScale slot=" + slot.index + " leash not ready, retry #" + slot.scaleAttempts);
            frames.main().postDelayed(() -> applySlotScale(slot), 200L);
            return;
        }
        slot.scaleAttempts = 0;
        Object sc = liveLeash;
        if (slot.scaleSurface != sc) { slot.frameRateHinted = false; slot.frameRateApplied = -1f; }
        slot.scaleOnLeash = true;
        if (applyScaleTransform(slot, sc)) {
            slot.scaleSurface = sc;
            slot.scaleTaskId = slot.taskId;
            slot.scaleApplied = k;
            OwnedTask accepted = slots.ownedTasks().get(slot.taskToken);
            if (accepted != null) accepted.surface = sc;
            WorkspaceHost.log("applySlotScale slot=" + slot.index + " task=" + slot.taskId
                    + " k=" + k + " onLeash=" + slot.scaleOnLeash + " surface=" + sc);
        }
        // TaskView may rewrite bounds while the surface changes. Keep one coalesced
        // first/final repair chain instead of accumulating anonymous callbacks.
        requestScaleRepair(slot);
    }

    void reapplyScale(Slot slot) {
        if (frames.liveGeometry().holdsLiveFlipZoom() || !slots.slotRegistry().canRender(slot) || slot.promoting || !slot.occupied || !mayWriteSideSurface(slot)) {
            return;
        }
        // The cross-fade owns the frames.main() surface alpha until it hands off; a delayed
        // reapply would snap the window back to its resting frame mid-animation.
        if (top.topTransition() != null && slot.index == SLOT_MAIN) return;
        if (!slots.slotRegistry().controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        Object current = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
        if (current == null || current != slot.scaleSurface) {
            applySlotScale(slot);
            return;
        }
        boolean applied = applyScaleTransform(slot, slot.scaleSurface);
        if (!applied) {
            // 表面可能已失效（任务重启/改边界后转场重建）：清缓存 + 放开去重门槛，
            // 下次 TaskInfo 变化时重新解析并整套重来。
            slot.scaleSurface = null;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
        }
        requestScaleRepair(slot);
    }

    void applyFullscreenBounds(Slot slot) {
        if (frames.liveGeometry().holdsLiveFlipZoom() || !slots.slotRegistry().canRender(slot) || slot.promoting || slot.taskToken == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        if (!slots.slotRegistry().controllerOwns(slot, slot.taskId, slot.taskToken)
                || WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash") == null) return;
        try {
            Object wct = TransactionCompat.newWindowTransaction();
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken,
                    slots.slotRegistry().logicalBounds(slot));
            WorkspaceNativeHooks.applyTopConfiguration(slots,frames,top,wct,slot.taskToken,slot);
            boolean ok = WorkspaceNativeHooks.applyWct("fullscreenBounds(slot=" + slot.index + ")", wct);
            WorkspaceHost.log("fullscreenBounds slot=" + slot.index + " task=" + slot.taskId + " ok=" + ok);
        } catch (Throwable t) {
            WorkspaceHost.log("fullscreenBounds slot=" + slot.index + " failed: " + t);
        }
    }

    void appendSlotViewport(Object tx, Slot slot) {
        if (frames.liveGeometry().holdsLiveFlipZoom() || slot == null || slot.index <= SLOT_MAIN
                || slot.view == null || !slots.slotRegistry().canRender(slot) || slot.promoting || slot.launching
                || slot.frozen || !mayWriteSideSurface(slot)
                || !slots.slotRegistry().controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
        if (parent == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isValid"))) return;
        Rect crop = new Rect(0, 0, slot.view.getWidth(), slot.view.getHeight());
        Rect viewClip = slot.view.getClipBounds();
        if (crop.isEmpty() || viewClip != null && !crop.intersect(viewClip)) return;
        // Never rotate, move or scale this parent. TaskView's own render thread
        // continues to place it, including the top task's collection clipping.
        XposedHelpers.callMethod(tx, "setWindowCrop", parent, crop);
        // TaskView parent pixels are physical and stay outside task rotation.
        XposedHelpers.callMethod(tx, "setCornerRadius", parent, 0f);
    }

    private boolean applyScaleTransform(Slot slot, Object sc) {
        if (frames.liveGeometry().holdsLiveFlipZoom() || !slots.slotRegistry().canRender(slot) || slot.promoting || !mayWriteSideSurface(slot)) return false;
        float k = scaleFor(slot);
        Object tx = null;
        try {
            tx = TransactionCompat.newSurfaceTransaction();
            if (slot.scaleOnLeash) {
                // A letterboxed portrait app sits centred in the strip instead of at its left edge.
                float offsetX = 0f;
                if (slot.index == SLOT_TOP && top.topPortrait()) {
                    TopTaskGeometry upper = frames.windowLayout().topGeometry();
                    Rect box = slots.slotRegistry().logicalBounds(slot);
                    offsetX = Math.max(0f, (upper.right - box.width() * k) * .5f);
                }
                XposedHelpers.callMethod(tx, "setPosition", sc, offsetX, 0f);
                // v1.1.3：视口也要对齐 —— 只缩不放位置会"比例对但只显示一部分"（真机反馈）。
                try {
                    XposedHelpers.callMethod(tx, "setWindowCrop", sc,
                            slots.slotRegistry().logicalBounds(slot));
                } catch (Throwable ignored) {
                    // 裁剪失败也继续，位置+矩阵仍是主体
                }
            }
            XposedHelpers.callMethod(tx, "setMatrix", sc, k, 0f, 0f, k);
            appendSlotViewport(tx, slot);
            applyFrameRateHint(tx, slot, sc);
            XposedHelpers.callMethod(tx, "apply");
            WorkspaceRuntimeCounters.SURFACE_TRANSFORMS.incrementAndGet();
            return true;
        } catch (Throwable t) {
            WorkspaceHost.log("applyScaleTransform slot=" + slot.index + " failed: " + t);
            return false;
        } finally {
            slots.sideRows().closeSurfaceObject(tx, "close");
        }
    }

    private void applyFrameRateHint(Object tx, Slot slot, Object surface) {
        if (tx == null || slot == null || surface == null) return;
        float fps = SideRefreshPolicy.target(slot.index == SLOT_TOP, slot.taskFocused,
                slot.promoting || slot.launching);
        if (slot.frameRateHinted && Math.abs(slot.frameRateApplied - fps) < 0.01f) {
            WorkspaceRuntimeCounters.FRAME_RATE_HINTS_SKIPPED.incrementAndGet();
            return;
        }
        try {
            if (!ShellCompat.hasMethod(tx.getClass(), "setFrameRate", 3))
                throw new NoSuchMethodException("SurfaceControl.Transaction.setFrameRate");
            XposedHelpers.callMethod(tx, "setFrameRate", surface, fps, 1);
            Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
            if (parent != null && parent != surface)
                XposedHelpers.callMethod(tx, "setFrameRate", parent, fps, 1);
            slot.frameRateHinted = true;
            slot.frameRateApplied = fps;
            WorkspaceRuntimeCounters.FRAME_RATE_HINTS_SET.incrementAndGet();
        } catch (Throwable unavailable) {
            if (!frameRateHintUnavailableLogged) {
                frameRateHintUnavailableLogged = true;
                WorkspaceHost.log("side frame-rate hint unavailable: " + unavailable);
            }
        }
    }

    void clearFrameRateHint(Slot slot) {
        if (slot == null) return;
        boolean wasHinted = slot.frameRateHinted;
        slot.frameRateHinted = false;
        slot.frameRateApplied = -1f;
        if (slot.controller == null) return;
        Object leash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
        Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
        if (leash == null && parent == null) {
            if (wasHinted) WorkspaceRuntimeCounters.FRAME_RATE_HINTS_CLEARED.incrementAndGet();
            return;
        }
        Object tx = null;
        try {
            tx = TransactionCompat.newSurfaceTransaction();
            boolean clear = ShellCompat.hasMethod(tx.getClass(), "clearFrameRate", 1);
            for (Object surface : new Object[] {leash, parent}) {
                if (surface == null) continue;
                try {
                    if (clear) XposedHelpers.callMethod(tx, "clearFrameRate", surface);
                    else if (ShellCompat.hasMethod(tx.getClass(), "setFrameRate", 3))
                        XposedHelpers.callMethod(tx, "setFrameRate", surface, 0f, 0);
                } catch (Throwable ignored) { /* Another layer may still retain a hint. */ }
            }
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable ignored) {
            /* A departed task may already have released both layers. */
        } finally {
            slots.sideRows().closeSurfaceObject(tx, "close");
        }
        if (wasHinted) WorkspaceRuntimeCounters.FRAME_RATE_HINTS_CLEARED.incrementAndGet();
    }

    void clearAllFrameRateHints() {
        for (Slot slot : slots.slots()) clearFrameRateHint(slot);
    }

    void refreshFrameRateHint(Slot slot) {
        if (slot == null || !slots.slotRegistry().canRender(slot) || slot.controller == null) return;
        Object surface = slot.scaleSurface;
        if (surface == null) surface = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
        if (surface == null) return;
        Object tx = null;
        try {
            tx = TransactionCompat.newSurfaceTransaction();
            applyFrameRateHint(tx, slot, surface);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable ignored) {
            /* The next geometry repair will retry the hint. */
        } finally {
            slots.sideRows().closeSurfaceObject(tx, "close");
        }
    }

    boolean mayWriteSideSurface(Slot slot) {
        if(slot!=null && slot.index==SLOT_TOP && !top.topFace())return false;
        if (slot == null || slot.windowMode != 6 || slot.controller == null) return false;
        PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.desktopLaunch) return false;
        try {
            // Controller.onTaskInfoChanged forwards info without replacing its
            // mTaskInfo on this ROM. Read the organizer's current canonical info.
            Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            Object lock = XposedHelpers.getObjectField(organizer, "mLock");
            synchronized (lock) {
                Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
                Object appeared = XposedHelpers.callMethod(tasks, "get", slot.taskId);
                Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                return info != null && XposedHelpers.getIntField(info, "taskId") == slot.taskId
                        && slot.taskToken != null && slot.taskToken.equals(
                                XposedHelpers.getObjectField(info, "token"))
                        && Integer.valueOf(6).equals(XposedHelpers.callMethod(info, "getWindowingMode"));
            }
        } catch (Throwable unavailable) { return false; }
    }
}
