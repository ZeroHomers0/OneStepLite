package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** Owns asynchronous wallpaper loading, publication and backing drawables. */
final class WorkspaceWallpaperController {
    private static final int MAX_TRANSITION_WAIT_ATTEMPTS = 20;
    private static final long TRANSITION_WAIT_MS = 50L;
    private final WorkspaceHost host;
    private final Consumer<String> log;

    WorkspaceWallpaperController(WorkspaceHost host, Consumer<String> log) {
        this.host = host;
        this.log = log;
        this.wallpaperRetry = () -> {
            if (host.root != null && host.screenOn && !host.keyguardLocked
                    && (host.scene == WorkspaceHost.ACTIVE
                    || host.scene == WorkspaceHost.RESUMING)) loadWallpaperBackdrop();
        };
    }

    final Runnable wallpaperRetry;



    void scheduleWallpaperRetry() {
        host.main.removeCallbacks(wallpaperRetry);
        long delay = Math.min(30000L, 1500L << Math.min(host.wallpaperRetryAttempt++, 4));
        if (host.root != null && host.screenOn && !host.keyguardLocked
                && (host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING)) host.main.postDelayed(wallpaperRetry, delay);
    }

    void loadWallpaperBackdrop() {
        if (host.wallpaperBackdropRequested) return;
        if (!host.wallpaperBackdropDirty && host.wallpaperBackdrop != null && !host.wallpaperBackdrop.isRecycled()) {
            applyWallpaperBackdrops();
            return;
        }
        if (host.screenW <= 0 || host.screenH <= 0) return;
        if (host.root == null) return;
        if (!WallpaperSnapshot.canCapture(host.ctx)) {
            scheduleWallpaperRetry();
            return;
        }
        host.wallpaperBackdropRequested = true;
        final long request = ++host.wallpaperLoadGeneration;
        final int width = host.screenW, height = host.screenH;
        final boolean canUseWarmup = !host.wallpaperBackdropDirty;
        final int user = CurrentUser.id();
        BackgroundExecutor.execute(() -> {
            Bitmap ready = null;
            Bitmap wellReady = null;
            try {
                Bitmap source = WorkspaceBackground.loadBlurred(host.ctx, width, height);
                Bitmap[] prepared = source == null && canUseWarmup
                        ? Warmup.takePreparedWallpaper(user, width, height) : null;
                if (prepared != null) {
                    host.main.post(() -> finishWallpaperLoad(request, width, height, prepared[0], prepared[1], 0));
                    return;
                }
                if (source == null) source = HyperOsStyle.blurredWallpaper(host.ctx, width, height);
                ready = WorkspaceFrost.finish(source);
                // A tiny, extra-blurred texture is shared by all circular wells.
                // Prepare it off the UI thread; no blur or capture work per frame.
                if (ready != null) wellReady = HyperOsStyle.blurredBitmap(ready,
                        Math.max(1, width / 6), Math.max(1, height / 6));
            } catch (Throwable error) {
                log.accept("wallpaper blur failed: " + error);
            }
            final Bitmap bitmap = ready;
            final Bitmap wellBitmap = wellReady;
            host.main.post(() -> finishWallpaperLoad(request, width, height, bitmap, wellBitmap, 0));
        });
    }

    void finishWallpaperLoad(long request, int width, int height, Bitmap bitmap, Bitmap wellBitmap) {
        finishWallpaperLoad(request, width, height, bitmap, wellBitmap, 0);
    }

    private void finishWallpaperLoad(long request, int width, int height,
            Bitmap bitmap, Bitmap wellBitmap, int waitAttempt) {
        if (request != host.wallpaperLoadGeneration || host.root == null || width != host.screenW || height != host.screenH
                || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)
                || !WallpaperSnapshot.canCapture(host.ctx)) {
            recycleWallpaper(bitmap, wellBitmap);
            if (request == host.wallpaperLoadGeneration) {
                host.wallpaperBackdropRequested = false;
                scheduleWallpaperRetry();
            }
            return;
        }
        boolean transitionOwnsTexture = host.mainSideAnimator != null || host.workspaceAnimator != null
                || host.promotion != null || host.cells.motion() != null;
        if (bitmap != null && !bitmap.isRecycled() && transitionOwnsTexture) {
            if (waitAttempt >= MAX_TRANSITION_WAIT_ATTEMPTS) {
                recycleWallpaper(bitmap, wellBitmap);
                host.wallpaperBackdropRequested = false;
                log.accept("wallpaper publication deadline reached; retain previous texture");
                scheduleWallpaperRetry();
                return;
            }
            // Every layer of one transition keeps the same texture until its final frame.
            host.main.postDelayed(() -> finishWallpaperLoad(
                    request, width, height, bitmap, wellBitmap, waitAttempt + 1), TRANSITION_WAIT_MS);
            return;
        }
        host.wallpaperBackdropRequested = false;
        if (bitmap == null || bitmap.isRecycled()) {
            recycleWallpaper(bitmap, wellBitmap);
            log.accept("wallpaper blur unavailable; retain last desktop texture");
            scheduleWallpaperRetry();
            return;
        }
        // Old drawables/native animations retain their bitmap until their last
        // frame. Let GC reclaim it after those owners release, never recycle live textures.
        host.wallpaperBackdrop = bitmap;
        host.wellWallpaperBackdrop = wellBitmap;
        host.wallpaperBackdropDirty = false;
        host.wallpaperRetryAttempt = 0;
        host.main.removeCallbacks(wallpaperRetry);
        log.accept("wallpaper gaussian backdrop ready " + bitmap.getWidth() + "x" + bitmap.getHeight());
        applyWallpaperBackdrops();
        }

    void cancelPending() {
        ++host.wallpaperLoadGeneration;
        host.wallpaperBackdropRequested = false;
        host.wallpaperRetryAttempt = 0;
        host.main.removeCallbacks(wallpaperRetry);
    }

    private static void recycleWallpaper(Bitmap bitmap, Bitmap wellBitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        if (wellBitmap != null && !wellBitmap.isRecycled()) wellBitmap.recycle();
    }

    void applyWallpaperBackdrops() {
        if (host.root != null && host.layout != null) {
            if (host.layout.tabletLandscape) {
                if (host.mainGapBacking == null) {
                    host.mainGapBacking = new View(host.ctx);
                    host.mainGapBacking.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    host.mainGapBacking.setBackground(HyperOsStyle.topBackground(host.ctx));
                    host.root.addView(host.mainGapBacking);
                }
                int[] bounds = host.layout.mainGapBounds();
                FrameLayout.LayoutParams gap = new FrameLayout.LayoutParams(bounds[2]-bounds[0], bounds[3]-bounds[1]);
                gap.leftMargin = bounds[0];
                gap.topMargin = bounds[1];
                host.mainGapBacking.setLayoutParams(gap);
                host.mainGapBacking.setAlpha(1f);
                View backing = host.sideBackingViews[WorkspaceHost.SLOT_TOP];
                if (backing == null) {
                    backing = new View(host.ctx);
                    backing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    host.sideBackingViews[WorkspaceHost.SLOT_TOP] = backing;
                    host.root.addView(backing, 0);
                }
                int[] side = host.layout.interactiveBounds();
                FrameLayout.LayoutParams sideParams = new FrameLayout.LayoutParams(side[2]-side[0], side[3]-side[1]);
                sideParams.leftMargin=side[0];sideParams.topMargin=side[1];
                backing.setLayoutParams(sideParams);
            } else if (host.mainGapBacking != null) {
                host.root.removeView(host.mainGapBacking);
                host.mainGapBacking = null;
            }
            if (!host.layout.tabletLandscape && host.sideBackingViews[WorkspaceHost.SLOT_TOP] != null) {
                host.root.removeView(host.sideBackingViews[WorkspaceHost.SLOT_TOP]);
                host.sideBackingViews[WorkspaceHost.SLOT_TOP] = null;
            }
        }
        updateWorkspaceBackdropVisibility();
        if (host.wallpaperBackdrop == null || host.wallpaperBackdrop.isRecycled() || host.layout == null) return;
        if (host.workspaceBackdrop != null) host.workspaceBackdrop.replaceWallpaper(host.wallpaperBackdrop,
                HyperOsStyle.topWallpaperTint(host.ctx));
        if (host.mainGapBacking != null) {
            int[] bounds = host.layout.mainGapBounds();
            host.mainGapBacking.setBackground(new WallpaperCropDrawable(host.wallpaperBackdrop, host.screenW, host.screenH,
                    bounds[0], bounds[1], HyperOsStyle.topWallpaperTint(host.ctx)));
        }
        if (host.sideBackingViews[WorkspaceHost.SLOT_TOP] != null) {
            int[] bounds = host.layout.interactiveBounds();
            host.sideBackingViews[WorkspaceHost.SLOT_TOP].setBackground(new WallpaperCropDrawable(host.wallpaperBackdrop, host.screenW, host.screenH,
                    bounds[0], bounds[1], HyperOsStyle.topWallpaperTint(host.ctx)));
        }
        if (host.topBar != null) host.topBar.setWallpaperBackdrop(host.wallpaperBackdrop, host.screenW, host.screenH);
        if (host.topStripBacking != null) {
            host.topStripBacking.setBackground(new WallpaperCropDrawable(host.wallpaperBackdrop, host.screenW, host.screenH,
                    0, 0, HyperOsStyle.topWallpaperTint(host.ctx)));
        }
        Slot upper=host.slots[WorkspaceHost.SLOT_TOP];
        if(upper!=null && upper.hint!=null && !upper.occupied)
            upper.hint.setBackground(new WallpaperCropDrawable(host.wallpaperBackdrop,host.screenW,host.screenH,
                    host.windowLayout.topGeometry().left,host.windowLayout.topGeometry().top,HyperOsStyle.topWallpaperTint(host.ctx)));
        int x = host.layout.mainOnRight ? 0 : host.layout.mainWidth;
        for (int i = 1; i < host.CELL_SLOT_COUNT; i++) {
            View backing = host.sideBackingViews[i];
            if (backing != null) backing.setBackground(new WallpaperCropDrawable(host.wallpaperBackdrop, host.screenW, host.screenH,
                    host.layout.tabletLandscape?host.layout.cellBounds(i-1)[0]:x,
                    host.chrome.sideSegmentTop(i), HyperOsStyle.topWallpaperTint(host.ctx)));
        }
        for (int i = 1; i < host.slots.length; i++) {
            if (i == WorkspaceHost.SLOT_TOP) continue;
            Slot slot = host.slots[i];
            if (slot != null && host.cellSurfaces.presentsSlot(slot)) refreshEmptyHintBackdrop(slot);
        }
        updateWorkspaceBackdropVisibility();
    }

    void refreshEmptyHintBackdrop(Slot slot) {
        if (slot.hint == null || slot.rect == null || (slot.occupied && !slot.parked)
                || host.wallpaperBackdrop == null || host.wallpaperBackdrop.isRecycled()) return;
        int x = slot.rect[0], y = slot.rect[1];
        int tint = HyperOsStyle.topWallpaperTint(host.ctx);
        if (slot.hint.getBackground() instanceof WallpaperCropDrawable) {
            WallpaperCropDrawable current = (WallpaperCropDrawable) slot.hint.getBackground();
            if (current.matchesWallpaper(host.wallpaperBackdrop, host.screenW, host.screenH, tint)) {
                current.setScreenOrigin(x, y);
                current.setWellWallpaper(host.wellWallpaperBackdrop);
                return;
            }
        }
        WallpaperCropDrawable backdrop = new WallpaperCropDrawable(
                host.wallpaperBackdrop, host.screenW, host.screenH, x, y, tint);
        backdrop.setWellWallpaper(host.wellWallpaperBackdrop);
        if (host.layout != null) backdrop.setMainHole(host.windowPresentation.workspaceMainHole());
        slot.hint.setBackground(backdrop);
    }

    void updateWorkspaceBackdropVisibility() {
        if (host.layout == null) return;
        // Wallpaper can arrive during a task handoff; do not rewrite its Surface alpha.
        android.graphics.RectF hole = host.windowPresentation.workspaceMainHole();
        host.chrome.updateWorkspaceBacking(host.topStripBacking, hole);
        host.chrome.updateWorkspaceBacking(host.mainGapBacking, hole);
        for (int i=1;i<WorkspaceHost.SLOT_COUNT;i++) {
            View backing=host.sideBackingViews[i];
            host.chrome.updateWorkspaceBacking(backing, hole);
            Slot slot=host.slots[i];
            if (slot!=null && slot.hint!=null && slot.hint.getBackground() instanceof WallpaperCropDrawable)
                ((WallpaperCropDrawable) slot.hint.getBackground()).setMainHole(hole);
        }
    }

    void registerWallpaperReceiver() {
        if (host.wallpaperReceiver != null) return;
        try {
            host.wallpaperReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    log.accept("wallpaper changed; reloading blur backdrop");
                    invalidateWallpaperBackdrop();
                }
            };
            host.ctx.registerReceiver(host.wallpaperReceiver, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
            log.accept("wallpaper change receiver registered");
        } catch (Throwable error) {
            log.accept("wallpaper receiver failed: " + error);
        }
        try {
            host.ctx.getContentResolver().registerContentObserver(WorkspaceBackground.URI, false,
                    new android.database.ContentObserver(host.main) {
                        @Override public void onChange(boolean selfChange) { invalidateWallpaperBackdrop(); }
                    });
        } catch (RuntimeException unavailable) {
            log.accept("workspace background observer unavailable: " + unavailable);
        }
    }

    void invalidateWallpaperBackdrop() {
        host.wallpaperBackdropDirty = true;
        ++host.wallpaperLoadGeneration;
        host.wallpaperBackdropRequested = false;
        host.wallpaperRetryAttempt = 0;
        host.main.removeCallbacks(wallpaperRetry);
        if ((host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING) && host.screenOn && !host.keyguardLocked)
            loadWallpaperBackdrop();
    }
}
