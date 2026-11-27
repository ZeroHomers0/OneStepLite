package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import android.widget.FrameLayout;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Composes one workspace instance and wires its scene, task, window, and presentation collaborators. */
public final class WorkspaceHost {
    static final String LANDSCAPE_MODE_ACTION = Actions.LANDSCAPE_MODE;

    private static final String TAG = Tags.PROBE;
    static final int SLOT_MAIN = 0;
    int CELL_SLOT_COUNT = 1 + WorkspaceLayout.CELL_COUNT;
    static final int SLOT_TOP = 1 + WorkspaceLayout.MAX_CELL_COUNT;
    static final int SLOT_COUNT = SLOT_TOP + 1;
    FrameLayout tabletTools;
    boolean tabletToolsOpen;
    boolean taskCellsCovered;
    static final String TOP_TASK_STATE_ACTION = Actions.TOP_TASK_STATE;
    static final long SIDE_PROMOTION_TIMEOUT_MS = 3500L;

    static volatile WorkspaceHost sInstance;
    static WorkspaceHost currentInstance() { return sInstance; }

    static boolean isCurrentInstance(WorkspaceHost host) { return sInstance == host; }
    final WorkspaceDebugController debug = new WorkspaceDebugController(this);

    final Context ctx;
    final Executor executor;
    final Object factory;
    final Object transitions;
    final Handler main = new Handler(Looper.getMainLooper());

    volatile Slot[] slots = new Slot[SLOT_COUNT];
    final WorkspaceSideRowController sideRows = new WorkspaceSideRowController(this);
    final WorkspaceCellSurfaceController cellSurfaces = new WorkspaceCellSurfaceController(this);
    final WorkspaceChromeController chrome = new WorkspaceChromeController(this);
    final WorkspaceExitController exits = new WorkspaceExitController(this);
    final WorkspaceResumeController resumes = new WorkspaceResumeController(this);
    final WorkspaceLaunchController launches = new WorkspaceLaunchController(this);
    final WorkspaceTaskStateController taskStates = new WorkspaceTaskStateController(this);
    final WorkspaceSideCloseController sideCloses = new WorkspaceSideCloseController(this);
    final WorkspaceScaleController scales = new WorkspaceScaleController(this);
    final WorkspaceCellMotionController cells = new WorkspaceCellMotionController(this);
    final WorkspaceSlotRegistry slotRegistry = new WorkspaceSlotRegistry(this, WorkspaceHost::log);
    final WorkspaceMemoryController memory = new WorkspaceMemoryController(this);
    final WorkspaceMainSideController mainSide = new WorkspaceMainSideController(this);

    volatile WorkspaceLayout layout;
    FrameLayout root;
    WindowManager wm;
    WindowManager.LayoutParams lp;
    DisplayManager displayManager;
    DisplayManager.DisplayListener displayListener;
    Boolean defaultDisplayOn;
    volatile int displayRotation = -1;
    boolean geometryRefreshQueued;
    int geometryRefreshDeferrals;
    boolean geometryRefreshAfterLive;
    boolean zoomed;
    volatile int screenW = 1080;
    volatile int screenH = 2460;
    volatile int displayDensityDpi = 320;
    volatile boolean screenOn = true;
    Promotion promotion;
    final StatusBar statusBar;
    volatile HomeTaskReturn coveredHome;
    CellMovePreview cellMovePreview;
    Bitmap mainBackdrop;
    View topStripBacking;
    View mainGapBacking;
    Bitmap wallpaperBackdrop;
    Bitmap wellWallpaperBackdrop;
    boolean wallpaperBackdropRequested;
    boolean wallpaperBackdropDirty;
    long wallpaperLoadGeneration;
    int wallpaperRetryAttempt;
    BroadcastReceiver wallpaperReceiver;
    UnlockHandoff unlockHandoff;
    final FinishTransactions<Long> keyguardTransactions = new FinishTransactions<>(16);
    final UnlockGeometryReplay unlockGeometryReplay = new UnlockGeometryReplay();
    final UnlockFinishGate unlockFinishGate = new UnlockFinishGate();
    final FinishTransactions<Long> unlockFinishTransactions = new FinishTransactions<>(16);
    View carrierBackdrop;
    int carrierSlotIndex = -1;
    TopBar topBar;
    volatile WorkspaceOptions workspaceOptions = WorkspaceOptions.DEFAULT;
    WorkspaceSettingsClient workspaceSettings;
    FrameLayout topLiveClip;
    VideoPane videoPane;
    final TopTaskState topTaskState = new TopTaskState();
    final java.util.Map<TopTaskState.Key, TopDirection> topDirections = new java.util.HashMap<>();
    Slot topOriginalSlot;
    TopCandidate tabletCoveredMain;
    TopTaskState.Key tabletReturnedTop;
    boolean topFromMain;
    boolean topFace;
    boolean topPortrait;
    volatile TopTaskTransition topTransition;

    final View[] sideBackingViews = new View[SLOT_COUNT];
    boolean mainOnRight;
    volatile boolean keyguardLocked;
    volatile boolean retainedForLock;
    boolean lockExitPending;
    boolean retainedGeometryConfirmed;
    boolean retainedWindowPrepared;
    long retainedWindowGeneration;
    volatile boolean keyguardWindowHidden;
    int keyguardResumeIndex = -1;
    volatile boolean retainedOnExit;
    long retainedExitSourceEpoch = -1;
    String pendingSuspendReason;
    static final int ACTIVE = WorkspaceSceneTracker.ACTIVE, SUSPENDING = WorkspaceSceneTracker.SUSPENDING,
            SUSPENDED = WorkspaceSceneTracker.SUSPENDED, RESUMING = WorkspaceSceneTracker.RESUMING;
    volatile int scene = ACTIVE;
    final WorkspaceSceneTracker sceneTracker = new WorkspaceSceneTracker();
    final WorkspaceLayoutCache layoutCache = new WorkspaceLayoutCache();
    long sceneEpoch;
    boolean suspendRequested;
    final NavigationPolicy.EmptyMainGesture emptyMainGesture =
            new NavigationPolicy.EmptyMainGesture();
    boolean landscapeSuspended;
    boolean transferBusy;
    static boolean boundsHooksReady;
    SideDismiss sideDismiss;
    final WorkspaceLiveHandoffController liveHandoff = new WorkspaceLiveHandoffController(this);
    final WorkspacePromotionController promotionController = new WorkspacePromotionController(this);
    final WorkspaceTopPresentationController topPresentationHost = new WorkspaceTopPresentationController(this);
    final WorkspaceTopAdmissionController topAdmission = new WorkspaceTopAdmissionController(this);
    final WorkspaceTaskTransferController taskTransfer = new WorkspaceTaskTransferController(this);
    final WorkspaceRetainedSurfaceHooks retainedSurfaces = new WorkspaceRetainedSurfaceHooks(this);
    final WorkspaceWindowPresentation windowPresentation = new WorkspaceWindowPresentation(this);
    final WorkspaceWindowLayout windowLayout = new WorkspaceWindowLayout(this);
    final WorkspaceKeyguardController keyguard = new WorkspaceKeyguardController(this);
    final WorkspaceMainLaunchController mainLaunch = new WorkspaceMainLaunchController(this);
    final WorkspaceMainTaskActions mainTaskActions = new WorkspaceMainTaskActions(this);
    final WorkspaceTransitionWindow transitionWindow = new WorkspaceTransitionWindow(this);
    final WorkspaceLiveGeometry liveGeometry = new WorkspaceLiveGeometry(this);
    final WorkspaceContentDragController contentDrag = new WorkspaceContentDragController(this);
    final WorkspaceSlotLifecycle slotLifecycle = new WorkspaceSlotLifecycle(this);
    final WorkspaceZoomSession zoomSession;
    final WorkspaceSideInteraction sideInteraction = new WorkspaceSideInteraction(this);
    volatile boolean liveSwapInputBlocked;
    TopPresentationController topPresentation;
    final android.os.IBinder desktopSceneOwner = new android.os.Binder();
    private boolean desktopPublishQueued;
    private final DesktopSideTaskPublisher desktopSideTaskPublisher =
            new DesktopSideTaskPublisher(new DesktopSideTaskPublisher.Host() {
        @Override public TopBar topBar() { return topBar; }
        @Override public int cellSlotCount() { return CELL_SLOT_COUNT; }
        @Override public Slot[] slots() { return slots; }
        @Override public int topSlot() { return SLOT_TOP; }
        @Override public long sceneEpoch() { return sceneEpoch; }
        @Override public boolean sceneActive() { return scene == ACTIVE; }
        @Override public boolean sceneSuspending() { return scene == SUSPENDING; }
        @Override public boolean sceneSuspended() { return scene == SUSPENDED; }
        @Override public boolean retainedOnExit() { return retainedOnExit; }
        @Override public boolean screenOn() { return screenOn; }
        @Override public boolean keyguardLocked() { return keyguardLocked; }
        @Override public FrameLayout root() { return root; }
        @Override public boolean isActive(Slot slot) { return WorkspaceHost.this.slotRegistry.isActive(slot); }
        @Override public boolean controllerOwns(Slot slot, int taskId, Object token) {
            return WorkspaceHost.this.slotRegistry.controllerOwns(slot, taskId, token);
        }
        @Override public boolean mayWriteSideSurface(Slot slot) {
            return WorkspaceHost.this.scales.mayWriteSideSurface(slot);
        }
        @Override public WorkspaceOptions options() { return workspaceOptions; }
        @Override public LiveTask findMainTask() throws Exception {
            return WorkspaceHost.this.slotRegistry.findMainTask();
        }
        @Override public Object rootInfo(LiveTask task) throws Exception {
            return WorkspaceHost.this.slotRegistry.rootInfo(task);
        }
        @Override public int windowModeOf(Object info) { return taskStates.windowModeOf(info); }
        @Override public TopTaskState topTaskState() { return topTaskState; }
        @Override public boolean topFace() { return topFace; }
        @Override public TopTaskGeometry topGeometry() { return WorkspaceHost.this.windowLayout.topGeometry(); }
        @Override public Rect topBounds() { return slotRegistry.logicalBounds(slots[SLOT_TOP]); }
        @Override public android.os.IBinder sceneOwner() { return desktopSceneOwner; }
        @Override public Context context() { return ctx; }
        @Override public boolean executeOnShell(Runnable task) {
            return WorkspaceHost.this.executeOnShell(task);
        }
    }, WorkspaceHost::log);
    final FinishTransactions<RenderBinding> finishTransactions = new FinishTransactions<>(16);
    final FinishTransactions<Long> workspaceFinishes = new FinishTransactions<>(16);

    final java.util.Map<Object, OwnedTask> ownedTasks = new java.util.HashMap<>();

    final FinishTransactions<NativeObservation> nativeObservations = new FinishTransactions<>(16);
    volatile NativeObservation pendingNativeObservation;

    NativeObservation observeNativeCommit(Object token, Runnable check) {
        NativeObservation observation = new NativeObservation(token, check, main,
                value -> { if (pendingNativeObservation == value) pendingNativeObservation = null; });
        pendingNativeObservation = observation;
        main.postDelayed(observation, 350L); // Fallback if the target omits the transition/commit callback.
        return observation;
    }

    void cancelNativeObservation(NativeObservation observation) {
        if (observation != null) observation.cancel();
    }

    private WorkspaceHost(Context ctx, Executor executor, Object factory, Object transitions) {
        // Keep the workspace on the physical portrait window configuration while
        // native SystemUI resources follow the main application's environment.
        Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        this.ctx = ctx.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        zoomSession = new WorkspaceZoomSession(this);
        zoomCommands=new WorkspaceZoomCommands(main::postDelayed,SystemClock::uptimeMillis,
                ()->zoomed,zoomSession::writeZoomLine,WorkspaceHost::log);
        this.executor = executor;
        this.factory = factory;
        this.transitions = transitions;
        statusBar = new StatusBar(main, WorkspaceHost::log);
        WorkspaceNativeHooks.install(ctx.getClassLoader());
        retainedSurfaces.installRetainedSurfaces(ctx.getClassLoader());
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(i);
        }
        workspaceSettings = new WorkspaceSettingsClient(this.ctx, main, options -> {
            boolean themeChanged=workspaceOptions.themeMode!=options.themeMode;
            HyperOsStyle.theme(options.themeMode);
            boolean sharingDisabled=workspaceOptions.dragShareEnabled && !options.dragShareEnabled;
            workspaceOptions = options;
            if(sharingDisabled){
                contentDrag.clearHighlight();
            }
            if (topBar != null) topBar.applyOptions(options);
            if (themeChanged && root!=null) wallpapers.applyWallpaperBackdrops();
            if(scene==ACTIVE && screenOn && !keyguardLocked)chrome.refreshQuickApps();
            if (root != null) {
                publishDesktopSideTasks();
            }
            cells.requestCompaction();
            if (!options.keepOnLock && retainedForLock) {
                // Settings may arrive while the physical screen is still locked.
                // Decide on authenticated wake, without returning tasks during fade-out.
                keyguard.leaveKeyguardPrivacy();
            }
            log("workspace settings applied swap=" + options.swapMode
                    + " keepOnLock=" + options.keepOnLock + " scrollSideTasks=" + options.scrollSideTasks);
        });
    }

    public static void start(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!EntryGuard.canOpen(ctx)) { log("host start rejected while locked"); return; }
        if (sInstance != null) {
            log("host already started");
            return;
        }
        sInstance = new WorkspaceHost(ctx, executor, factory, transitions);
        sInstance.startInternal();
    }

    public static void toggleOrStart(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!EntryGuard.canOpen(ctx)) { log("host gesture rejected while locked"); return; }
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
            return;
        }
        sInstance.resumes.toggleVisibility();
    }

    public static void toggleOrStartIfNeeded(Context ctx, Executor executor, Object factory,
            Object transitions) {
        if (!EntryGuard.canOpen(ctx)) return;
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
        }
    }

    public static void debugLaunch(int slot, String pkg) {
        WorkspaceHost h = sInstance;
        if (h == null) { log("debugLaunch: host not started"); return; }
        h.debug.debugLaunch(slot, pkg);
    }

    public static void debugSwap(int slot) {
        WorkspaceHost h = sInstance;
        if (h == null) { log("debugSwap: host not started"); return; }
        h.debug.debugSwap(slot);
    }

    public static void debugTraceNextSwap() {
        WorkspaceHost h = sInstance;
        if (h == null) { log("passive trace: host not started"); return; }
        h.debug.debugTraceNextSwap();
    }

    public static void debugMirror(int taskId, int x, int y, int w, int h, int holdMs,
            int hideMode) {
        WorkspaceHost host = sInstance;
        if (host == null) { log("debugMirror: host not started"); return; }
        host.debug.debugMirror(taskId, x, y, w, h, holdMs, hideMode);
    }

    static volatile int pendingMainSide = -1;

    public static void setPendingMainSide(boolean mainOnRight) {
        WorkspaceHost host = sInstance;
        // 已经打开时的滑动是“关闭”，不改变下一次打开的方向。
        if (host != null && host.scene == ACTIVE) return;
        pendingMainSide = mainOnRight ? 1 : 0;
    }

    void consumePendingMainSide() {
        carriers.consumePendingMainSide();
    }

    public static void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        WorkspaceHost host = sInstance;
        if (host == null) { log("debugLeashAnim: host not started"); return; }
        host.debug.debugLeashAnim(slotIndex, durationMs, pkg);
    }

    final WorkspaceCarrierCoordinator carriers =
            new WorkspaceCarrierCoordinator(this, WorkspaceHost::log);

    public static void debugRoots() {
        WorkspaceHost host = sInstance;
        if (host == null) { log("debugRoots: host not started"); return; }
        host.debug.debugRoots();
    }

    void adoptExisting(Slot slot, LiveTask task, Runnable success, Consumer<String> failure) {
        taskTransfer.adoptExisting(slot, task, success, failure);
    }

    void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) {
        taskTransfer.adoptExisting(slot, task, collectFromMain, success, failure);
    }

    void returnToSystem(Slot slot, boolean front, Runnable success, Consumer<String> failure) {
        taskTransfer.returnToSystem(slot, front, success, failure);
    }

    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            Runnable success, Consumer<String> failure) {
        taskTransfer.returnToSystem(slot, front, liveMainReveal, success, failure);
    }

    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            boolean nativeAlreadyFullscreen, Runnable success, Consumer<String> failure) { taskTransfer.returnToSystem(slot, front, liveMainReveal, nativeAlreadyFullscreen, success, failure); }

    static final long ZOOM_ANIM_MS = WorkspaceMotionSpec.DURATION_MS;
    android.animation.ValueAnimator workspaceAnimator;
    android.animation.ValueAnimator mainSideAnimator;
    float mainSideFrameX = Float.NaN;
    WallpaperCropDrawable mainSideBackdrop;
    long mainSideSwitchGeneration;
    long workspaceGeneration;
    float workspaceAlpha = 1f;
    boolean workspaceEntering;
    boolean workspaceExiting;
    long workspaceZoomStart;
    LiveBackdrop workspaceBackdrop;

    volatile long zoomAnimUntil;
    final WorkspaceZoomCommands zoomCommands;

    private void startInternal() {
        main.post(() -> {
            if (!EntryGuard.canOpen(ctx)) {
                if (sInstance == this && root == null) sInstance = null;
                log("queued host start rejected while locked");
                return;
            }
            try {
                mainLaunch.registerDesktopIconReceiver();
                windowLayout.buildLayout();
                windowLayout.createSubViews();
                wallpapers.registerWallpaperReceiver();
                main.postDelayed(chrome::collapseStatusBarPanels, 220L);
                main.postDelayed(wallpapers::loadWallpaperBackdrop, 250L);
            } catch (Throwable t) {
                log("startInternal FAILED: " + t);
                XposedBridge.log(t);
            }
        });
    }

    private void observeWideVideo(TopTaskState.Key key, boolean landscape, boolean fullscreen,
            boolean focused, int mode, boolean portrait, long issued) { topPresentationHost.observeWideVideo(key, landscape, fullscreen, focused, mode, portrait, issued); }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,
            int color, android.graphics.Paint plus) { slotLifecycle.drawEmptyCellPlus(canvas, width, height, color, plus); }

    final WorkspaceWallpaperController wallpapers =
            new WorkspaceWallpaperController(this, WorkspaceHost::log);

    final WorkspaceDisplayController displays =
            new WorkspaceDisplayController(this, WorkspaceHost::log);

    WorkspaceOptions options() { return workspaceOptions; }
    float workspaceAlpha() { return workspaceAlpha; }
    android.animation.ValueAnimator mainSideAnimator() { return mainSideAnimator; }

    void publishDesktopSideTasks() {
        if(desktopPublishQueued)return;
        desktopPublishQueued=true;
        main.postDelayed(()->{
            desktopPublishQueued=false;
            desktopSideTaskPublisher.publish();
        },50L);
    }

    void scheduleSideSurfaceRestore(Slot slot, int taskId, Object token,
            long bindingEpoch, long epoch) {
        for (long delay : new long[]{90L, 320L, 800L}) {
            main.postDelayed(() -> {
                if (scene != ACTIVE || sceneEpoch != epoch || !slotRegistry.isActive(slot)
                        || slot.taskId != taskId || slot.taskEpoch != bindingEpoch
                        || !java.util.Objects.equals(slot.taskToken, token)
                        || !slot.occupied || slot.promoting || slot.parked
                        || slot.windowMode != 6) return;
                scales.reapplyScale(slot);
            }, delay);
        }
    }

    public static void debugDumpViews() {
        WorkspaceHost host = sInstance;
        if (host == null) { log("debugDumpViews: host not started"); return; }
        host.debug.debugDumpViews();
    }

    boolean onSlotTouch(int slotIndex, MotionEvent event) {
        return sideInteraction.onSlotTouch(slotIndex, event);
    }

    void recycleBuffered(Slot slot) {
        WorkspaceSideInteraction.recycleBuffered(slot);
    }

    boolean livePeerBindingsCurrent(Promotion ticket) {
        for (LivePeerBinding peer : ticket.livePeers) {
            Slot slot = peer.slot;
            if (!slotRegistry.isActive(slot) || slot.taskEpoch != peer.epoch || slot.taskId != peer.id
                    || slot.controller != peer.controller || !peer.token.equals(slot.taskToken)
                    || !slot.occupied || slot.parked || slot.frozen || slot.promoting || slot.launching)
                return false;
        }
        return true;
    }

    void drawLiveEmptyHints(android.graphics.Canvas canvas, WorkspaceLayout original,
            WorkspaceLayout target, int revealEmptySlots) { liveGeometry.drawLiveEmptyHints(canvas, original, target, revealEmptySlots); }

    void applyLiveZoom(Promotion ticket, LiveSwapZoom.Zoom target,
            java.util.function.Consumer<LiveSwapZoom.Ack> completed) { liveGeometry.applyLiveZoom(ticket, target, completed); }

    void applyLiveMainLayout(Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,
            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) { liveGeometry.applyLiveMainLayout(ticket, zoom, restoring, completed); }

    void postForSlot(Slot slot, Consumer<Slot> callback) {
        main.post(() -> {
            if (!slotRegistry.isActive(slot)) return;
            callback.accept(slot);
            if (topBar != null) topBar.requestOpenedAppsRefresh();
        });
    }

    boolean executeOnShell(Runnable task) {
        if (executor != null) {
            try { executor.execute(task); return true; }
            catch (Throwable error) { log("Host executor failed: " + error); }
        }
        try {
            Object shell = XposedHelpers.getObjectField(transitions, "mShellExecutor");
            if (shell instanceof Executor) {
                ((Executor) shell).execute(task);
                return true;
            }
        } catch (Throwable t) {
            log("Shell executor failed: " + t);
        }
        return false;
    }

    static void log(String msg) {
        if (!RoutineLog.allow(msg)) return;
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }

    Object prepareLiveFlip(Promotion ticket, WorkspaceLayout geometry, WorkspaceLayout target,
            int width, int height, java.util.List<LiveTaskSwapDriver.Peer> peers,
            Object[] cellBackgrounds) throws Throwable { return liveGeometry.prepareLiveFlip(ticket, geometry, target, width, height, peers, cellBackgrounds); }

    void commitPromotedHandoff(Promotion ticket,Object organizer,Object transitions,Object controller,
            Object fallback,Object info,HandoffOwner owner,Object area,Object canonical) throws Exception { liveGeometry.commitPromotedHandoff(ticket, organizer, transitions, controller, fallback, info, owner, area, canonical); }
}
