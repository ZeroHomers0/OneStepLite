package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

/** Owns main/side orientation animation and the host-only layout commit. */
final class WorkspaceMainSideController {
    private final WorkspaceHost host;

    WorkspaceMainSideController(WorkspaceHost host) {
        this.host = host;
    }

    private void rememberMainSide() {
        host.ctx.createDeviceProtectedStorageContext().getSharedPreferences("workspace_position",0)
                .edit().putBoolean("main_right",host.mainOnRight).apply();
    }

    private void translateSidePresentation(float offset) {
        for(Slot slot:host.slots) {
            if(slot==null||slot.index<=WorkspaceHost.SLOT_MAIN||slot.index==WorkspaceHost.SLOT_TOP)continue;
            // setX is also used by the steady slot layout. Its translation is
            // part of the base position, not an animation offset to clear.
            if(slot.rect!=null){
                if(slot.view!=null)slot.view.setX(slot.rect[0]+offset);
                if(slot.hint!=null)slot.hint.setX(slot.rect[0]+offset);
            }
            // The wallpaper is screen anchored, not part of the sliding content.
            if(slot.index<host.sideBackingViews.length&&host.sideBackingViews[slot.index]!=null)host.sideBackingViews[slot.index].setTranslationX(0);
        }
    }

    private void updateMainSideFrame(float offset, float mainX) {
        host.mainSideFrameX = mainX;
        translateSidePresentation(offset);
        host.windowPresentation.updateWorkspaceBackdropVisibility();
        host.chrome.updateChromeClip();
        if (host.root != null) host.root.invalidate();
    }

    private void settleMainSidePresentation() {
        host.mainSideFrameX = Float.NaN;
        host.mainSideBackdrop = null;
        translateSidePresentation(0);
        host.windowPresentation.updateWorkspaceBackdropVisibility();
        host.liveSwapInputBlocked=false;host.transferBusy=false;
        for(Slot slot:host.slots)if(slot!=null&&slot.index>WorkspaceHost.SLOT_MAIN&&slot.index!=WorkspaceHost.SLOT_TOP)host.chrome.updateTouchRegion(slot);
        if(host.root!=null){host.root.requestLayout();host.root.invalidate();}
        host.chrome.updateChromeClip();
        host.chrome.updateTopBarState();
    }

    void cancelMainSideSwitch() {
        host.mainSideSwitchGeneration++;
        ValueAnimator previous=host.mainSideAnimator;host.mainSideAnimator=null;
        if(previous!=null){previous.cancel();settleMainSidePresentation();}
    }

    /** Move cells through their outside edge, then animate the native main and incoming cells together. */
    void switchMainSideAnimated(boolean right) {
        if(host.root==null||host.layout==null||right==host.mainOnRight||host.mainSideAnimator!=null||host.liveSwapInputBlocked
                ||host.scene!=WorkspaceHost.ACTIVE||host.workspaceEntering||host.promotion!=null||host.transferBusy||host.cells.motion()!=null
                ||host.sideRows.motionRunning()||host.liveGeometry.holdsLiveFlipZoom()||!host.screenOn||host.keyguardLocked||host.suspendRequested)return;
        if(!ValueAnimator.areAnimatorsEnabled()){setMainSide(right);return;}
        final long generation=++host.mainSideSwitchGeneration,epoch=host.sceneEpoch;
        final float outgoing=host.layout.mainOnRight?-host.layout.sideWidth:host.layout.sideWidth;
        final float fromMainX=host.layout.mainOffsetX;
        host.mainSideFrameX=fromMainX;
        if(host.wallpaperBackdrop!=null&&!host.wallpaperBackdrop.isRecycled())
            host.mainSideBackdrop=new WallpaperCropDrawable(host.wallpaperBackdrop,host.screenW,host.screenH,0,0,HyperOsStyle.topWallpaperTint(host.ctx));
        host.liveSwapInputBlocked=true;host.transferBusy=true;
        ValueAnimator exit=ValueAnimator.ofFloat(0,1);
        host.mainSideAnimator=exit;
        exit.addUpdateListener(value->{if(host.mainSideAnimator==exit&&generation==host.mainSideSwitchGeneration)updateMainSideFrame(outgoing*(Float)value.getAnimatedValue(),fromMainX);});
        exit.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){
            if(host.mainSideAnimator!=exit||generation!=host.mainSideSwitchGeneration)return;
            if(epoch!=host.sceneEpoch||host.scene!=WorkspaceHost.ACTIVE||!host.screenOn||host.keyguardLocked||host.suspendRequested){cancelMainSideSwitch();host.resumes.finishPendingSuspend();return;}
            try{applyMainLayout(host.windowLayout.makeAdaptiveLayout(right));rememberMainSide();host.zoomSession.writeZoomAnimated(true);}
            catch(Throwable error){WorkspaceHost.log("animated side layout: "+error);cancelMainSideSwitch();return;}
            final float incoming=host.layout.mainOnRight?-host.layout.sideWidth:host.layout.sideWidth;
            final float toMainX=host.layout.mainOffsetX;
            updateMainSideFrame(incoming,fromMainX);
            ValueAnimator enter=ValueAnimator.ofFloat(0,1);host.mainSideAnimator=enter;
            enter.addUpdateListener(frame->{if(host.mainSideAnimator==enter&&generation==host.mainSideSwitchGeneration)updateMainSideFrame(incoming*(1-(Float)frame.getAnimatedValue()),fromMainX+(toMainX-fromMainX)*(Float)frame.getAnimatedValue());});
            enter.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
                if(host.mainSideAnimator!=enter||generation!=host.mainSideSwitchGeneration)return;
                host.mainSideAnimator=null;settleMainSidePresentation();host.resumes.finishPendingSuspend();
            }});WorkspaceMotionSpec.start(enter,host.workspaceZoomStart,WorkspaceHost.ZOOM_ANIM_MS);
        }});WorkspaceMotionSpec.start(exit,SystemClock.uptimeMillis(),WorkspaceMotionSpec.CELL_EXIT_MS);
    }

    void setMainSide(boolean right) {
        if (host.layout != null && host.mainOnRight == right) return;
        try {
            applyMainLayout(host.windowLayout.makeAdaptiveLayout(right));
            rememberMainSide();
            // Reopening from the opposite corner prepares Host geometry first.
            // The entry frame gate owns its animated zoom; an early steady write
            // would shrink the real main task before the backdrop is drawable.
            if (host.root != null && host.scene == WorkspaceHost.ACTIVE && !host.workspaceEntering) host.zoomSession.writeZoom(true);
        } catch (Throwable error) {
            WorkspaceHost.log("main side switch failed: " + error);
        }
    }

    /** Host geometry only. A live flip sends its correlated WMS zoom separately. */
    void applyMainLayout(WorkspaceLayout target) {
        if (target == null || target.width != host.screenW || target.height != host.screenH)
            throw new IllegalArgumentException("stale Host layout");
        host.mainOnRight = target.mainOnRight;
        host.layout = target;
        StatusBarSampling.geometryChanged();
        if (host.topBar != null) host.topBar.setMainOnRight(host.mainOnRight);
        WorkspaceHost.log("main side layout: " + (host.mainOnRight ? "right" : "left") + " " + host.layout.describe());
        host.wallpapers.applyWallpaperBackdrops();
        if (host.root == null) return;
        if (host.layout.tabletLandscape) host.topPresentationHost.updateTopTaskGeometry();
        host.sideRows.layoutAll();
        for (int i = 1; i < host.CELL_SLOT_COUNT; i++) {
            Slot slot = host.slots[i];
            slot.rect = host.slotRegistry.slotBounds(slot);
            host.sideRows.applyRect(slot);
            host.chrome.updateTouchRegion(slot);
        }
        for (int i = 1; i < host.CELL_SLOT_COUNT; i++) {
            View backing = host.sideBackingViews[i];
            if (backing == null
                    || !(backing.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) backing.getLayoutParams();
            int[] cell=target.cellBounds(i-1);
            params.leftMargin = target.tabletLandscape?cell[0]:target.mainOnRight ? 0 : target.mainWidth;
            params.topMargin = host.chrome.sideSegmentTop(i);
            params.width = target.tabletLandscape?cell[2]-cell[0]:target.sideWidth;
            params.height = host.chrome.sideSegmentHeight(i);
            backing.setLayoutParams(params);
        }
        host.root.requestLayout();
        host.root.invalidate();
        // A side switch moves the main task to the other edge, so the chrome cut
        // computed for the previous orientation is stale even at rest.
        host.chrome.updateChromeClip();
    }

}
