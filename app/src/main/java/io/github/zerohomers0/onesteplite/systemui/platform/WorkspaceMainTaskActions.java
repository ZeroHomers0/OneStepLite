package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.graphics.Rect;
import android.view.View;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Resolves cell actions and prepares collection or promotion of existing tasks. */
final class WorkspaceMainTaskActions {
    private final WorkspaceHost host;

    WorkspaceMainTaskActions(WorkspaceHost host) {
        this.host = host;
    }

    void onSlotTapped(int slotIndex) {
        if(slotIndex==WorkspaceHost.SLOT_TOP) { host.topPresentationHost.releaseTopTask(true,true,null); return; }
        try {
            if (slotIndex <= WorkspaceHost.SLOT_MAIN || slotIndex >= host.slots.length) {
                return;
            }
            WorkspaceHost.log("onSlotTapped slot=" + slotIndex
                    + (host.slots[slotIndex] != null && host.slots[slotIndex].carrier ? " carrier" : ""));
            if (host.scene != WorkspaceHost.ACTIVE || host.transferBusy) return;
            Slot slot = host.slots[slotIndex];
            if (slot.closing) return;
            if (!prepareSideRowTap(slot)) return;
            slotIndex = slot.index;
            if (slot.carrier) {
                host.carriers.restoreCarrierToMain();
                return;
            }
            if (slot.parked && slot.occupied && !slot.frozen) {
                host.adoptExisting(slot, new LiveTask(slot), host.resumes::finishPendingSuspend,
                        error -> WorkspaceHost.log("explicit resume retained task: " + error));
                return;
            }
            if (!slot.occupied) {
                captureMainIntoEmptySlot(slot);
                return;
            }
            if (slotIndex == WorkspaceHost.SLOT_MAIN) {
                return;
            }
            swapWithMain(slotIndex);
        } catch (Throwable t) {
            WorkspaceHost.log("onSlotTapped FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    void reorderNativeSideSlots(Slot source,Slot target) {
        int from=source.index,to=target.index;
        int[] fromRect=source.rect,toRect=target.rect;
        SlotPositions.swap(host.slots,from,to);
        source.rect=toRect;target.rect=fromRect;
        host.taskStates.syncSlotWindowState(source);host.taskStates.syncSlotWindowState(target);
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){host.cells.finishCellLayout();return;}
        host.cells.animateCellMoves(java.util.List.of(
                new CellMovePlan(source,new Rect(fromRect[0],fromRect[1],fromRect[2],fromRect[3])),
                new CellMovePlan(target,new Rect(toRect[0],toRect[1],toRect[2],toRect[3]))),0,true);
    }

    private boolean prepareSideRowTap(Slot slot) {
        if (slot.sideRow < 0) return true;
        if (host.sideRows.isCompacting() || host.sideRows.isDesktopRevealing()) return false;
        if (host.sideRows.isCaptured() || host.sideRows.motionRunning()) host.sideRows.finishGesture();
        if (slot.index < host.CELL_SLOT_COUNT) return true;
        if (!slot.occupied) return false;
        if (!host.sideRows.isScrolling()) {
            host.returnToSystem(slot, true, () -> {
                host.slotLifecycle.clearSlot(slot);
                host.resumes.finishPendingSuspend();
            }, error -> WorkspaceHost.log("open hidden side task=" + slot.taskId + ": " + error));
            return false;
        }
        host.sideRows.scrollToRow(slot.sideRow);
        return true;
    }

    void captureMainIntoEmptySlot(Slot requestedSlot) {
        captureMainIntoEmptySlot(requestedSlot,null,null);
    }

    void captureMainIntoEmptySlot(Slot requestedSlot, ComponentName target, Runnable after) {
        if (requestedSlot == null) return;
        final Slot slot = requestedSlot;
        if (host.carrierSlotIndex >= 0) {
            android.widget.Toast.makeText(host.ctx, "先点副格把相机放回主区",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.scene != WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked || host.transferBusy
                || host.promotion != null
                || slot.occupied || slot.launching || slot.promoting || slot.frozen
                || slot.controller == null) return;
        for (Slot other : host.slots) if (other.launching || other.promoting || other.closing) return;
        final long epoch = host.sceneEpoch;
        host.transferBusy = true;
        if (!host.executeOnShell(() -> {
            try {
                LiveTask task = host.slotRegistry.findMainTask(); // Deliberately excludes Home and existing cells.
                host.main.post(() -> {
                    if (host.scene != WorkspaceHost.ACTIVE || epoch != host.sceneEpoch || !host.slotRegistry.isActive(slot)
                            || slot.occupied || host.promotion != null || host.suspendRequested) {
                        host.transferBusy = false;
                        host.resumes.finishPendingSuspend();
                        return;
                    }
                    if (task == null) {
                        host.transferBusy = false;
                        if(after!=null) {after.run();return;}
                        WorkspaceHost.log("empty cell keeps Home in main; open an app there first");
                        android.widget.Toast.makeText(host.ctx, "先在主屏打开应用，再点此格收进来",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if(target!=null && target.getPackageName().equals(task.pkg)) {
                        host.transferBusy=false;after.run();return;
                    }
                    host.windowLayout.hideIme();
                    host.liveHandoff.startLiveCollect(slot, task);
                    if(host.promotion!=null && host.promotion.nativeCollect && host.promotion.previousMain==task)
                        host.promotion.collectionLaunch=after;
                });
            } catch (Throwable error) {
                host.main.post(() -> {
                    host.transferBusy = false;
                    WorkspaceHost.log("collect current main admission stopped: " + error);
                    host.resumes.finishPendingSuspend();
                });
            }
        })) {
            host.transferBusy = false;
            WorkspaceHost.log("no Shell queue for collecting current main");
        }
    }

    void prepareCollectHandoff(Promotion ticket, Consumer<Boolean> settled) {
        if (!host.promotionController.externalPromotionCurrent(ticket)) { settled.accept(false); return; }
        // The real app is still above Host. Remove the opaque empty-cell drawing
        // now, commit that frame, then let Shell return the app to its TaskView.
        if (ticket.slot.hint != null) ticket.slot.hint.setVisibility(View.GONE);
        CollectFrame.await(host.root, () -> host.promotionController.externalPromotionCurrent(ticket), settled);
    }

    void swapWithMain(int slotIndex) {
        if(slotIndex==WorkspaceHost.SLOT_TOP) { host.topPresentationHost.releaseTopTask(true,true,null); return; }
        swapWithMain(slotIndex, false);
    }

    private void swapWithMain(int slotIndex, boolean desktopLaunch) {
        if (slotIndex <= WorkspaceHost.SLOT_MAIN || slotIndex >= WorkspaceHost.SLOT_COUNT) {
            return;
        }
        Slot cellSlot = host.slots[slotIndex];
        if (host.scene != WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked || host.transferBusy
                || cellSlot.parked
                || host.promotion != null || cellSlot.launching || cellSlot.promoting) return;
        if (cellSlot.carrier) {
            // 镜像承载的任务本身仍在主区：把画面移回来即完成“升为主区”。
            host.carriers.restoreCarrierToMain();
            return;
        }
        for (Slot s : host.slots) if (s.launching || s.closing) return;
        if (!cellSlot.occupied || cellSlot.controller == null) {
            captureMainIntoEmptySlot(cellSlot);
            return;
        }
        if (cellSlot.taskToken == null) {
            WorkspaceHost.log("promote: task token not ready; keep cell" + slotIndex);
            return;
        }
        final Promotion ticket = new Promotion(cellSlot, host.slots);
        ticket.sceneEpoch = host.sceneEpoch;
        ticket.bindingEpoch = cellSlot.taskEpoch;
        ticket.previousMainOnRight = host.mainOnRight;
        ticket.targetMainOnRight = host.mainOnRight;
        ticket.keepTopBarAppearance = host.layout != null && !host.layout.tabletLandscape
                && host.workspaceOptions.mainOnRightAfterSwap(host.mainOnRight) != host.mainOnRight;
        host.promotion = ticket;
        if (desktopLaunch) ticket.slot.promoting = true;
        host.statusBar.hideFor(ticket, () -> {
            if (host.promotion == ticket && !ticket.cancelled) {
                host.promotionController.failPromotion(ticket, "status-bar lease expired");
            }
        });
        host.recycleBuffered(cellSlot);
        WorkspaceHost.log("promote begin: cell" + slotIndex + " task=" + ticket.taskId);
        host.windowLayout.hideIme();
        if (!host.executeOnShell(() -> {
            try {
                if (ticket.cancelled) return;
                Object current = XposedHelpers.getObjectField(cellSlot.controller, "mTaskToken");
                if (!ticket.token.equals(current)) throw new IllegalStateException("task token changed");
                ticket.previousMain = desktopLaunch ? null : host.slotRegistry.findMainTask();
                if (ticket.previousMain == null) ticket.homeBeforeCover = host.slotRegistry.existingHomeTask();
                host.debug.traceSwap(ticket);
                if (ticket.previousMain == null) {
                    // Home remains anchored. Its state is retained by its original
                    // task, rather than by animating or repainting a Home snapshot.
                    ticket.targetMainOnRight = ticket.previousMainOnRight;
                    host.main.post(() -> {
                        if (host.promotion != ticket || ticket.cancelled || !host.screenOn
                                || host.keyguardLocked || host.scene != WorkspaceHost.ACTIVE) {
                            host.promotionController.failPromotion(ticket, "Home promotion scene changed");
                            return;
                        }
                        if (ticket.homeBeforeCover == null) {
                            host.promotionController.failPromotion(ticket, "exact Home task unavailable");
                            return;
                        }
                        ticket.slot.promoting = true;
                        WorkspaceHost.log("cover existing Home task=" + ticket.homeBeforeCover.id
                                + " with app=" + ticket.taskId + " desktop=" + desktopLaunch
                                + " keepMainSide=" + ticket.previousMainOnRight);
                        host.liveHandoff.startLiveHome(ticket);
                    });
                    return;
                }
                host.main.post(() -> host.liveHandoff.startLiveSwap(ticket));
            } catch (Throwable t) {
                host.main.post(() -> host.promotionController.failPromotion(ticket, "prepare: " + t));
            }
        })) host.promotionController.failPromotion(ticket, "Shell executor unavailable");
    }
}
