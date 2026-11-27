package io.github.zerohomers0.onesteplite;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;

/** Schedules and applies memory trimming while the workspace is dormant. */
final class WorkspaceMemoryController {
    private static final long DORMANT_TRIM_MS = 5L * 60L * 1000L;
    private final WorkspaceHost host;
    private final Runnable dormantTrim = this::trimSuspended;

    WorkspaceMemoryController(WorkspaceHost host) {
        this.host = host;
    }

    void trim(int level) {
        if (level < TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) return;
        if (WorkspaceWindowLayout.hostSceneActive() || host.retainedForLock
                || host.promotion != null || host.transferBusy) return;
        if (host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING) return;
        if (host.wallpaperBackdropRequested && level < TRIM_MEMORY_RUNNING_CRITICAL) return;
        if (host.topBar != null) host.topBar.trimDormantMemory();
        releaseDormantBackdrops();
        Warmup.onTrimMemory(TRIM_MEMORY_UI_HIDDEN);
        WorkspaceHost.log("dormant memory trimmed level=" + level);
    }

    void schedule() {
        cancel();
        host.main.postDelayed(dormantTrim, DORMANT_TRIM_MS);
    }

    void cancel() {
        host.main.removeCallbacks(dormantTrim);
    }

    private void trimSuspended() {
        if (host.scene == WorkspaceHost.SUSPENDED && !host.retainedForLock
                && !WorkspaceWindowLayout.hostSceneActive()) {
            trim(TRIM_MEMORY_UI_HIDDEN);
        }
    }

    static void onTrimMemory(int level) {
        WorkspaceHost h=WorkspaceHost.currentInstance();
        if(h==null)return;
        h.main.post(() -> h.memory.trim(level));
    }

    void releaseDormantBackdrops() {
        host.wallpapers.cancelPending();
        host.windowPresentation.releaseWorkspaceBackdrop();
        if (host.mainBackdrop != null && !host.mainBackdrop.isRecycled()) host.mainBackdrop.recycle();
        host.mainBackdrop = null;
        if (host.wallpaperBackdrop != null && !host.wallpaperBackdrop.isRecycled()) host.wallpaperBackdrop.recycle();
        host.wallpaperBackdrop = null;
        if (host.wellWallpaperBackdrop != null && !host.wellWallpaperBackdrop.isRecycled()) host.wellWallpaperBackdrop.recycle();
        host.wellWallpaperBackdrop = null;
        host.mainSideBackdrop = null;
        host.wallpaperBackdropDirty = true;
        host.wallpaperBackdropRequested = false;
    }

    private void scheduleDormantTrim() {
        schedule();
    }

    void cancelDormantTrim() {
        cancel();
    }
}
