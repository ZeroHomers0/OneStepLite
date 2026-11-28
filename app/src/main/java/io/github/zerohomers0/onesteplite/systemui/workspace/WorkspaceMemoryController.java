package io.github.zerohomers0.onesteplite;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;

/** Schedules and applies memory trimming while the workspace is dormant. */
final class WorkspaceMemoryController {
    private static final long DORMANT_TRIM_MS = 5L * 60L * 1000L;
    private final WorkspacePorts.ScenePort scenes;
    private final WorkspacePorts.SurfacePort surfaces;
    private final WorkspacePorts.PresentationPort presentation;
    private final WorkspacePorts.TaskPort tasks;
    private final Runnable dormantTrim = this::trimSuspended;

    WorkspaceMemoryController(WorkspacePorts.ScenePort scenes,
            WorkspacePorts.SurfacePort surfaces,
            WorkspacePorts.PresentationPort presentation,
            WorkspacePorts.TaskPort tasks) {
        this.scenes=scenes;
        this.surfaces=surfaces;
        this.presentation=presentation;
        this.tasks=tasks;
    }

    void trim(int level) {
        if (level < TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) return;
        if (WorkspaceWindowLayout.hostSceneActive() || scenes.retainedForLock()
                || tasks.promotion() != null || tasks.transferBusy()) return;
        if (scenes.scene() == WorkspaceHost.ACTIVE || scenes.scene() == WorkspaceHost.RESUMING) return;
        if (presentation.wallpaperBackdropRequested() && level < TRIM_MEMORY_RUNNING_CRITICAL) return;
        if (presentation.topBar() != null) presentation.topBar().trimDormantMemory();
        releaseDormantBackdrops();
        Warmup.onTrimMemory(TRIM_MEMORY_UI_HIDDEN);
        WorkspaceHost.log("dormant memory trimmed level=" + level);
    }

    void schedule() {
        cancel();
        surfaces.main().postDelayed(dormantTrim, DORMANT_TRIM_MS);
    }

    void cancel() {
        surfaces.main().removeCallbacks(dormantTrim);
    }

    private void trimSuspended() {
        if (scenes.scene() == WorkspaceHost.SUSPENDED && !scenes.retainedForLock()
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
        surfaces.wallpapers().cancelPending();
        surfaces.windowPresentation().releaseWorkspaceBackdrop();
        if (surfaces.mainBackdrop() != null && !surfaces.mainBackdrop().isRecycled())
            surfaces.mainBackdrop().recycle();
        surfaces.mainBackdrop(null);
        if (surfaces.wallpaperBackdrop() != null && !surfaces.wallpaperBackdrop().isRecycled())
            surfaces.wallpaperBackdrop().recycle();
        surfaces.wallpaperBackdrop(null);
        if (surfaces.wellWallpaperBackdrop() != null && !surfaces.wellWallpaperBackdrop().isRecycled())
            surfaces.wellWallpaperBackdrop().recycle();
        surfaces.wellWallpaperBackdrop(null);
        surfaces.mainSideBackdrop(null);
        presentation.wallpaperBackdropDirty(true);
        presentation.wallpaperBackdropRequested(false);
    }

    private void scheduleDormantTrim() {
        schedule();
    }

    void cancelDormantTrim() {
        cancel();
    }
}