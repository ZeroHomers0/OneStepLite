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

    interface FramePort {
        Handler main();
        WorkspaceLayout layout();
        WorkspaceLiveGeometry liveGeometry();
        WorkspaceWindowLayout windowLayout();
        int screenWidth();
        int screenHeight();
        int displayDensityDpi();
    }

    interface BackdropPort {
        Bitmap mainBackdrop();
        void mainBackdrop(Bitmap value);
        Bitmap wallpaperBackdrop();
        void wallpaperBackdrop(Bitmap value);
        Bitmap wellWallpaperBackdrop();
        void wellWallpaperBackdrop(Bitmap value);
    }

    interface WallpaperPort {
        WorkspaceWallpaperController wallpapers();
        WorkspaceWindowPresentation windowPresentation();
        void mainSideBackdrop(WallpaperCropDrawable value);
    }

    interface TopPort {
        TopBar topBar();
        boolean topFace();
        boolean topPortrait();
        Object topTransition();
    }

    interface PresentationPort {
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
        void releaseHiddenRetainedTasks();
    }



    interface CellLifecyclePort {
        WorkspaceCellMotionController cells();
        Promotion promotion();
        WorkspaceScaleController scales();
        WorkspaceSlotRegistry slotRegistry();
        WorkspaceSideRowController sideRows();
        boolean taskCellsCovered();
        boolean topFace();
        float workspaceAlpha();
    }

    interface CellLayerPort {
        WorkspaceWindowPresentation windowPresentation();
        boolean topTransitionOwnsTopOpacity();
        boolean topTransitionOwnsTaskOpacity();
        boolean wallpaperBackdropPresent();
        WorkspaceWallpaperController wallpapers();
        boolean rootReady();
        android.content.Context context();
    }

    interface DebugStatePort {
        Handler main();
        int scene();
        boolean screenOn();
        boolean keyguardLocked();
        Promotion promotion();
        boolean transferBusy();
    }

    interface DebugTaskPort {
        WorkspaceLaunchController launches();
        WorkspaceMainTaskActions mainTaskActions();
        WorkspaceCarrierCoordinator carriers();
        WorkspaceSlotRegistry slotRegistry();
        Slot[] slots();
        boolean executeOnShell(Runnable task);
    }

    interface DebugSurfacePort {
        android.content.Context context();
        WorkspaceLayout layout();
        WorkspaceSideRowController sideRows();
        WorkspaceSceneTracker sceneTracker();
        int screenWidth();
        int screenHeight();
        int displayRotation();
        FrameLayout root();
    }

    interface DebugPorts {
        DebugStatePort state();
        DebugTaskPort tasks();
        DebugSurfacePort surfaces();
    }

    interface Runtime {
        ScenePort scenes();
        SlotPort slots();
        FramePort frames();
        BackdropPort backdrops();
        WallpaperPort wallpapers();
        TopPort top();
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
        FramePort frames=new FramePort() {
            @Override public Handler main() { return host.main; }
            @Override public WorkspaceLayout layout() { return host.layout; }
            @Override public WorkspaceLiveGeometry liveGeometry() { return host.liveGeometry; }
            @Override public WorkspaceWindowLayout windowLayout() { return host.windowLayout; }
            @Override public int screenWidth() { return host.screenW; }
            @Override public int screenHeight() { return host.screenH; }
            @Override public int displayDensityDpi() { return host.displayDensityDpi; }
        };
        BackdropPort backdrops=new BackdropPort() {
            @Override public Bitmap mainBackdrop() { return host.mainBackdrop; }
            @Override public void mainBackdrop(Bitmap value) { host.mainBackdrop=value; }
            @Override public Bitmap wallpaperBackdrop() { return host.wallpaperBackdrop; }
            @Override public void wallpaperBackdrop(Bitmap value) { host.wallpaperBackdrop=value; }
            @Override public Bitmap wellWallpaperBackdrop() { return host.wellWallpaperBackdrop; }
            @Override public void wellWallpaperBackdrop(Bitmap value) { host.wellWallpaperBackdrop=value; }
        };
        WallpaperPort wallpapers=new WallpaperPort() {
            @Override public WorkspaceWallpaperController wallpapers() { return host.wallpapers; }
            @Override public WorkspaceWindowPresentation windowPresentation() { return host.windowPresentation; }
            @Override public void mainSideBackdrop(WallpaperCropDrawable value) { host.mainSideBackdrop=value; }
        };
        TopPort top=new TopPort() {
            @Override public TopBar topBar() { return host.topBar; }
            @Override public boolean topFace() { return host.topFace; }
            @Override public boolean topPortrait() { return host.topPortrait; }
            @Override public Object topTransition() { return host.topTransition; }
        };
        PresentationPort presentation=new PresentationPort() {
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
            @Override public void releaseHiddenRetainedTasks() { host.exits.releaseHiddenRetainedTasksForMemory(); }
        };
        return new Runtime() {
            @Override public ScenePort scenes() { return scenes; }
            @Override public SlotPort slots() { return slots; }
            @Override public FramePort frames() { return frames; }
            @Override public BackdropPort backdrops() { return backdrops; }
            @Override public WallpaperPort wallpapers() { return wallpapers; }
            @Override public TopPort top() { return top; }
            @Override public PresentationPort presentation() { return presentation; }
            @Override public TaskPort tasks() { return tasks; }
        };
    }


    static CellLifecyclePort cellLifecycle(WorkspaceHost host) {
        return new CellLifecyclePort() {
            @Override public WorkspaceCellMotionController cells() { return host.cells; }
            @Override public Promotion promotion() { return host.promotion; }
            @Override public WorkspaceScaleController scales() { return host.scales; }
            @Override public WorkspaceSlotRegistry slotRegistry() { return host.slotRegistry; }
            @Override public WorkspaceSideRowController sideRows() { return host.sideRows; }
            @Override public boolean taskCellsCovered() { return host.taskCellsCovered; }
            @Override public boolean topFace() { return host.topFace; }
            @Override public float workspaceAlpha() { return host.workspaceAlpha(); }
        };
    }

    static CellLayerPort cellLayers(WorkspaceHost host) {
        return new CellLayerPort() {
            @Override public WorkspaceWindowPresentation windowPresentation() { return host.windowPresentation; }
            @Override public boolean topTransitionOwnsTopOpacity() {
                return host.topTransition != null && host.topTransition.ownsTopOpacity();
            }
            @Override public boolean topTransitionOwnsTaskOpacity() {
                return host.topTransition != null && host.topTransition.ownsTaskOpacity();
            }
            @Override public boolean wallpaperBackdropPresent() { return host.wallpaperBackdrop != null; }
            @Override public WorkspaceWallpaperController wallpapers() { return host.wallpapers; }
            @Override public boolean rootReady() { return host.root != null; }
            @Override public android.content.Context context() { return host.ctx; }
        };
    }

    static DebugPorts debug(WorkspaceHost host) {
        DebugStatePort state=new DebugStatePort() {
            @Override public Handler main() { return host.main; }
            @Override public int scene() { return host.scene; }
            @Override public boolean screenOn() { return host.screenOn; }
            @Override public boolean keyguardLocked() { return host.keyguardLocked; }
            @Override public Promotion promotion() { return host.promotion; }
            @Override public boolean transferBusy() { return host.transferBusy; }
        };
        DebugTaskPort tasks=new DebugTaskPort() {
            @Override public WorkspaceLaunchController launches() { return host.launches; }
            @Override public WorkspaceMainTaskActions mainTaskActions() { return host.mainTaskActions; }
            @Override public WorkspaceCarrierCoordinator carriers() { return host.carriers; }
            @Override public WorkspaceSlotRegistry slotRegistry() { return host.slotRegistry; }
            @Override public Slot[] slots() { return host.slots; }
            @Override public boolean executeOnShell(Runnable task) { return host.executeOnShell(task); }
        };
        DebugSurfacePort surfaces=new DebugSurfacePort() {
            @Override public android.content.Context context() { return host.ctx; }
            @Override public WorkspaceLayout layout() { return host.layout; }
            @Override public WorkspaceSideRowController sideRows() { return host.sideRows; }
            @Override public WorkspaceSceneTracker sceneTracker() { return host.sceneTracker; }
            @Override public int screenWidth() { return host.screenW; }
            @Override public int screenHeight() { return host.screenH; }
            @Override public int displayRotation() { return host.displayRotation; }
            @Override public FrameLayout root() { return host.root; }
        };
        return new DebugPorts() {
            @Override public DebugStatePort state() { return state; }
            @Override public DebugTaskPort tasks() { return tasks; }
            @Override public DebugSurfacePort surfaces() { return surfaces; }
        };
    }
    private WorkspacePorts() {}
}