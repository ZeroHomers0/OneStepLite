package io.github.zerohomers0.onesteplite;

import android.content.Intent;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Arbitrates side-cell touch and owns the slot participating in a dismissal gesture. */
final class WorkspaceSideInteraction {
    private final WorkspaceHost host;
    private int quickDropIndex=-1;
    private android.graphics.drawable.GradientDrawable quickDropOutline;
    private static final String SIDE_REORDER_MIME="application/vnd.onesteplite.side-reorder";
    private Slot sideDismissSlot;

    WorkspaceSideInteraction(WorkspaceHost host) {
        this.host = host;
    }

    boolean onSlotTouch(int slotIndex, MotionEvent e) {
        if (slotIndex <= WorkspaceHost.SLOT_MAIN || slotIndex >= host.CELL_SLOT_COUNT) return false;
        if (host.taskCellsCovered) return true;
        Slot slot = host.slots[slotIndex];
        // Our MOVE session owns this pointer even while the task-transfer gate is held.
        if (host.sideDismiss != null && sideDismissSlot == slot) return followSideDismiss(slot, e);
        if (slot.promoting || slot.launching || slot.closing || slot.frozen || slot.parked
                || host.transferBusy || host.promotion != null || host.cells.motion() != null) {
            recycleBuffered(slot);
            slot.injecting = false;
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            WorkspaceHost.log("slotTouch down slot=" + slotIndex + " occupied=" + slot.occupied
                    + " x=" + e.getRawX() + " y=" + e.getRawY());
        }
        if (!slot.occupied || slot.view == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                recycleBuffered(slot);
                slot.bufferedDown = MotionEvent.obtain(e);
                slot.touchTaskEpoch = slot.taskEpoch;
                slot.touchSceneEpoch = host.sceneEpoch;
                slot.touchAxis = SideGestureAxis.UNDECIDED;
                slot.injecting = false;
                host.sideInteraction.scheduleSideReorder(slot);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (slot.bufferedDown != null && e.getPointerCount() == 1
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == host.sceneEpoch) {
                    float dx = e.getRawX() - slot.bufferedDown.getRawX();
                    float dy = e.getRawY() - slot.bufferedDown.getRawY();
                    int slop = ViewConfiguration.get(host.ctx).getScaledTouchSlop();
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) slot.injecting = true;
                    if(slot.touchAxis==SideGestureAxis.UNDECIDED)
                        slot.touchAxis=SideGestureAxis.resolve(host.mainOnRight,dx,dy,slop);
                    if (slot.touchAxis==SideGestureAxis.DISMISS
                            && SideDismissGesture.starts(host.mainOnRight, dx, dy, slop)) {
                        dismissSideToBackground(slot);
                        if (host.sideDismiss != null && sideDismissSlot == slot) host.sideDismiss.follow(dx);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                recycleBuffered(slot);
                slot.injecting = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                MotionEvent down = slot.bufferedDown;
                boolean current = down != null && e.getActionMasked() == MotionEvent.ACTION_UP
                        && e.getPointerCount() == 1 && down.getPointerCount() == 1
                        && e.getPointerId(0) == down.getPointerId(0)
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == host.sceneEpoch;
                float dx = current ? e.getRawX() - down.getRawX() : 0;
                float dy = current ? e.getRawY() - down.getRawY() : 0;
                int slop = ViewConfiguration.get(host.ctx).getScaledTouchSlop();
                boolean dismiss = current && slot.rect != null
                        && (slot.touchAxis==SideGestureAxis.DISMISS
                            || slot.touchAxis==SideGestureAxis.UNDECIDED
                                && SideGestureAxis.resolve(host.mainOnRight,dx,dy,slop)==SideGestureAxis.DISMISS)
                        && SideDismissGesture.accepts(
                        host.mainOnRight, dx, dy, slot.rect[2] - slot.rect[0],
                        host.ctx.getResources().getDisplayMetrics().density, slop,
                        e.getEventTime() - down.getEventTime());
                boolean tap = current && !slot.injecting && Math.abs(dx) <= slop && Math.abs(dy) <= slop
                        && e.getEventTime() - down.getEventTime() <= ViewConfiguration.getLongPressTimeout();
                recycleBuffered(slot);
                slot.injecting = false;
                if (dismiss) {
                    dismissSideToBackground(slot);
                    if (host.sideDismiss != null && sideDismissSlot == slot) {
                        host.sideDismiss.follow(dx);
                        host.sideDismiss.settle(true);
                    }
                } else if (tap) {
                    WorkspaceHost.log("cell tap slot=" + slotIndex + " -> swap");
                    host.mainTaskActions.onSlotTapped(slotIndex);
                }
                if (host.cells.pending()) host.cells.compactCells();
                return true;
            }
            default:
                return false;
        }
    }

    static void recycleBuffered(Slot slot) {
        if (slot.bufferedDown != null) {
            slot.bufferedDown.recycle();
            slot.bufferedDown = null;
        }
    }

    private boolean followSideDismiss(Slot slot, MotionEvent e) {
        SideDismiss run = host.sideDismiss;
        if (!run.isTracking()) return true;
        MotionEvent down = slot.bufferedDown;
        int action = e.getActionMasked();
        boolean current = down != null && e.getPointerCount() == 1
                && e.getPointerId(0) == down.getPointerId(0)
                && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == host.sceneEpoch;
        float dx = current ? e.getRawX() - down.getRawX() : 0f;
        float dy = current ? e.getRawY() - down.getRawY() : 0f;
        if (action == MotionEvent.ACTION_MOVE && current) { run.follow(dx); return true; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !current
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            boolean commit = current && action == MotionEvent.ACTION_UP && slot.rect != null
                    && SideDismissGesture.accepts(host.mainOnRight, dx, dy,
                            slot.rect[2] - slot.rect[0], host.ctx.getResources().getDisplayMetrics().density,
                            ViewConfiguration.get(host.ctx).getScaledTouchSlop(), e.getEventTime() - down.getEventTime());
            if (current) run.follow(dx);
            recycleBuffered(slot); slot.injecting = false;
            run.settle(commit);
        }
        return true;
    }

    private void dismissSideToBackground(Slot slot) {
        if (host.scene != WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked || host.transferBusy || host.promotion != null
                || host.cells.motion() != null || !host.slotRegistry.isActive(slot) || !slot.occupied || slot.carrier
                || slot.parked || slot.frozen || slot.promoting || slot.launching || slot.closing
                || slot.taskId < 0 || slot.taskToken == null || slot.controller == null) return;
        for (Slot other : host.slots) if (other.launching || other.promoting || other.closing) return;
        final int taskId = slot.taskId;
        final long binding = slot.taskEpoch;
        final long epoch = host.sceneEpoch;
        final View hostRoot = host.root;
        WorkspaceHost.log("side outward swipe -> background task=" + taskId + " slot=" + slot.index);
        LiveBackdrop preparing = null;
        LiveSwapVisualLease preparingVisuals = null;
        try {
            final WorkspaceLayout geometry = host.layout;
            final int emptyMask = 1 << slot.index;
            Rect strip = WorkspaceLiveGeometry.liveSideStrip(geometry);
            final LiveBackdrop backing = preparing = LiveBackdrop.create(host.liveGeometry.liveHostRootSurface(),
                    host.wallpaperBackdrop, host.screenW, host.screenH, strip, strip,
                    canvas -> host.drawLiveEmptyHints(canvas, geometry, geometry, emptyMask), WorkspaceHost::log);
            final LiveSwapVisualLease visuals = preparingVisuals =
                    new LiveSwapVisualLease(host.sideBackingViews[slot.index], slot.hint);
            host.transferBusy = true;
            slot.promoting = true;
            final SideDismiss[] run = new SideDismiss[1];
            Runnable release = () -> {
                run[0].close();
                if (host.sideDismiss == run[0]) { host.sideDismiss = null; sideDismissSlot = null; }
                recycleBuffered(slot); slot.injecting = false;
                if (host.root == hostRoot && host.sceneEpoch == epoch) {
                    for (Slot cell : host.slots) host.cellSurfaces.updateCellSurfaceVisibility(cell);
                    visuals.restore();
                    hostRoot.invalidate();
                } else visuals.restore();
                backing.closeAfterFrame(hostRoot);
                if (host.cells.pending()) host.cells.compactCells();
                host.resumes.finishPendingSuspend();
            };
            float distance = (host.mainOnRight ? -1f : 1f)
                    * (slot.rect[2] - slot.rect[0] + HyperOsStyle.dp(host.ctx, 8f));
            run[0] = new SideDismiss(hostRoot, slot.view, distance,
                    () -> host.root == hostRoot && host.sceneEpoch == epoch && host.scene == WorkspaceHost.ACTIVE
                            && host.slotRegistry.isActive(slot) && slot.taskEpoch == binding && slot.taskId == taskId,
                    reachedEdge -> {
                        if (!reachedEdge) {
                            if (slot.taskEpoch == binding && slot.taskId == taskId) slot.promoting = slot.frozen;
                            host.transferBusy = false; release.run(); return;
                        }
                        host.returnToSystem(slot, false, () -> {
                            // Hide the empty real container before restoring its offscreen View.
                            if (slot.taskEpoch == binding && slot.taskId == taskId) host.slotLifecycle.clearSlot(slot);
                            WorkspaceHost.log("side swipe handed back task=" + taskId + "; kept in system background");
                            release.run();
                        }, error -> {
                            WorkspaceHost.log("side swipe handback stopped; binding retained task=" + taskId + ": " + error);
                            release.run();
                        });
                    }, WorkspaceHost::log);
            host.sideDismiss = run[0];
            sideDismissSlot = slot;
            WorkspaceHost.log("side outward real View animation task=" + taskId + " distance=" + distance);
            // MOVE positions the real view immediately; UP completes or rebounds from it.
        } catch (Throwable error) {
            if (preparingVisuals != null) preparingVisuals.restore();
            if (preparing != null) preparing.closeAfterFrame(hostRoot);
            host.transferBusy = false;
            slot.promoting = slot.frozen;
            WorkspaceHost.log("side outward preparation stopped: " + error);
            host.resumes.finishPendingSuspend();
        }
    }

    void scheduleSideReorder(Slot slot) {
        MotionEvent down=slot.bufferedDown;
        long pressedAt=down.getDownTime();
        SideReorderDrag drag=new SideReorderDrag(slot,slot.index,slot.taskEpoch,host.sceneEpoch,pressedAt);
        host.main.postDelayed(()->{
            if(slot.bufferedDown!=down || down.getDownTime()!=pressedAt || slot.injecting
                    || !canReorderFrom(drag))return;
            android.content.ClipData clip=new android.content.ClipData("小窗排序",
                    new String[]{SIDE_REORDER_MIME},new android.content.ClipData.Item(""));
            slot.injecting=true;
            try {
                if(slot.view.startDragAndDrop(clip,sideReorderShadow(slot),drag,0)) {
                    host.sideRows.cancelArmedGesture();
                    slot.view.performHapticFeedback(android.view.HapticFeedbackConstants.DRAG_START);
                    return;
                }
            } catch(RuntimeException unavailable) {
                WorkspaceHost.log("side reorder drag unavailable: "+unavailable);
            }
            slot.injecting=false;
        },ViewConfiguration.getLongPressTimeout());
    }
    private View.DragShadowBuilder sideReorderShadow(Slot slot) {
        android.graphics.drawable.Drawable icon;
        try { icon=host.ctx.getPackageManager().getApplicationIcon(slot.pkg); }
        catch(android.content.pm.PackageManager.NameNotFoundException unavailable) {
            icon=host.ctx.getPackageManager().getDefaultActivityIcon();
        }
        android.graphics.drawable.Drawable glyph=icon;
        int edge=HyperOsStyle.dp(host.ctx,QuickAppRail.ICON_SIZE_DP);
        return new View.DragShadowBuilder() {
            @Override public void onProvideShadowMetrics(android.graphics.Point size,android.graphics.Point touch) {
                size.set(edge,edge);touch.set(edge/2,edge/2);
            }
            @Override public void onDrawShadow(android.graphics.Canvas canvas) {
                glyph.setBounds(0,0,edge,edge);glyph.draw(canvas);
            }
        };
    }
    private boolean canReorderFrom(SideReorderDrag drag) {
        return host.root!=null && host.sideRows.canMoveSideRows() && !host.sideRows.isCaptured() && !host.sideRows.motionRunning()
                && drag.scene()==host.sceneEpoch && drag.source().taskEpoch==drag.binding()
                && drag.source().index==drag.position() && isReorderableSide(drag.source());
    }
    private boolean isReorderableSide(Slot slot) {
        return slot.index>WorkspaceHost.SLOT_MAIN && slot.index<host.CELL_SLOT_COUNT && host.slotRegistry.isActive(slot) && host.cellSurfaces.presentsSlot(slot)
                && slot.occupied && slot.taskToken!=null && slot.view!=null && slot.view.isShown() && slot.rect!=null
                && !slot.promoting && !slot.launching && !slot.closing && !slot.frozen && !slot.parked
                && !slot.carrier && !slot.nativeReleasePending;
    }
    boolean onSideReorderDrag(View target,android.view.DragEvent event,SideReorderDrag drag) {
        int action=event.getAction();
        if(action==android.view.DragEvent.ACTION_DRAG_ENDED || action==android.view.DragEvent.ACTION_DRAG_EXITED) {
            showQuickDrop(target,null);
            Slot source=drag.source();
            if(action==android.view.DragEvent.ACTION_DRAG_ENDED && drag.scene()==host.sceneEpoch
                    && source.bufferedDown!=null && source.bufferedDown.getDownTime()==drag.down()) {
                host.recycleBuffered(source);
                source.injecting=false;
                if(host.cells.pending())host.cells.requestCompaction();
            }
            return true;
        }
        if(target!=host.root || !canReorderFrom(drag)) {
            showQuickDrop(target,null);return false;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_STARTED)
            return event.getClipDescription()!=null && event.getClipDescription().hasMimeType(SIDE_REORDER_MIME);
        Slot match=null;
        for(int index=1;index<host.CELL_SLOT_COUNT;index++) {
            Slot candidate=host.slots[index];
            if(candidate==drag.source() || !isReorderableSide(candidate))continue;
            int[] rect=candidate.rect;
            if(event.getX()>=rect[0] && event.getX()<rect[2] && event.getY()>=rect[1] && event.getY()<rect[3]) {
                match=candidate;break;
            }
        }
        if(action!=android.view.DragEvent.ACTION_DROP){showQuickDrop(target,match);return true;}
        showQuickDrop(target,null);
        if(match==null || !host.slotRegistry.controllerOwns(drag.source(),drag.source().taskId,drag.source().taskToken)
                || !host.slotRegistry.controllerOwns(match,match.taskId,match.taskToken))return false;
        if (!host.sideRows.reorderRows(drag.source(), match)) host.mainTaskActions.reorderNativeSideSlots(drag.source(), match);
        return true;
    }
    boolean onQuickAppDrag(View target,android.view.DragEvent event,QuickApp app) {
        int action=event.getAction();
        if(action==android.view.DragEvent.ACTION_DRAG_ENDED || action==android.view.DragEvent.ACTION_DRAG_EXITED) {
            showQuickDrop(target,null);return true;
        }
        if(host.scene!=WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked || !WorkspaceHost.boundsHooksReady
                || host.promotion!=null || host.transferBusy || host.taskCellsCovered) {
            showQuickDrop(target,null);return false;
        }
        if(action==android.view.DragEvent.ACTION_DRAG_STARTED) {
            return event.getClipDescription()!=null
                    && event.getClipDescription().hasMimeType(QuickAppRail.APP_DRAG_MIME);
        }
        String pkg=app.component.getPackageName();
        int user=host.slotRegistry.currentUserId();
        for(Slot slot:host.slots) {
            if(slot.index>WorkspaceHost.SLOT_MAIN && pkg.equals(slot.pkg)
                    && (slot.launching || host.mainLaunch.sideTaskMatchesEntry(slot,user,app.component))) {
                showQuickDrop(target,null);return false;
            }
        }
        Slot match=null;
        for(int index=1;index<host.CELL_SLOT_COUNT;index++) {
            Slot slot=host.slots[index];
            if(!host.slotRegistry.isActive(slot) || slot.occupied || slot.launching || slot.promoting
                    || slot.closing || slot.rect==null || slot.view==null || !slot.view.isShown())continue;
            int[] rect=slot.rect;
            if(event.getX()>=rect[0] && event.getX()<rect[2]
                    && event.getY()>=rect[1] && event.getY()<rect[3]){match=slot;break;}
        }
        if(action!=android.view.DragEvent.ACTION_DROP){showQuickDrop(target,match);return true;}
        showQuickDrop(target,null);
        if(match==null)return false;
        Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component);
        host.launches.launchIntoSlot(match.index,launch,app.label);
        return true;
    }
    void showQuickDrop(View target,Slot slot) {
        int index=slot==null?-1:slot.index;
        if(index==quickDropIndex) {
            if(slot!=null && quickDropOutline!=null) {
                int[] rect=slot.rect;quickDropOutline.setBounds(rect[0],rect[1],rect[2],rect[3]);
            }
            return;
        }
        if(quickDropOutline!=null)target.getOverlay().remove(quickDropOutline);
        quickDropOutline=null;
        if(slot!=null) {
            float density=target.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable outline=new android.graphics.drawable.GradientDrawable();
            outline.setColor(0x1834c759);outline.setCornerRadius(14*density);
            outline.setStroke(Math.max(1,Math.round(2*density)),0xff34c759);
            int[] rect=slot.rect;outline.setBounds(rect[0],rect[1],rect[2],rect[3]);
            quickDropOutline=outline;target.getOverlay().add(outline);
            if(index!=quickDropIndex)target.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        quickDropIndex=index;
    }
}
