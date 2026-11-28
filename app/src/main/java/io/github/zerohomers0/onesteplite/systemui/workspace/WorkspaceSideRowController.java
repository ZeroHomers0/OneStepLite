package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Owns side-row ordering, scrolling viewport and empty-row lifecycle for the workspace host. */
final class WorkspaceSideRowController {
    private static final int SLOT_TOP = WorkspaceHost.SLOT_TOP;
    private static final int SLOT_COUNT = WorkspaceHost.SLOT_COUNT;

    private final WorkspaceHost workspace;
    private final SideRowOrder<Slot> order = new SideRowOrder<>();
    private boolean scrolling;
    private boolean armed;
    private boolean captured;
    private boolean creatingView;
    private float downX;
    private float downY;
    private float lastY;
    private float offset;
    private float savedOffset;
    private final ArrayList<Slot> attached = new ArrayList<>();
    private final SideScrollMotion motion = new SideScrollMotion();
    private android.view.VelocityTracker velocity;
    private boolean compacting;
    private boolean desktopRevealing;
    private long revealGeneration;
    private final IdentityHashMap<Slot, Float> compactionShift = new IdentityHashMap<>();
    private float compactionProgress;
    private Object viewport;
    private final Rect viewportCrop = new Rect();

    WorkspaceSideRowController(WorkspaceHost workspace) {
        this.workspace = workspace;
    }

    boolean isEmpty() { return order.isEmpty(); }
    int size() { return order.size(); }
    boolean isScrolling() { return scrolling; }
    boolean isArmed() { return armed; }
    boolean isCaptured() { return captured; }
    boolean isCompacting() { return compacting; }
    boolean isDesktopRevealing() { return desktopRevealing; }
    boolean motionRunning() { return isSideMotionRunning(); }
    boolean gestureActive() { return armed || captured || isSideMotionRunning(); }

    void cancelArmedGesture() {
        armed = false;
        recycleSideVelocity();
    }

    void taskViewCreated() {
        creatingView = false;
        workspace.cells.requestCompaction();
    }

    void layoutAll() { layoutSideViewport(true); }
    boolean ensureViewport() { return ensureSideViewport(); }
    boolean compactRows() { return compactSideRows(); }
    boolean trackTouch(MotionEvent event) { return trackSideScroll(event); }
    void applySlotRect(Slot slot) { applySideRow(slot, scrolling ? offset : 0f, scrolling); }
    boolean isRowInPlace(Slot slot) { return sideRowInPlace(slot); }
    boolean hasViewport() { return viewport != null; }
    void attachViewport(Slot slot, Object surface) { attachSideViewport(slot, surface); }
    void revealForDesktop(Slot slot, android.os.Bundle binding) { revealSideTaskForDesktop(slot, binding); }
    void onRootAttached(View view) { observeSideViewportRoot(view); }

    void finishGesture() { finishSideScroll(); }

    void scrollToRow(int row) {
        offset = Math.min(maxSideOffset(), row);
        mapSideSlots((int) offset);
        layoutSideViewport(false);
    }

    boolean reorderRows(Slot source, Slot target) {
        if (source.sideRow < 0 || target.sideRow < 0) return false;
        int sourceRow = source.sideRow, targetRow = target.sideRow;
        java.util.Collections.swap(order, sourceRow, targetRow);
        mapSideSlots(Math.round(offset));
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            compactionShift.put(source, (float) (sourceRow - targetRow));
            compactionShift.put(target, (float) (targetRow - sourceRow));
            animateSideCompaction();
        } else commitSideScrollPosition();
        return true;
    }

    private boolean trackSideScroll(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            recycleSideVelocity();
            captured = false;
            downX = event.getX();
            downY = lastY = event.getY();
            armed = scrolling && canMoveSideRows()
                    && (maxSideOffset() > 0 || isSideMotionRunning())
                    && downX >= workspace.layout.cellX && downX < workspace.layout.cellX + workspace.layout.cellWidth
                    && downY >= workspace.layout.topStrip && downY < workspace.screenH;
            if (armed) {
                velocity = android.view.VelocityTracker.obtain();
                if (isSideMotionRunning()) {
                    if (!compacting && !desktopRevealing) cancelSideAnimator();
                    captured = true;
                }
                velocity.addMovement(event);
            }
            return captured;
        }
        if (!armed && !captured) return false;
        if (velocity != null) velocity.addMovement(event);
        if (!canMoveSideRows() || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            boolean consumed = captured;
            int flingVelocity = 0;
            if (consumed && action == MotionEvent.ACTION_UP && velocity != null) {
                ViewConfiguration config = ViewConfiguration.get(workspace.ctx);
                velocity.computeCurrentVelocity(1000, config.getScaledMaximumFlingVelocity());
                float pixelsPerSecond = -velocity.getYVelocity(event.getPointerId(0));
                if (Math.abs(pixelsPerSecond) >= config.getScaledMinimumFlingVelocity())
                    flingVelocity = Math.round(pixelsPerSecond);
            }
            armed = captured = false;
            recycleSideVelocity();
            if (!consumed && workspace.cells.pending()) workspace.cells.requestCompaction();
            if (consumed && !compacting && !desktopRevealing) {
                if (canMoveSideRows()) {
                    if (!flingSideRows(flingVelocity)) settleSideScroll();
                }
                else finishSideScroll();
            }
            return consumed;
        }
        if (compacting || desktopRevealing) return captured;
        if (action != MotionEvent.ACTION_MOVE) return captured;
        float dy = event.getY() - downY;
        if (!captured) {
            float dx = event.getX() - downX;
            int slop = ViewConfiguration.get(workspace.ctx).getScaledTouchSlop();
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return false;
            int axis = SideGestureAxis.resolve(workspace.mainOnRight, dx, dy, slop);
            if (axis == SideGestureAxis.UNDECIDED) return false;
            armed = false;
            if (axis != SideGestureAxis.SCROLL) {
                recycleSideVelocity();
                return false;
            }
            captured = true;
            lastY = downY + Math.copySign(slop, dy);
        }
        setSideOffset(offset + (lastY - event.getY()) / sidePitch());
        lastY = event.getY();
        return true;
    }

    private float sidePitch() {
        return (workspace.layout.mainHeight + workspace.layout.gap) / (float) WorkspaceLayout.CELL_COUNT;
    }

    private int maxSideOffset() {
        return order.maxOffset(WorkspaceLayout.CELL_COUNT,slot->slot.occupied || slot.launching || slot.promoting);
    }

    private void setSideOffset(float nextOffset) {
        float next = Math.min(maxSideOffset(), Math.max(0f, nextOffset));
        if (next == offset) return;
        offset = next;
        layoutSideViewport(false);
    }

    private void cancelSideAnimator() {
        motion.cancel();
        compacting = false;
        compactionShift.clear();
        if (desktopRevealing) {
            desktopRevealing = false;
            ++revealGeneration;
        }
    }

    private boolean isSideMotionRunning() {
        return motion.running() || desktopRevealing;
    }

    private void recycleSideVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private boolean flingSideRows(int pixelsPerSecond) {
        final int start = Math.round(offset * sidePitch());
        final int end = Math.round(maxSideOffset() * sidePitch());
        if (pixelsPerSecond == 0 || workspace.root == null || end <= 0 || (pixelsPerSecond < 0 && start <= 0)
                || (pixelsPerSecond > 0 && start >= end)
                || !android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
        cancelSideAnimator();
        final FrameLayout host=workspace.root;
        final long epoch=workspace.sceneEpoch;
        int projected=motion.fling(host,start,end,pixelsPerSecond,
                ()->workspace.root==host && workspace.sceneEpoch==epoch && canMoveSideRows(),
                pixels->setSideOffset(pixels/sidePitch()),this::finishSideScroll,this::settleSideScroll);
        WorkspaceHost.log("side fling start offset="+offset+" velocity="+pixelsPerSecond+" projected="+(projected/sidePitch()));
        return true;
    }

    private void settleSideScroll() {
        final int target = Math.min(maxSideOffset(), Math.round(offset));
        if (offset == target || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            finishSideScroll();
            return;
        }
        animateSideOffset(target, 160L, this::finishSideScroll);
    }

    private void animateSideOffset(int target, long duration, Runnable done) {
        if (offset == target || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            setSideOffset(target);
            done.run();
            return;
        }
        motion.animate(offset,target,duration,new android.view.animation.DecelerateInterpolator(),
                this::canMoveSideRows,this::setSideOffset,done);
    }

    private void finishSideScroll() {
        boolean wasCompacting = compacting;
        cancelSideAnimator();
        recycleSideVelocity();
        armed = captured = false;
        if (!scrolling && !wasCompacting) return;
        commitSideScrollPosition();
        if (wasCompacting) for (Slot slot : attached) workspace.slotLifecycle.updateHint(slot);
        if (workspace.cells.pending()) workspace.cells.requestCompaction();
    }

    private void commitSideScrollPosition() {
        if (scrolling) {
            offset = Math.min(maxSideOffset(), Math.round(offset));
            mapSideSlots((int) offset);
            layoutSideViewport(false);
            syncSideViewportFocus();
        } else {
            layoutSideViewport(true);
        }
        workspace.publishDesktopSideTasks();
        WorkspaceHost.log("side scroll settled row=" + (int) offset + workspace.debug.describeTasks());
    }

    private boolean sideRowInPlace(Slot slot) {
        return !captured && !isSideMotionRunning()
                && slot.index < workspace.CELL_SLOT_COUNT && slot.rect != null
                && slot.rect[1] >= workspace.layout.topStrip && slot.rect[3] <= workspace.screenH;
    }

    private void revealSideTaskForDesktop(Slot slot, android.os.Bundle binding) {
        final FrameLayout host = workspace.root;
        final int old = Math.round(offset);
        final long epoch = workspace.sceneEpoch;
        final int target = Math.min(maxSideOffset(), Math.max(0,
                Math.min(slot.sideRow, Math.max(old, slot.sideRow - WorkspaceLayout.CELL_COUNT + 1))));
        cancelSideAnimator();
        recycleSideVelocity();
        armed = captured = false;
        desktopRevealing = true;
        final long generation = ++revealGeneration;
        WorkspaceHost.log("desktop side reveal start task=" + slot.taskId + " from=" + offset + " to=" + target);
        animateSideOffset(target, Math.min(430L, 235L + 45L * Math.abs(target - old)), () -> {
            if (workspace.root != host || generation != revealGeneration || !desktopRevealing) return;
            commitSideScrollPosition();
            Runnable afterFrame = () -> {
                if (workspace.root != host || generation != revealGeneration || !desktopRevealing) return;
                desktopRevealing = false;
                if (workspace.sceneEpoch == epoch && workspace.scene == WorkspaceHost.ACTIVE && sideRowInPlace(slot)) {
                    WorkspaceHost.log("desktop side reveal committed row=" + (int) offset + " task=" + slot.taskId);
                    workspace.mainLaunch.preannounceDesktopLaunch(binding);
                }
                if (workspace.cells.pending()) workspace.cells.requestCompaction();
            };
            if (host.isHardwareAccelerated() && host.isAttachedToWindow()
                    && host.getViewTreeObserver().isAlive()) {
                host.getViewTreeObserver().registerFrameCommitCallback(() -> workspace.main.post(afterFrame));
                host.invalidate();
                workspace.main.postDelayed(() -> {
                    if (workspace.root == host && generation == revealGeneration && desktopRevealing) {
                        desktopRevealing = false;
                        WorkspaceHost.log("desktop side reveal frame not committed task=" + slot.taskId);
                        if (workspace.cells.pending()) workspace.cells.requestCompaction();
                    }
                }, 350L);
            } else host.postOnAnimation(afterFrame);
        });
    }

    private void mapSideSlots(int firstRow) {
        workspace.slots=order.remap(workspace.slots,firstRow,WorkspaceLayout.CELL_COUNT,SLOT_COUNT,
                (owner,row)->owner.sideRow=row,(owner,position)->{
                    owner.position(position);workspace.taskStates.syncSlotWindowState(owner);
                });
    }

    private void layoutSideViewport(boolean all) {
        if (workspace.root == null || workspace.layout == null || order.isEmpty() || workspace.layout.tabletLandscape) return;
        if (all) {
            ensureSideViewport();
            for (Slot slot : order) applySideRow(slot, offset, scrolling);
            syncSideViewportFocus();
        } else {
            for (int i = attached.size() - 1; i >= 0; i--) {
                Slot slot = attached.get(i);
                if (!nearSideViewport(slot.sideRow, offset)) {
                    attached.remove(i);
                    applySideRow(slot, offset, false);
                }
            }
            int start = (int) Math.floor(offset);
            int first = Math.max(0, start - 2);
            int end = Math.min(order.size(), start + WorkspaceLayout.CELL_COUNT + 3);
            for (int i = first; i < end; i++) applySideRow(order.get(i), offset, true);
        }
        workspace.root.invalidate();
    }

    private void observeSideViewportRoot(View observedRoot) {
        try {
            Object viewRoot = XposedHelpers.callMethod(observedRoot, "getViewRootImpl");
            Class<?> callback = XposedHelpers.findClass(
                    "android.view.ViewRootImpl$SurfaceChangedCallback", viewRoot.getClass().getClassLoader());
            Object observer = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (observedRoot == workspace.root && viewport != null && !viewportCrop.isEmpty()
                                && args != null && args.length == 1
                                && ("surfaceCreated".equals(name) || "surfaceReplaced".equals(name))) {
                            try {
                                if (XposedHelpers.callMethod(observedRoot, "getViewRootImpl") != viewRoot)
                                    return null;
                                stageSideViewport(args[0], workspace.liveGeometry.liveHostRootSurface(), viewportCrop);
                            } catch (Throwable error) {
                                WorkspaceHost.log("side viewport host refresh failed: " + error);
                            }
                        }
                        return null;
                    });
            XposedHelpers.callMethod(viewRoot, "addSurfaceChangedCallback", observer);
            WorkspaceHost.log("side viewport host callback registered");
        } catch (Throwable error) {
            WorkspaceHost.log("side viewport host callback unavailable: " + error);
        }
    }

    private void stageSideViewport(Object tx, Object host, Rect crop) {
        XposedHelpers.callMethod(tx, "reparent", viewport, host);
        XposedHelpers.callMethod(tx, "setLayer", viewport, -2);
        XposedHelpers.callMethod(tx, "setWindowCrop", viewport, crop);
        XposedHelpers.callMethod(tx, "show", viewport);
    }

    private boolean ensureSideViewport() {
        Object tx = null;
        try {
            Object host = workspace.liveGeometry.liveHostRootSurface();
            if (viewport == null
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(viewport, "isValid"))) {
                closeSurfaceObject(viewport, "release");
                Object builder = Class.forName("android.view.SurfaceControl$Builder")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(builder, "setName", "OneStepLiteSideViewport");
                XposedHelpers.callMethod(builder, "setParent", host);
                XposedHelpers.callMethod(builder, "setContainerLayer");
                viewport = XposedHelpers.callMethod(builder, "build");
                viewportCrop.setEmpty();
            }
            Rect crop = new Rect(workspace.layout.cellX, workspace.layout.topStrip, workspace.layout.cellX + workspace.layout.cellWidth, workspace.screenH);
            tx = TransactionCompat.newSurfaceTransaction();
            stageSideViewport(tx, host, crop);
            XposedHelpers.callMethod(tx, "apply");
            viewportCrop.set(crop);
            return true;
        } catch (Throwable error) {
            WorkspaceHost.log("side viewport unavailable: " + error);
            return false;
        } finally { closeSurfaceObject(tx, "close"); }
    }

    private void attachSideViewport(Slot slot, Object surface) {
        Object targetViewport = viewport;
        if (surface == null || targetViewport == null || viewportCrop.isEmpty()
                || surface == slot.sideViewportSurface && targetViewport == slot.sideViewportParent) return;
        Object tx = null;
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
            tx = TransactionCompat.newSurfaceTransaction();
            XposedHelpers.callMethod(tx, "reparent", surface, targetViewport);
            XposedHelpers.callMethod(tx, "apply");
            slot.sideViewportSurface = surface;
            slot.sideViewportParent = targetViewport;
        } catch (Throwable error) { WorkspaceHost.log("side viewport slot=" + slot.index + ": " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    private boolean nearSideViewport(int row, float offset) {
        int start = (int) Math.floor(offset);
        return row >= start - 2 && row < start + WorkspaceLayout.CELL_COUNT + 3;
    }

    private void applySideRow(Slot slot, float offset, boolean scrolling) {
        Float shiftFrom = compacting ? compactionShift.get(slot) : null;
        if (shiftFrom != null) offset -= shiftFrom * (1f - compactionProgress);
        int first = (int) Math.floor(offset);
        int row = slot.sideRow - first;
        int count = WorkspaceLayout.CELL_COUNT;
        int[] rect = workspace.layout.cellBounds(Math.floorMod(row, count));
        int shift = Math.floorDiv(row, count) * (workspace.layout.mainHeight + workspace.layout.gap)
                - Math.round((offset - first) * sidePitch());
        rect[1] += shift;
        rect[3] += shift;
        boolean near = scrolling ? nearSideViewport(slot.sideRow, offset)
                : row >= 0 && row < count && !scrolling;
        boolean shown = near && rect[3] > workspace.layout.topStrip && rect[1] < workspace.screenH;
        boolean changed = shown != slot.sideShown;
        boolean preparationChanged = near != slot.sidePrepared;
        boolean preparing = near && preparationChanged;
        slot.sideShown = shown;
        slot.sidePrepared = near;
        slot.rect = rect;
        if (near) {
            if (!attached.contains(slot)) attached.add(slot);
        } else {
            attached.remove(slot);
        }
        Rect crop = new Rect(0, Math.max(0, workspace.layout.topStrip - rect[1]),
                rect[2] - rect[0], Math.min(rect[3], workspace.screenH) - rect[1]);
        if (!shown) crop.setEmpty();
        if (near) attachSideViewport(slot, WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl"));
        placeSideView(slot.view, rect, crop);
        placeSideView(slot.hint, rect, crop);
        if (slot.view != null) {
            int visibility = near ? View.VISIBLE : View.INVISIBLE;
            if (slot.view.getVisibility() != visibility) slot.view.setVisibility(visibility);
        }
        if (changed || preparationChanged) workspace.slotLifecycle.updateHint(slot);
        else if (shown) workspace.wallpapers.refreshEmptyHintBackdrop(slot);
        if (preparing && slot.occupied) workspace.scales.reapplyScale(slot);
        if (preparing && slot.occupied && !slot.taskVisible) {
            slot.visibilityRepair.reset();
            workspace.retainedSurfaces.scheduleSideVisibilityRepair(slot, true);
        }
        workspace.chrome.updateTouchRegion(slot);
    }

    private void placeSideView(View view, int[] rect, Rect crop) {
        if (view == null) return;
        float offset=0f;
        if(workspace.layout!=null && !workspace.layout.tabletLandscape && workspace.workspaceAlpha()<1f && workspace.mainSideAnimator()==null) {
            android.graphics.RectF edge=workspace.windowPresentation.workspaceMainHole();
            offset=WorkspaceTransition.sideOffset(workspace.layout.mainOnRight,workspace.layout.mainOffsetX,
                    workspace.layout.mainDispWidth,edge.left,edge.right,workspace.layout.gap,workspace.workspaceAlpha());
        }
        view.setX(rect[0]+offset);
        view.setY(rect[1]);
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width != rect[2] - rect[0] || params.height != rect[3] - rect[1])) {
            params.width = rect[2] - rect[0];
            params.height = rect[3] - rect[1];
            view.setLayoutParams(params);
        }
        if (workspace.workspaceAlpha() < 1f && workspace.layout != null && !workspace.layout.tabletLandscape)
            crop = workspace.windowPresentation.workspaceCellCrop(rect, false);
        view.setClipBounds(crop);
    }

    private boolean compactSideRows() {
        final boolean enabled = workspace.options().scrollSideTasks && !workspace.layout.tabletLandscape
                && !DeviceProfile.isTablet(workspace.ctx);
        if (!enabled && order.isEmpty()) return false;
        if (creatingView || workspace.slots[1].view == null || workspace.slots[SLOT_TOP].view == null) return true;
        if (!ensureSideViewport()) return true;
        if (order.isEmpty()) {
            for (int i = 1; i <= WorkspaceLayout.CELL_COUNT; i++) order.add(workspace.slots[i]);
        }
        if (!compacting) trimTrailingEmptySideRows();
        final boolean wasScrolling = scrolling;
        final float previousOffset = offset;
        final int requestedRow = Math.round(wasScrolling ? previousOffset : savedOffset);
        final Slot anchor = order.get(Math.min(order.size() - 1, requestedRow));
        // Once created, these rows keep their TaskViews across the toggle. Fixed
        // mode compacts the original three cells and keeps the other rows parked.
        final int rowCount = enabled ? order.size() : WorkspaceLayout.CELL_COUNT;
        order.subList(0, rowCount).sort((a, b) -> Boolean.compare(b.occupied, a.occupied));
        scrolling = enabled;
        if (enabled) {
            int anchorRow = anchor.occupied ? order.indexOf(anchor) : requestedRow;
            offset = Math.min(maxSideOffset(), anchorRow);
        } else {
            if (wasScrolling || anchor.occupied)
                savedOffset = anchor.occupied ? order.indexOf(anchor) : requestedRow;
            offset = 0;
        }
        if (wasScrolling == enabled && canMoveSideRows()
                && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            for (int i = 0; i < rowCount; i++) {
                Slot slot = order.get(i);
                float delta = (slot.sideRow - previousOffset) - (i - offset);
                if (slot.occupied && delta != 0f && (!enabled
                        || nearSideViewport(slot.sideRow, previousOffset)
                        || nearSideViewport(i, offset))) compactionShift.put(slot, delta);
            }
        }
        mapSideSlots((int) offset);
        if (!compactionShift.isEmpty()) animateSideCompaction();
        else layoutSideViewport(true);
        Slot last = order.get(order.size() - 1);
        if (enabled && last.occupied && SideRowCapacityPolicy.canAppend(order.size())) appendSideRow();
        return true;
    }

    private void animateSideCompaction() {
        final FrameLayout host = workspace.root;
        final long epoch = workspace.sceneEpoch;
        compacting = true;
        compactionProgress = 0f;
        for (Slot row : order) {
            applySideRow(row, offset, scrolling);
            if (!row.occupied) workspace.slotLifecycle.updateHint(row);
        }
        WorkspaceHost.log("side compaction start moving="+compactionShift.size());
        motion.animate(0f,1f,240L,new android.view.animation.PathInterpolator(.4f,0f,.2f,1f),
                ()->workspace.root==host && workspace.sceneEpoch==epoch,progress->{
                    compactionProgress=progress;
                    for(Slot slot:compactionShift.keySet())applySideRow(slot,offset,scrolling);
                    host.invalidate();
                },()->{finishSideScroll();WorkspaceHost.log("side compaction settled");});
    }

    private void appendSideRow() {
        creatingView = true;
        Slot[] expanded = java.util.Arrays.copyOf(workspace.slots, workspace.slots.length + 1);
        Slot owner = new Slot(workspace.slots.length);
        owner.sideRow = order.size();
        order.add(owner);
        expanded[owner.index] = owner;
        workspace.slots = expanded;
        try {
            ShellCompat.createTaskView(workspace.factory, workspace.ctx, workspace.executor, taskView ->
                    workspace.main.post(() -> workspace.slotLifecycle.onTaskViewCreated(owner, taskView, false)));
        } catch (Throwable error) {
            creatingView = false;
            WorkspaceHost.log("add side row TaskView: " + error);
        }
    }

    private void trimTrailingEmptySideRows() {
        if (workspace.root == null || workspace.scene != WorkspaceHost.ACTIVE || compacting || captured
                || isSideMotionRunning() || desktopRevealing || creatingView || workspace.transferBusy
                || workspace.promotion != null || order.size() <= WorkspaceLayout.CELL_COUNT) return;
        int removed = 0;
        int protectedRows = Math.max(WorkspaceLayout.CELL_COUNT,
                Math.round(offset) + WorkspaceLayout.CELL_COUNT);
        while (order.size() > protectedRows) {
            Slot tail = order.get(order.size() - 1);
            if (tail.occupied || tail.parked || tail.promoting || tail.launching
                    || tail.frozen || tail.closing || tail.nativeReleasePending
                    || tail.taskId >= 0 || tail.taskToken != null) break;
            order.remove(order.size() - 1);
            releaseDetachedSideRow(tail);
            removed++;
        }
        if (removed == 0) return;
        WorkspaceRuntimeCounters.SIDE_ROWS_TRIMMED.addAndGet(removed);
        int required = SLOT_COUNT + Math.max(0, order.size() - WorkspaceLayout.CELL_COUNT);
        if (required < workspace.slots.length) workspace.slots = java.util.Arrays.copyOf(workspace.slots, required);
        attached.removeIf(slot -> slot.index >= workspace.slots.length);
        compactionShift.keySet().removeIf(slot -> slot.index >= workspace.slots.length);
        savedOffset = Math.max(0f, Math.min(savedOffset,
                order.maxOffset(WorkspaceLayout.CELL_COUNT, slot -> slot.occupied)));
        offset = Math.max(0f, Math.min(offset, savedOffset));
        WorkspaceHost.log("trimmed empty side rows=" + removed + " remaining=" + order.size());
    }

    private void releaseDetachedSideRow(Slot slot) {
        workspace.cellSurfaces.removeCellBackground(slot);
        View view = slot.view;
        View hint = slot.hint;
        slot.view = null;
        slot.hint = null;
        slot.controller = null;
        if (hint != null && hint.getParent() instanceof ViewGroup)
            ((ViewGroup) hint.getParent()).removeView(hint);
        if (view != null && view.getParent() instanceof ViewGroup)
            ((ViewGroup) view.getParent()).removeView(view);
        if (view != null) {
            WorkspaceRuntimeCounters.EMPTY_ROW_TASK_VIEWS_RELEASED.incrementAndGet();
            try { ShellCompat.call(view, "release"); }
            catch (Throwable ignored) { /* The native controller may already be released. */ }
        }
    }

    boolean canMoveSideRows() {
        return workspace.scene == WorkspaceHost.ACTIVE && workspace.screenOn && !workspace.keyguardLocked && !workspace.workspaceEntering
                && !workspace.taskCellsCovered && !workspace.transferBusy && workspace.promotion == null && workspace.cells.motion() == null
                && workspace.sideDismiss == null && workspace.carrierSlotIndex < 0;
    }

    void syncSideViewportFocus() {
        final long epoch = workspace.sceneEpoch;
        workspace.executeOnShell(() -> {
            if (workspace.sceneEpoch != epoch || (workspace.scene != WorkspaceHost.ACTIVE && workspace.scene != WorkspaceHost.RESUMING)
                    || !workspace.screenOn || workspace.keyguardLocked) return;
            try {
                Object wct = null;
                int clipped = 0;
                for (Slot slot : workspace.slots) {
                    if (slot.sideRow < 0 || !slot.occupied || slot.parked || slot.promoting
                            || slot.launching || slot.closing || slot.frozen) continue;
                    final int id = slot.taskId;
                    final Object token = slot.taskToken;
                    if (token == null || !workspace.slotRegistry.controllerOwns(slot, id, token)
                            || workspace.taskStates.windowModeOf(workspace.taskStates.currentOrganizedTaskInfo(slot, id, token)) != 6) continue;
                    if (wct == null) wct = TransactionCompat.newWindowTransaction();
                    boolean focusable = slot.sideShown;
                    XposedHelpers.callMethod(wct, "setFocusable", token, focusable);
                    if (!focusable) clipped++;
                }
                if (workspace.sceneEpoch == epoch && wct != null)
                    WorkspaceNativeHooks.applyWct("side-viewport-focus-outside-" + clipped, wct);
            } catch (Throwable error) {
                WorkspaceHost.log("side viewport focus update: " + error);
            }
        });
    }

    void closeSurfaceObject(Object object, String method) {
        if (object == null) return;
        try {
            XposedHelpers.callMethod(object, method);
        } catch (Throwable failure) {
            WorkspaceHost.log("cell backing " + method + " failed: " + failure);
        }
    }

    int slotIndexOfView(View view) {
        for (Slot s : workspace.slots) {
            if (s.view == view) {
                return s.index;
            }
        }
        return -1;
    }

    void applyRect(Slot slot) {
        if (slot.sideRow >= 0) {
            applySlotRect(slot);
            return;
        }
        if (slot.rect == null) {
            return;
        }
        if (slot.view != null) {
            slot.view.setX(slot.rect[0]);
            slot.view.setY(slot.rect[1]);
            if (slot.view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.view.getLayoutParams();
                lp.width = slot.rect[2] - slot.rect[0];
                lp.height = slot.rect[3] - slot.rect[1];
                slot.view.setLayoutParams(lp);
            }
            if (slot.index != WorkspaceHost.SLOT_TOP)
                slot.view.setClipBounds(workspace.windowPresentation.workspaceCellCrop(slot));
        }
        if (slot.hint != null) {
            boolean paneLocal=slot.index==WorkspaceHost.SLOT_TOP;
            slot.hint.setX(paneLocal?0:slot.rect[0]);
            slot.hint.setY(paneLocal?0:slot.rect[1]);
            if (slot.hint.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.hint.getLayoutParams();
                lp.width = paneLocal?-1:slot.rect[2] - slot.rect[0];
                lp.height = paneLocal?-1:slot.rect[3] - slot.rect[1];
                slot.hint.setLayoutParams(lp);
            }
        }
        workspace.cellSurfaces.ensureCellBackground(slot);
    }
}
