package io.github.zerohomers0.onesteplite;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;

/** Schedules and applies memory trimming while the workspace is dormant. */
final class WorkspaceMemoryController {
    private static final long DORMANT_TRIM_MS = 5L * 60L * 1000L;
    private final WorkspacePorts.ScenePort scenes;
    private final WorkspacePorts.FramePort frames;
    private final WorkspacePorts.BackdropPort backdrops;
    private final WorkspacePorts.WallpaperPort wallpapers;
    private final WorkspacePorts.TopPort top;
    private final WorkspacePorts.PresentationPort presentation;
    private final WorkspacePorts.TaskPort tasks;
    private final Runnable dormantTrim = this::trimSuspended;

    WorkspaceMemoryController(WorkspacePorts.ScenePort scenes, WorkspacePorts.FramePort frames,
            WorkspacePorts.BackdropPort backdrops, WorkspacePorts.WallpaperPort wallpapers,
            WorkspacePorts.TopPort top, WorkspacePorts.PresentationPort presentation,
            WorkspacePorts.TaskPort tasks) {
        this.scenes=scenes;
        this.frames=frames;
        this.backdrops=backdrops;
        this.wallpapers=wallpapers;
        this.top=top;
        this.presentation=presentation;
        this.tasks=tasks;
    }

    void trim(int level) {
        if (level < TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) return;
        if (WorkspaceWindowLayout.hostSceneActive() || scenes.retainedForLock()
                || tasks.promotion() != null || tasks.transferBusy()) return;
        if (scenes.scene() == WorkspaceHost.ACTIVE || scenes.scene() == WorkspaceHost.RESUMING) return;
        if (presentation.wallpaperBackdropRequested() && level < TRIM_MEMORY_RUNNING_CRITICAL) return;
        if (top.topBar() != null) top.topBar().trimDormantMemory();
        releaseDormantBackdrops();
        Warmup.onTrimMemory(TRIM_MEMORY_UI_HIDDEN);
        WorkspaceHost.log("dormant memory trimmed level=" + level);
    }

    void schedule() {
        cancel();
        frames.main().postDelayed(dormantTrim, DORMANT_TRIM_MS);
    }

    void cancel() {
        frames.main().removeCallbacks(dormantTrim);
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
        wallpapers.wallpapers().cancelPending();
        wallpapers.windowPresentation().releaseWorkspaceBackdrop();
        if (backdrops.mainBackdrop() != null && !backdrops.mainBackdrop().isRecycled())
            backdrops.mainBackdrop().recycle();
        backdrops.mainBackdrop(null);
        if (backdrops.wallpaperBackdrop() != null && !backdrops.wallpaperBackdrop().isRecycled())
            backdrops.wallpaperBackdrop().recycle();
        backdrops.wallpaperBackdrop(null);
        if (backdrops.wellWallpaperBackdrop() != null && !backdrops.wellWallpaperBackdrop().isRecycled())
            backdrops.wellWallpaperBackdrop().recycle();
        backdrops.wellWallpaperBackdrop(null);
        wallpapers.mainSideBackdrop(null);
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