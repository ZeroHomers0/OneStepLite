package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** Owns display sampling, deferred refresh queues and workspace geometry rebuilds. */
final class WorkspaceDisplayController {
    private final WorkspaceHost host;
    private final Consumer<String> log;

    WorkspaceDisplayController(WorkspaceHost host, Consumer<String> log) {
        this.host = host;
        this.log = log;
    }

    void readDisplayGeometry() {
        android.util.DisplayMetrics metrics = host.ctx.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        try {
            if (host.displayManager == null) {
                host.displayManager = (DisplayManager) host.ctx.getSystemService(Context.DISPLAY_SERVICE);
            }
            Display display = host.displayManager == null
                    ? null : host.displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Point realSize = new Point();
                display.getRealSize(realSize);
                if (realSize.x > 0 && realSize.y > 0) {
                    width = realSize.x;
                    height = realSize.y;
                }
                host.displayRotation = display.getRotation();
            }
        } catch (Throwable error) {
            log.accept("display geometry read fell back to resources: " + error);
        }
        if (width > 0 && height > 0) {
            boolean tablet = DeviceProfile.isTablet(host.ctx);
            host.screenW = WorkspaceDisplayPolicy.width(tablet, width, height);
            host.screenH = WorkspaceDisplayPolicy.height(tablet, width, height);
        }
    }

    void scheduleDisplayGeometryRefresh() {
        if (host.geometryRefreshQueued) return;
        host.geometryRefreshQueued = true;
        host.main.postDelayed(() -> {
            host.geometryRefreshQueued = false;
            refreshDisplayGeometry();
        }, 120L);
    }

    void refreshDisplayGeometry() {
        if (host.root == null || host.displayManager == null) return;
        if (handleLiveGeometryChange()) return;
        if (deferGeometryChange()) return;
        applyGeometryChange();
    }
    private boolean handleLiveGeometryChange() {
            if (host.cellMotion != null || host.promotion != null && host.promotion.nativeSwap) {
                int oldWidth = host.screenW;
                int oldHeight = host.screenH;
                int oldRotation = host.displayRotation;
                readDisplayGeometry();
                int newWidth = host.screenW;
                int newHeight = host.screenH;
                int newRotation = host.displayRotation;
                // Display notifications also report non-geometric changes. Keep the
                // in-flight coordinate space intact until its finish or rollback.
                host.screenW = oldWidth;
                host.screenH = oldHeight;
                host.displayRotation = oldRotation;
                if (oldWidth == newWidth && oldHeight == newHeight && oldRotation == newRotation) {
                    if (host.cellMotion != null) log.accept("unchanged display geometry retains real cell motion");
                    return true;
                }
                if (host.cellMotion != null) {
                    host.cellMotion.finish();
                    scheduleDisplayGeometryRefresh();
                    return true;
                }
                host.geometryRefreshAfterLive = true;
                host.coordinatorFailPromotion(host.promotion, "display geometry changed during live swap "
                        + oldWidth + "x" + oldHeight + " r" + oldRotation
                        + " -> " + newWidth + "x" + newHeight + " r" + newRotation);
                return true;
            }
        return false;
    }
    private boolean deferGeometryChange() {
            if (host.transferBusy || host.cellMovePreview != null) {
                if (host.geometryRefreshDeferrals++ < 8) {
                    scheduleDisplayGeometryRefresh();
                    return true;
                }
            }
        host.geometryRefreshDeferrals = 0;
        return false;
    }
    private void applyGeometryChange() {
        int[] old={host.screenW,host.screenH,host.displayRotation};
        if(!prepareGeometry(old))return;
        host.layout=host.coordinatorMakeAdaptiveLayout(host.mainOnRight);
        applyGeometryLayout(old);
    }
    private boolean prepareGeometry(int[] old) {
        readDisplayGeometry();
        if(host.layout!=null && host.scene==WorkspaceHost.ACTIVE
                && (old[0]!=host.screenW || old[1]!=host.screenH)) {
            host.screenW=old[0];host.screenH=old[1];host.displayRotation=old[2];
            log.accept("display capacity change: return all real tasks before changing layout");
            host.coordinatorSuspendSession("display-layout-change");return false;
        }
        if(NativeLandscapePolicy.shouldRestoreFromDisplay(host.landscapeSuspended,
                host.scene==WorkspaceHost.SUSPENDED,host.displayRotation)) {
            log.accept("restore workspace after native landscape rotation="+host.displayRotation);
            host.coordinatorResumeSession();return false;
        }
        if(!DeviceProfile.isTablet(host.ctx) && host.displayRotation!=FullscreenRotationPolicy.portraitDisplayRotation()) {
            if(old[2]!=host.displayRotation)log.accept("workspace retains portrait geometry during display rotation "
                    +host.displayRotation+"; waiting for WM portrait configuration");
            return false;
        }
        return (old[0]!=host.screenW || old[1]!=host.screenH || old[2]!=host.displayRotation)
                && host.screenW>0 && host.screenH>0;
    }
    private void applyGeometryLayout(int[] old) {
                host.CELL_SLOT_COUNT=1+host.layout.cellCount;
        host.mainOnRight=host.layout.mainOnRight;
        for(Slot saved:host.slots) if(saved.sideRow < 0 && saved.index>=host.CELL_SLOT_COUNT && saved.index!=WorkspaceHost.coordinatorSlotTop()
                && saved.parked) {
            log.accept("layout capacity released system task="+saved.taskId);
            host.coordinatorClearSlot(saved);
        }
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, host.layout.topStrip);
        if (host.topStripBacking != null) host.topStripBacking.setLayoutParams(topParams);
        if (host.topLiveClip != null) {
            host.topLiveClip.setLayoutParams(new FrameLayout.LayoutParams(-1,host.layout.tabletLandscape?-1:host.layout.topStrip));
            host.topLiveClip.setClipBounds(new Rect(0,0,host.screenW,host.layout.tabletLandscape?host.screenH:host.layout.topStrip));
        }
        if (host.topBar != null) {
            host.topBar.setLayoutParams(new FrameLayout.LayoutParams(-1, host.layout.topStrip));
            host.topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(host.ctx));
        }
        if (!host.sideOrder.isEmpty()) host.coordinatorEnsureSideViewport();
        for (int i = 1; i < host.slots.length; i++) {
            Slot slot = host.slots[i];
            slot.rect = host.coordinatorSlotBounds(slot);
            host.coordinatorApplyRect(slot);
            host.coordinatorUpdateTouchRegion(slot);
            host.coordinatorUpdateHint(slot);
            View backing = i < WorkspaceHost.coordinatorSlotCount() ? host.sideBackingViews[i] : null;
            if (backing != null && i<host.CELL_SLOT_COUNT) {
                int[] cell=host.layout.cellBounds(i-1);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        host.layout.tabletLandscape?cell[2]-cell[0]:host.layout.sideWidth, host.coordinatorSideSegmentHeight(i));
                params.leftMargin = host.layout.tabletLandscape?cell[0]:host.layout.mainOnRight ? 0 : host.layout.mainWidth;
                params.topMargin = host.coordinatorSideSegmentTop(i);
                backing.setLayoutParams(params);
                backing.setVisibility(View.VISIBLE);
            } else if(backing!=null) {
                backing.setVisibility(View.GONE);
            }
            if (slot.carrierTapView != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
                params.leftMargin = slot.rect[0];
                params.topMargin = slot.rect[1];
                slot.carrierTapView.setLayoutParams(params);
            }
        }
        if (host.carrierBackdrop != null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    host.layout.mainDispWidth, host.layout.mainDispHeight);
            params.leftMargin = host.layout.mainOffsetX;
            params.topMargin = host.layout.mainOffsetY;
            host.carrierBackdrop.setLayoutParams(params);
        }
        host.coordinatorConfigureTabletTools();
        host.coordinatorUpdateTopTaskGeometry();
        host.coordinatorApplyWallpaperBackdrops();
        if (host.carrierSlotIndex >= 0) host.coordinatorReassertCarrier();
        if (host.wm != null && host.lp != null) {
            try { host.wm.updateViewLayout(host.root, host.lp); }
            catch (Throwable error) { log.accept("host window resize refresh failed: " + error); }
        }
        host.root.requestLayout();
        host.root.invalidate();
        boolean activeRoot = ZoomLifecyclePolicy.shouldReassertGeometry(
                host.scene == WorkspaceHost.ACTIVE && !host.keyguardLocked, host.root.getVisibility() == View.VISIBLE, host.screenOn);
        if (activeRoot) host.coordinatorWriteZoom(true);
        else log.accept("display geometry changed while host inactive; skip zoom/task reassert");
        boolean queued = activeRoot && host.coordinatorExecuteOnShell(() -> {
            if (host.scene != WorkspaceHost.ACTIVE) return;
            for (Slot slot : host.slots) {
                if (slot.index > WorkspaceHost.coordinatorSlotMain() && slot.occupied && !slot.carrier
                        && !slot.promoting && !slot.launching) host.coordinatorApplyFullscreenBounds(slot);
            }
        });
        host.main.postDelayed(() -> {
            if (!ZoomLifecyclePolicy.shouldReassertGeometry(host.scene == WorkspaceHost.ACTIVE && !host.keyguardLocked,
                    host.root != null && host.root.getVisibility() == View.VISIBLE, host.screenOn)) return;
            for (Slot slot : host.slots) {
                if (slot.index > WorkspaceHost.coordinatorSlotMain() && slot.occupied && !slot.carrier) host.coordinatorReapplyScale(slot);
            }
        }, queued ? 180L : 0L);
        log.accept("display geometry changed " + old[0] + "x" + old[1] + " r" + old[2]
                + " -> " + host.screenW + "x" + host.screenH + " r" + host.displayRotation
                + " queuedTaskBounds=" + queued + " " + host.layout.describe());


    }


}
