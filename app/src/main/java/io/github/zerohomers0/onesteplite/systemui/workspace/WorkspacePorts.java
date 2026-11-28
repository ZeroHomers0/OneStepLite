package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.os.Handler;
import android.widget.FrameLayout;
import java.util.Map;

/** Narrow live capabilities exposed to workspace collaborators instead of the concrete Host. */
final class WorkspacePorts {
    interface ScenePort {
        int scene();
        long sceneEpoch();
        boolean screenOn();
        boolean keyguardLocked();
        boolean retainedForLock();
        boolean taskCellsCovered();
    }

    interface SlotPort {
        Slot[] slots();
        int cellSlotCount();
        WorkspaceSlotRegistry slotRegistry();
        WorkspaceSideRowController sideRows();
        Map<Object,OwnedTask> ownedTasks();
    }

    interface SurfacePort {
        Handler main();
        WorkspaceLayout layout();
        WorkspaceLiveGeometry liveGeometry();
        WorkspaceWindowLayout windowLayout();
        int screenWidth();
        int screenHeight();
        int displayDensityDpi();
        WorkspaceWallpaperController wallpapers();
        WorkspaceWindowPresentation windowPresentation();
        Bitmap mainBackdrop();
        void mainBackdrop(Bitmap value);
        Bitmap wallpaperBackdrop();
        void wallpaperBackdrop(Bitmap value);
        Bitmap wellWallpaperBackdrop();
        void wellWallpaperBackdrop(Bitmap value);
        void mainSideBackdrop(WallpaperCropDrawable value);
    }

    interface PresentationPort {
        TopBar topBar();
        boolean topFace();
        boolean topPortrait();
        Object topTransition();
        boolean wallpaperBackdropRequested();
        void wallpaperBackdropRequested(boolean value);
        WorkspaceOptions options();
        FrameLayout root();
        WorkspaceSideInteraction sideInteraction();
        boolean wallpaperBackdropDirty();
        void wallpaperBackdropDirty(boolean value);
    }

    interface TaskPort {
        Promotion promotion();
        boolean transferBusy();
        WorkspaceScaleController scales();
    }

    interface Runtime {
        ScenePort scenes();
        SlotPort slots();
        SurfacePort surfaces();
        PresentationPort presentation();
        TaskPort tasks();
    }

    static Runtime of(WorkspaceHost host) {
        ScenePort scenes=new ScenePort() {
            @Override public int scene() { return host.scene; }
            @Override public long sceneEpoch() { return host.sceneEpoch; }
            @Override public boolean screenOn() { return host.screenOn; }
            @Override public boolean keyguardLocked() { return host.keyguardLocked; }
            @Override public boolean retainedForLock() { return host.retainedForLock; }
            @Override public boolean taskCellsCovered() { return host.taskCellsCovered; }
        };
        SlotPort slots=new SlotPort() {
            @Override public Slot[] slots() { return host.slots; }
            @Override public int cellSlotCount() { return host.CELL_SLOT_COUNT; }
            @Override public WorkspaceSlotRegistry slotRegistry() { return host.slotRegistry; }
            @Override public WorkspaceSideRowController sideRows() { return host.sideRows; }
            @Override public Map<Object,OwnedTask> ownedTasks() { return host.ownedTasks; }
        };
        SurfacePort surfaces=new SurfacePort() {
            @Override public Handler main() { return host.main; }
            @Override public WorkspaceLayout layout() { return host.layout; }
            @Override public WorkspaceLiveGeometry liveGeometry() { return host.liveGeometry; }
            @Override public WorkspaceWindowLayout windowLayout() { return host.windowLayout; }
            @Override public int screenWidth() { return host.screenW; }
            @Override public int screenHeight() { return host.screenH; }
            @Override public int displayDensityDpi() { return host.displayDensityDpi; }
            @Override public WorkspaceWallpaperController wallpapers() { return host.wallpapers; }
            @Override public WorkspaceWindowPresentation windowPresentation() { return host.windowPresentation; }
            @Override public Bitmap mainBackdrop() { return host.mainBackdrop; }
            @Override public void mainBackdrop(Bitmap value) { host.mainBackdrop=value; }
            @Override public Bitmap wallpaperBackdrop() { return host.wallpaperBackdrop; }
            @Override public void wallpaperBackdrop(Bitmap value) { host.wallpaperBackdrop=value; }
            @Override public Bitmap wellWallpaperBackdrop() { return host.wellWallpaperBackdrop; }
            @Override public void wellWallpaperBackdrop(Bitmap value) { host.wellWallpaperBackdrop=value; }
            @Override public void mainSideBackdrop(WallpaperCropDrawable value) { host.mainSideBackdrop=value; }
        };
        PresentationPort presentation=new PresentationPort() {
            @Override public TopBar topBar() { return host.topBar; }
            @Override public boolean topFace() { return host.topFace; }
            @Override public boolean topPortrait() { return host.topPortrait; }
            @Override public Object topTransition() { return host.topTransition; }
            @Override public boolean wallpaperBackdropRequested() { return host.wallpaperBackdropRequested; }
            @Override public void wallpaperBackdropRequested(boolean value) { host.wallpaperBackdropRequested=value; }
            @Override public WorkspaceOptions options() { return host.workspaceOptions; }
            @Override public FrameLayout root() { return host.root; }
            @Override public WorkspaceSideInteraction sideInteraction() { return host.sideInteraction; }
            @Override public boolean wallpaperBackdropDirty() { return host.wallpaperBackdropDirty; }
            @Override public void wallpaperBackdropDirty(boolean value) { host.wallpaperBackdropDirty=value; }
        };
        TaskPort tasks=new TaskPort() {
            @Override public Promotion promotion() { return host.promotion; }
            @Override public boolean transferBusy() { return host.transferBusy; }
            @Override public WorkspaceScaleController scales() { return host.scales; }
        };
        return new Runtime() {
            @Override public ScenePort scenes() { return scenes; }
            @Override public SlotPort slots() { return slots; }
            @Override public SurfacePort surfaces() { return surfaces; }
            @Override public PresentationPort presentation() { return presentation; }
            @Override public TaskPort tasks() { return tasks; }
        };
    }
    private WorkspacePorts() {}
}