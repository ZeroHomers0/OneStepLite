package io.github.zerohomers0.onesteplite;

import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns asynchronous TaskView creation, cell affordances, and slot retirement. */
final class WorkspaceSlotLifecycle {
    private final WorkspaceHost host;

    WorkspaceSlotLifecycle(WorkspaceHost host) {
        this.host = host;
    }

    void createNext(final int index) {
        if (index < WorkspaceHost.SLOT_TOP && index >= host.CELL_SLOT_COUNT) {
            createNext(index + 1);
            return;
        }
        if (index >= WorkspaceHost.SLOT_COUNT) {
            host.topPresentationHost.configureTabletTools();
            WorkspaceHost.log("TaskViews ready cells="+host.layout.cellCount+" interactive=1; system main has no TaskView");
            host.cells.requestCompaction();
            return;
        }
        Slot owner = host.slots[index];
        Object prepared = TaskViewWarmup.take(host.ctx, host.factory);
        if (prepared != null) {
            host.main.post(() -> onTaskViewCreated(owner, prepared, true));
            return;
        }
        ShellCompat.createTaskView(host.factory, host.ctx, host.executor, taskView ->
                host.main.post(() -> onTaskViewCreated(owner, taskView, true)));
    }

    void onTaskViewCreated(Slot slot, Object taskViewObj, boolean next) {
        final int index = slot.index;
        try {
            View view = (View) taskViewObj;
            slot.view = view;
            slot.controller = ShellCompat.taskViewController(view);
            WorkspaceRuntimeCounters.TASK_VIEWS_CREATED.incrementAndGet();
            slot.rect = host.slotRegistry.slotBounds(slot);

            Object listener = host.taskStates.makeListener(index);
            XposedHelpers.callMethod(view, "setListener", host.executor, listener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
            if (!WorkspaceHost.boundsHooksReady) throw new IllegalStateException("logical bounds hooks unavailable");
            // ViewGroup clipping does not constrain separately composed task surfaces.
            XposedHelpers.callMethod(view,"setEnableSurfaceClipping",true);
            if (!DeviceProfile.isTablet(host.ctx)
                    && view instanceof android.view.SurfaceView) {
                // TaskView renders task leashes as children, not in its opaque buffer.
                // Do not expose SurfaceView's opaque resize colour between real frames.
                ((android.view.SurfaceView) view).getHolder().setFormat(PixelFormat.TRANSLUCENT);
            }
            if (slot.sideRow >= 0 && index >= WorkspaceHost.SLOT_COUNT) view.setVisibility(View.INVISIBLE);
            if(index==WorkspaceHost.SLOT_TOP) {
                // Compose the original TOP surface above the host instead of punching a hole
                // through the other live face. Set before attach; keep the native task parent.
                XposedHelpers.callMethod(view,"setZOrderOnTop",true);
                (host.layout.tabletLandscape?host.root:host.topLiveClip).addView(view,lp);
            } else host.root.addView(view, lp);
            host.sideRows.applyRect(slot);
            host.cellSurfaces.ensureCellBackground(slot);

            if(index==WorkspaceHost.SLOT_TOP) {
                host.topPresentationHost.createTopTaskControls();
                updateHint(slot); host.chrome.updateTouchRegion(slot);
                WorkspaceHost.log("upper landscape TaskView ready");
                createNext(index+1); return;
            }
            slot.hint = makeEmptyHint();
            slot.hint.setOnClickListener(v -> { if (!host.taskCellsCovered) host.mainTaskActions.onSlotTapped(slot.index); });
            host.root.addView(slot.hint, hintParams(slot));
            // 修：hint 在 applyRect 之后才创建，之前一直停在 (0,0)（屏幕上左上角那个多余的加号）。
            host.sideRows.applyRect(slot);
            updateHint(slot);

            if (host.workspaceAlpha < 1f) host.windowPresentation.setWorkspaceAlpha(host.workspaceAlpha);

            view.setOnClickListener(v -> { if (!host.taskCellsCovered) host.mainTaskActions.onSlotTapped(host.sideRows.slotIndexOfView(v)); });
            view.setOnTouchListener((v, e) -> host.slotRegistry.canRender(slot) && host.scene == WorkspaceHost.ACTIVE && host.onSlotTouch(slot.index, e));
            host.chrome.updateTouchRegion(slot);
            WorkspaceHost.log("slot " + index + " attached rect=" + java.util.Arrays.toString(slot.rect));
        } catch (Throwable t) {
            WorkspaceHost.log("onTaskViewCreated slot=" + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
        if (next) createNext(index + 1);
        else {
            host.sideRows.taskViewCreated();
        }
    }

    private TextView makeEmptyHint() {
        TextView tv = new TextView(host.ctx) {
            private final android.graphics.Paint plus = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (length() != 0) return;
                if (getBackground() instanceof WallpaperCropDrawable)
                    ((WallpaperCropDrawable) getBackground()).drawAddWell(canvas, getWidth(),
                            getHeight(), getResources().getDisplayMetrics().density);
                drawEmptyCellPlus(canvas, getWidth(), getHeight(), getCurrentTextColor(), plus);
            }
        };
        // SurfaceView clears a hole through the host. Paint above that hole while empty.
        tv.setBackground(HyperOsStyle.emptyCell(host.ctx));
        tv.setText("");
        tv.setTextColor(HyperOsStyle.textSecondary(host.ctx));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38f);
        tv.setTypeface(android.graphics.Typeface.create(
                "sans-serif-light", android.graphics.Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setContentDescription("空白窗口，点按添加应用");
        return tv;
    }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,
            int color, android.graphics.Paint plus) {
        WorkspaceCellStyle.drawAdd(canvas, width, height, HyperOsStyle.dp(host.ctx, 1f), plus);
    }

    private FrameLayout.LayoutParams hintParams(Slot slot) {
        return new FrameLayout.LayoutParams(
                slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
    }

    void updateHint(Slot slot) {
        if (slot.index != WorkspaceHost.SLOT_TOP && slot.hint != null)
            LiveSwapVisualLease.writeAlpha(slot.hint, host.taskCellsCovered ? 0f : host.workspaceAlpha);
        if (!host.cellSurfaces.presentsSlot(slot)) {
            if(slot.hint!=null)slot.hint.setVisibility(View.GONE);
            host.cellSurfaces.updateCellSurfaceVisibility(slot);
            return;
        }
        if(slot.index==WorkspaceHost.SLOT_TOP) {
            host.cellSurfaces.ensureCellBackground(slot); host.cellSurfaces.updateCellSurfaceVisibility(slot);
            host.topPresentationHost.updateTopExitWindow();
            return;
        }
        host.cellSurfaces.ensureCellBackground(slot);
        host.cellSurfaces.updateCellSurfaceVisibility(slot);
        if (slot.hint == null) {
            return;
        }
        host.wallpapers.refreshEmptyHintBackdrop(slot);
        if (slot.hint instanceof TextView) {
            TextView label = (TextView) slot.hint;
            label.setText(slot.parked ? "恢复" : "");
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    slot.parked ? 15f : 38f);
            label.setTextColor(slot.parked
                    ? HyperOsStyle.accent(host.ctx) : HyperOsStyle.textSecondary(host.ctx));
            label.setContentDescription(slot.parked
                    ? "已保留的窗口，点按恢复" : "空白窗口，点按添加应用");
        }
        slot.hint.setVisibility((slot.occupied && !slot.parked)
                || (host.sideRows.isCompacting() && slot.sideRow >= 0) ? View.GONE : View.VISIBLE);
    }

    void clearSlot(Slot slot) {
        Object retiredToken = slot.taskToken;
        host.scales.cancelScaleRepair(slot);
        host.scales.clearFrameRateHint(slot);
        if(slot.index==WorkspaceHost.SLOT_TOP && host.topTaskState.owner()!=null) {
            host.topTaskState.release(host.topTaskState.owner()); host.topOriginalSlot=null;
        }
        if(slot.index==WorkspaceHost.SLOT_TOP) host.topPortrait=false;

        slot.taskEpoch++;
        slot.removalCheckTask = -1;
        slot.retiredTaskId = slot.taskId;
        slot.occupied = false;
        slot.parked = false;
        slot.frozen = false;
        slot.promoting = false;
        slot.taskVisible = false;
        slot.taskId = -1;
        slot.taskToken = null;
        slot.windowMode = -1;
        slot.nativeReleasePending = false;
        slot.pendingSidePromotion = null;
        slot.launching = false;
        slot.injecting = false;
        slot.modeGuardUntil = 0L;
        host.taskStates.clearWindowState(slot);
        host.releaseOwnedTask(retiredToken);
        slot.taskUid = 0;
        slot.pkg = null;
        slot.modeAdmissionPending = false;
        slot.modeRepairAttempts = 0;
        slot.scaleSurface = null;
        slot.scaleOnLeash = false;
        slot.scaleTaskId = Integer.MIN_VALUE;
        slot.scaleApplied = -1f;
        slot.scaleAttempts = 0;
        slot.reassertPending = false;
        slot.visibilityRepair.reset();
        host.recycleBuffered(slot);
        updateHint(slot);
        host.chrome.updateTouchRegion(slot);
        host.publishDesktopSideTasks();
        host.cells.requestCompaction();
    }
}
