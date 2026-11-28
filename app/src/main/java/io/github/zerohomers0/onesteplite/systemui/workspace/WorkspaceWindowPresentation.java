package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Owns workspace backdrop readiness, reveal animation, and live viewport geometry. */
final class WorkspaceWindowPresentation {
    private final WorkspaceHost host;
    private View workspaceBackdropOwner;
    private View.OnAttachStateChangeListener workspaceBackdropDetach;
    private final android.graphics.RectF workspaceHole = new android.graphics.RectF();
    private final android.graphics.RectF workspaceTopHole = new android.graphics.RectF();
    private final android.graphics.RectF workspaceSideHole = new android.graphics.RectF();

    WorkspaceWindowPresentation(WorkspaceHost host) {
        this.host = host;
    }

    void beginWorkspaceEntry(Runnable ready) {
        host.memory.cancel();
        final long generation = ++host.workspaceGeneration;
        host.workspaceEntering = true;
        host.zoomAnimUntil = SystemClock.uptimeMillis() + 1000L;
        setWorkspaceAlpha(0f);
        awaitWorkspaceFrame(generation, 0, ready);
    }

    private void prepareWorkspaceBackdrop(long generation, int attempt, Runnable ready) {
        if(generation!=host.workspaceGeneration || host.root==null || host.scene!=WorkspaceHost.ACTIVE && host.scene!=WorkspaceHost.RESUMING)return;
        if(host.wallpaperBackdrop==null && attempt<24) {
            host.main.postDelayed(()->prepareWorkspaceBackdrop(generation,attempt+1,ready),16L);return;
        }
        if (host.workspaceBackdrop != null || host.wallpaperBackdrop == null) {
            ready.run(); return;
        }
        final View owner=host.root;
        WorkspaceBackdrop.acquire(host.ctx, host.main, host.root.getWindowToken(), host.wallpaperBackdrop,
                host.screenW, host.screenH, backdrop -> {
                    if (generation!=host.workspaceGeneration || host.root!=owner || !host.screenOn || host.keyguardLocked
                            || host.scene!=WorkspaceHost.ACTIVE && host.scene!=WorkspaceHost.RESUMING) {
                        if(backdrop!=null)backdrop.close();return;
                    }
                    host.workspaceBackdrop=backdrop;
                    if(backdrop!=null) {
                        workspaceBackdropOwner=owner;
                        workspaceBackdropDetach=new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View view) {}
                        @Override public void onViewDetachedFromWindow(View view) {
                            if(host.workspaceBackdrop==backdrop)releaseWorkspaceBackdrop();
                        }
                        };
                        owner.addOnAttachStateChangeListener(workspaceBackdropDetach);
                    }
                    ready.run();
                }, WorkspaceHost::log);
    }

    void releaseWorkspaceBackdrop() {
        if(workspaceBackdropOwner!=null && workspaceBackdropDetach!=null)
            workspaceBackdropOwner.removeOnAttachStateChangeListener(workspaceBackdropDetach);
        workspaceBackdropOwner=null;workspaceBackdropDetach=null;
        if(host.workspaceBackdrop!=null)host.workspaceBackdrop.close();
        host.workspaceBackdrop=null;
    }

    void retireWorkspaceBackdrop() {
        if(host.workspaceBackdrop==null)return;
        if(!host.screenOn || host.keyguardLocked || workspaceBackdropOwner==null) {releaseWorkspaceBackdrop();return;}
        final LiveBackdrop backing=host.workspaceBackdrop;
        final long generation=host.workspaceGeneration;
        WorkspaceBackdrop.retire(host.ctx,host.main,workspaceBackdropOwner.getWindowToken(),
                ()->host.workspaceBackdrop==backing && host.workspaceGeneration==generation && !host.zoomed,
                this::releaseWorkspaceBackdrop,WorkspaceHost::log);
    }

    private void awaitWorkspaceFrame(long generation, int attempt, Runnable ready) {
        if (generation != host.workspaceGeneration || host.root == null || !host.screenOn || host.keyguardLocked
                || host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING) return;
        if (host.wallpaperBackdrop == null && attempt < 24) {
            host.main.postDelayed(() -> awaitWorkspaceFrame(generation, attempt + 1, ready), 16L);
            return;
        }
        FrameLayout enteringRoot = host.root;
        Runnable start = () -> host.main.post(() -> {
            if (generation != host.workspaceGeneration || host.root != enteringRoot || !host.screenOn || host.keyguardLocked
                    || host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING) return;
            if (host.scene == WorkspaceHost.RESUMING && !host.sideRows.isEmpty()) host.sideRows.layoutAll();
            // WM must see this Host's first buffer before validating its visible
            // window and lending the physical wallpaper parent. Reversing these
            // gates rejected every first entry and exposed an empty surround.
            prepareWorkspaceBackdrop(generation, 0, () -> {
                if (generation != host.workspaceGeneration || host.root != enteringRoot || !host.screenOn || host.keyguardLocked
                        || host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING) return;
                setWorkspaceAlpha(0f);
                host.zoomSession.writeZoomAnimated(true);
                host.zoomSession.setZoomState(true);
                host.carriers.scheduleMainCropClear(900L);
                playWorkspaceFade(true, generation, ready);
            });
        });
        if (host.root.isAttachedToWindow() && host.root.isHardwareAccelerated()) {
            host.root.getViewTreeObserver().registerFrameCommitCallback(start);
            host.root.invalidate();
        } else start.run();
    }

    void setWorkspaceAlpha(float value) {
        host.workspaceAlpha = Math.max(0f, Math.min(1f, value));
        // The top strip is revealed by geometry, not by opacity: the main task
        // covers it until its edge recedes, so a constant opaque bar reads as
        // being pushed out instead of fading in as a ghost over the app.
        if (host.topBar != null) host.topBar.setAlpha(host.layout == null ? host.workspaceAlpha : 1f);
        if (host.tabletTools != null) host.tabletTools.setAlpha(host.workspaceAlpha);
        boolean movingBackdrop = host.layout != null;
        boolean riding=host.layout!=null && !host.layout.tabletLandscape && host.mainSideAnimator==null;
        android.graphics.RectF hole = movingBackdrop ? workspaceMainHole() : null;
        android.graphics.RectF sideHole = movingBackdrop ? workspaceSideHole() : null;
        android.graphics.RectF topHole = movingBackdrop ? workspaceTopHole() : null;
        host.chrome.updateWorkspaceBacking(host.topStripBacking, hole);
        host.chrome.updateWorkspaceBacking(host.mainGapBacking, hole);
        Object tx = null;
        try {
            for (int i = 1; i < host.slots.length; i++) {
                if (i < WorkspaceHost.SLOT_COUNT) host.chrome.updateWorkspaceBacking(host.sideBackingViews[i], hole);
                Slot slot = host.slots[i];
                if (slot == null) continue;
                // Placeholders are clipped by the same geometry as their cells, so
                // they can stay opaque and be uncovered instead of fading in.
                LiveSwapVisualLease.writeAlpha(slot.hint,
                        host.taskCellsCovered && slot.index != WorkspaceHost.SLOT_TOP ? 0f
                                : movingBackdrop ? 1f : host.workspaceAlpha);
                if (slot.hint != null && slot.hint.getBackground() instanceof WallpaperCropDrawable)
                    ((WallpaperCropDrawable) slot.hint.getBackground())
                            .setMainHole(riding ? null : slot.index == WorkspaceHost.SLOT_TOP ? topHole : sideHole);
                if (slot.hint != null && slot.hint.getBackground()!=null)
                    slot.hint.getBackground().setAlpha(255);
                if (!host.cellSurfaces.ownsCellPresentation(slot)) continue;
                if (tx == null) tx = TransactionCompat.newSurfaceTransaction();
                host.cellSurfaces.stageCellPresentation(tx, slot, host.cellSurfaces.cellPresentationVisible(slot));
            }
            if (tx != null) XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { WorkspaceHost.log("workspace surface fade: " + error); }
        finally { host.sideRows.closeSurfaceObject(tx, "close"); }
        // Move visual edges with the main. The side gap grows back into its normal
        // size as the layout settles.
        if (riding) {
            if (host.topLiveClip != null) host.topLiveClip.setTranslationY(hole.top - host.layout.mainOffsetY);
            float sideOffset = WorkspaceTransition.sideOffset(host.layout.mainOnRight,host.layout.mainOffsetX,
                    host.layout.mainDispWidth,hole.left,hole.right,host.layout.gap,host.workspaceAlpha);
            for (Slot slot : host.slots) {
                if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN || slot.index == WorkspaceHost.SLOT_TOP) continue;
                if (slot.rect == null) continue;
                if (slot.view != null) slot.view.setX(slot.rect[0] + sideOffset);
                if (slot.hint != null) slot.hint.setX(slot.rect[0] + sideOffset);
            }
        }
        host.chrome.updateChromeClip();
        if(host.root!=null && (host.workspaceAlpha==0f || host.workspaceAlpha==1f))host.root.invalidate();
        if(host.topPresentation!=null)host.topPresentation.refreshVideo();
    }

    android.graphics.RectF workspaceMainHole() {
        return workspaceMainHoleAt(host.workspaceAlpha, workspaceHole);
    }

    /** Clips chrome to the uncovered region using the caller-owned scratch rectangle. */
    private android.graphics.RectF workspaceMainHoleAt(float progress, android.graphics.RectF out) {
        return WorkspaceTransition.mainHole(progress,host.screenW,host.screenH,host.layout.mainScale,
                host.layout.mainOffsetX,host.layout.mainOffsetY,host.mainSideFrameX,out);
    }

    android.graphics.RectF workspaceTopHole() {
        return workspaceMainHoleAt(WorkspaceRevealCurve.top(host.workspaceAlpha), workspaceTopHole);
    }

    android.graphics.RectF workspaceSideHole() {
        return workspaceMainHoleAt(WorkspaceRevealCurve.side(host.workspaceAlpha, host.workspaceExiting),
                workspaceSideHole);
    }

    void playWorkspaceFade(boolean entering, long generation, Runnable complete) {
        android.animation.ValueAnimator previous = host.workspaceAnimator;
        host.workspaceAnimator = null;
        if (previous != null) previous.cancel();
        // The reveal curves mirror with the direction: entering, the main edge
        // recedes and the chrome is uncovered; exiting, it advances and the
        // chrome is covered again.
        host.workspaceExiting = !entering;
        final float from = entering ? 0f : host.workspaceAlpha;
        WorkspaceTransition transition=new WorkspaceTransition(from,entering?1f:0f,
                ()->generation==host.workspaceGeneration,
                this::setWorkspaceAlpha,()->{
                    host.workspaceAnimator=null;
                    if(entering)host.workspaceEntering=false;
                    setWorkspaceAlpha(entering?1f:0f);
                    if(complete!=null)complete.run();
                    host.chrome.updateTopBarState();
                });
        host.workspaceAnimator=transition.animator();
        transition.start(host.workspaceZoomStart);
    }

    Rect workspaceCellCrop(Slot slot) {
        if (slot == null || slot.rect == null || host.layout == null) return null;
        return workspaceCellCrop(slot.rect, slot.index == WorkspaceHost.SLOT_TOP || host.layout.tabletLandscape);
    }

    Rect workspaceCellCrop(int[] rect, boolean aboveMain) {
        // While the chrome rides the main edge it is translated clear of the
        // window, so a second geometric cut would fight the offset and clip the
        // cell to nothing. Keep the full cell and let the offset define the edge.
        if (host.layout != null && !host.layout.tabletLandscape && host.workspaceAlpha < 1f
                && host.mainSideAnimator == null)
            return new Rect(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
        // Cells trail the receding main edge, so their crop follows the side curve.
        android.graphics.RectF main = workspaceSideHole();
        int[] crop = WorkspaceCellReveal.crop(rect[0], rect[1], rect[2], rect[3],
                aboveMain ? 0 : host.layout.topStrip, host.screenH, aboveMain, host.layout.mainOnRight,
                host.workspaceAlpha < 1f, main.left, main.top, main.right, main.bottom);
        return new Rect(crop[0], crop[1], crop[2], crop[3]);
    }

    void updateWorkspaceBackdropVisibility() {
        host.wallpapers.updateWorkspaceBackdropVisibility();
    }
}
