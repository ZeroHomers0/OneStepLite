package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.graphics.Rect;
import java.util.concurrent.Executor;
import de.robv.android.xposed.XposedHelpers;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.View;
import java.util.function.Consumer;

/** Coordinates correlated WMS zoom acknowledgements and host frames during live handoff. */
final class WorkspaceLiveGeometry {
    private final WorkspaceHost host;
    private BroadcastReceiver liveZoomReceiver;
    private LiveZoomRequest pendingLiveZoom;

    WorkspaceLiveGeometry(WorkspaceHost host) {
        this.host = host;
    }

    void afterLiveHostFrame(Promotion ticket, Runnable action) {
        final View drawingRoot = host.root;
        if (!host.promotionController.externalPromotionCurrent(ticket) || drawingRoot == null
                || !drawingRoot.isAttachedToWindow() || !drawingRoot.isHardwareAccelerated()) {
            host.promotionController.failPromotion(ticket, "live Host draw cannot commit");
            return;
        }
        try {
            drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> host.main.post(() -> {
                if (host.root == drawingRoot && host.promotionController.externalPromotionCurrent(ticket)) action.run();
                else host.promotionController.failPromotion(ticket, "live Host frame scene changed");
            }));
            drawingRoot.invalidate();
        } catch (Throwable error) { host.promotionController.failPromotion(ticket, "live Host frame registration: " + error); }
    }

    boolean holdsLiveFlipZoom() {
        Promotion active = host.promotion;
        return active != null && active.nativeFlip && !active.cancelled;
    }

    boolean hasPendingZoom() { return pendingLiveZoom != null; }

    private boolean registerLiveZoomReceiver() {
        if (liveZoomReceiver != null) return true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                LiveZoomRequest pending = pendingLiveZoom;
                if (intent == null || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())) return;
                Object replay;
                try {
                    replay = intent.getParcelableExtra(LiveSwapZoomProtocol.FINISH_REPLAY,
                            android.view.SurfaceControl.Transaction.class);
                } catch (RuntimeException unreadable) {
                    if (pending != null) pending.failed("WMS finish replay parcel unreadable");
                    return;
                }
                if (pending == null || intent == null
                        || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())
                        || intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, -1L)
                                != pending.expected.generation) {
                    LiveSwapZoom.closeReplay(replay); return;
                }
                if (!pending.isBound()) {
                    LiveSwapZoom.closeReplay(replay);
                    pending.failed("live zoom ticket changed before WMS commit");
                    return;
                }
                pending.complete(new LiveSwapZoom.Ack(pending.expected.generation,
                        intent.getLongExtra(LiveSwapZoomProtocol.TRANSACTION, 0L),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0)),
                        intent.getBooleanExtra(LiveSwapZoomProtocol.COMMITTED, false),
                        intent.getStringExtra(LiveSwapZoomProtocol.ERROR), replay));
            }
        };
        try {
            host.ctx.registerReceiver(receiver, new IntentFilter(LiveSwapZoomProtocol.ACK),
                    LiveSwapZoomProtocol.PERMISSION, host.main, Context.RECEIVER_EXPORTED);
            liveZoomReceiver = receiver;
            return true;
        } catch (Throwable error) {
            WorkspaceHost.log("live zoom receiver unavailable: " + error);
            return false;
        }
    }

    void applyLiveZoom(Promotion ticket, LiveSwapZoom.Zoom target,
            java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
        host.main.post(() -> {
            LiveZoomRequest request = new LiveZoomRequest(target, completed, host.main,
                    () -> host.liveHandoff.livePromotionBound(ticket),
                    value -> { if (pendingLiveZoom == value) pendingLiveZoom = null; });
            if (host.keyguardLocked || !host.screenOn || !host.liveHandoff.livePromotionBound(ticket) || !ticket.nativeFlip
                    || !registerLiveZoomReceiver()) {
                request.failed("live zoom ticket/receiver unavailable");
                return;
            }
            if (pendingLiveZoom != null) pendingLiveZoom.failed("live zoom superseded by recovery");
            pendingLiveZoom = request;
            host.zoomCommands.observeGeneration(target.generation);
            host.zoomAnimUntil = SystemClock.uptimeMillis() + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
            ticket.liveZoomGuardUntil = host.zoomAnimUntil;
            long issuedAt = System.currentTimeMillis();
            // ZOOM64 renews the WM lease itself. Ordinary transports would eagerly
            // reset the leashes while they still belong to the animation parent.
            // Publish their steady tuple only after the native finish commits.
            Intent intent = new Intent(LiveSwapZoomProtocol.APPLY).setPackage("android");
            intent.putExtra(LiveSwapZoomProtocol.GENERATION, target.generation);
            intent.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(target.scale));
            intent.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(target.x));
            intent.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(target.y));
            intent.putExtra(LiveSwapZoomProtocol.ISSUED, issuedAt);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            host.main.postDelayed(request, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
            try { host.ctx.sendBroadcast(intent, LiveSwapZoomProtocol.PERMISSION); }
            catch (Throwable error) { request.failed("live zoom dispatch failed: " + error); }
        });
    }

    void applyLiveMainLayout(Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,
            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) {
        host.main.post(() -> {
            WorkspaceLayout target = restoring ? ticket.previousLayout : ticket.targetLayout;
            View drawingRoot = host.root;
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<String> fail = error -> {
                if (delivered.compareAndSet(false, true))
                    completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, false, error));
            };
            if (!host.liveHandoff.livePromotionBound(ticket) || !ticket.nativeFlip || target == null
                    || !zoom.sameTuple(target.mainScale, target.mainOffsetX, target.mainOffsetY)
                    || drawingRoot == null || !drawingRoot.isAttachedToWindow()
                    || !drawingRoot.isHardwareAccelerated()) {
                fail.accept("live Host layout generation/scene unavailable");
                return;
            }
            Runnable timeout = () -> fail.accept("live Host layout frame timed out");
            try {
                drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> host.main.post(() -> {
                    if (!host.liveHandoff.livePromotionBound(ticket) || host.root != drawingRoot || host.layout != target) {
                        fail.accept("live Host layout changed before frame commit");
                    } else if (delivered.compareAndSet(false, true)) {
                        host.main.removeCallbacks(timeout);
                        completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, true, null));
                    }
                }));
                host.main.postDelayed(timeout, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
                host.mainSide.applyMainLayout(target);
            } catch (Throwable error) {
                host.main.removeCallbacks(timeout);
                fail.accept("live Host layout failed: " + error);
            }
        });
    }

    void finishLiveResult(Promotion ticket, LiveSwap.Result result) {
        boolean mainReady = result.rolesConfirmed && (ticket.nativeCollect ? result.sideOwned
                : ticket.nativeHome ? result.succeeded && !result.sideOwned : result.sideOwned);
        if (mainReady && host.promotionController.externalPromotionCurrent(ticket)) {
            try { ticket.liveMainFocusResult = host.promotionController.requestLiveMainFocus(ticket); }
            catch (Throwable error) {
                ticket.liveMainFocusResult = FocusResult.UNAVAILABLE;
                WorkspaceHost.log("live main focus request unavailable: " + error);
            }
            ticket.liveMainFocusPrepared = true;
            WorkspaceHost.log("live main focus result=" + ticket.liveMainFocusResult + " task="
                    + (ticket.nativeCollect ? ticket.collectionHome == null ? -1
                            : ticket.collectionHome.id : ticket.taskId)
                    + " uptime=" + SystemClock.uptimeMillis());
        }
        host.main.post(() -> {
            if (ticket.nativeCollect) host.liveHandoff.finishLiveCollect(ticket, result);
            else if (ticket.nativeHome) finishLiveHome(ticket, result);
            else host.liveHandoff.finishLiveSwap(ticket, result);
        });
    }

    void finishLiveHome(Promotion ticket, LiveSwap.Result result) {
        if (!host.liveHandoff.livePromotionBound(ticket) || ticket.nativeCompleting) return;
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.succeeded && !result.sideOwned && result.rolesConfirmed) {
            // The facade already gave B to the existing fullscreen listener and committed finishT.
            ticket.handoffAttempted = ticket.handoffComplete = true;
            host.ownedTasks.remove(ticket.token);
            host.slotLifecycle.clearSlot(slot);
            host.promotionController.finishPromotionFocus(ticket, () -> {
                if (host.promotion != ticket || ticket.cancelled) return;
                host.liveHandoff.restoreLiveSwapVisual(ticket);
                host.statusBar.finish(ticket);
                host.promotionController.completeHomePromotion(ticket);
            }, 0);
        } else {
            boolean original = result.sideOwned && result.rolesConfirmed
                    && host.slotRegistry.controllerOwns(slot, ticket.taskId, ticket.token) && host.scales.mayWriteSideSurface(slot);
            OwnedTask retained = host.ownedTasks.get(ticket.token);
            if (retained != null && original) retained.recoveryCandidate = false;
            ticket.cancelled = true;
            host.promotion = null;
            slot.promoting = !original;
            slot.frozen = !original;
            host.liveHandoff.restoreLiveSwapVisual(ticket);
            if (original) {
                host.scales.applyFullscreenBounds(slot);
                host.scales.applySlotScale(slot);
            }
            host.statusBar.finish(ticket);
            host.publishDesktopSideTasks();
            WorkspaceHost.log("live Home promotion unconfirmed; original side retained=" + original + " error=" + result.error);
            host.resumes.finishPendingSuspend();
        }
    }

    Object liveHostRootSurface() {
        if (host.root == null || !host.root.isAttachedToWindow() || !host.root.isHardwareAccelerated())
            throw new IllegalStateException("live Host window unavailable");
        Object viewRoot = XposedHelpers.callMethod(host.root, "getViewRootImpl");
        Object surface = viewRoot == null ? null : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
            throw new IllegalStateException("live Host root surface unavailable");
        return surface;
    }

    void drawLiveEmptyHints(android.graphics.Canvas canvas, WorkspaceLayout original,
            WorkspaceLayout target, int revealEmptySlots) {
        // Match the resting side background, including gaps outside the hint bounds.
        canvas.drawColor(HyperOsStyle.topWallpaperTint(host.ctx));
        int outer = canvas.save();
        try {
            canvas.scale((float) host.wallpaperBackdrop.getWidth() / original.width,
                    (float) host.wallpaperBackdrop.getHeight() / original.height);
            for (int i = 1; i < host.CELL_SLOT_COUNT; i++) {
                Slot slot = host.slots[i];
                boolean revealed = (revealEmptySlots & (1 << i)) != 0;
                if (slot.hint == null || (!revealed
                        && (slot.occupied || slot.hint.getVisibility() != View.VISIBLE))) continue;
                // Old cells remain until naturally covered by the growing app; target
                // cells emerge below the shrinking app. Finish crops to the target side.
                // A revealed cell paints only its Host hint underneath the moving task.
                int[] before = original.cellBounds(i - 1), after = target.cellBounds(i - 1);
                int[][] positions = java.util.Arrays.equals(before, after)
                        ? new int[][]{before} : new int[][]{before, after};
                for (int[] rect : positions) {
                    int saved = canvas.save();
                    try {
                        canvas.translate(rect[0], rect[1]);
                        canvas.clipRect(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                        if (revealed) {
                            // An occupied hint may still have its pre-wallpaper fallback.
                            // Paint fresh empty chrome rather than revealing that dark tile.
                            WallpaperCropDrawable empty = new WallpaperCropDrawable(host.wallpaperBackdrop,
                                    original.width, original.height, rect[0], rect[1],
                                    HyperOsStyle.topWallpaperTint(host.ctx));
                            empty.setBounds(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                            empty.draw(canvas);
                            host.drawEmptyCellPlus(canvas, rect[2] - rect[0], rect[3] - rect[1],
                                    HyperOsStyle.textSecondary(host.ctx), new android.graphics.Paint(
                                            android.graphics.Paint.ANTI_ALIAS_FLAG));
                            continue;
                        }
                        android.graphics.drawable.Drawable background = slot.hint.getBackground();
                        if (background instanceof WallpaperCropDrawable) {
                            ((WallpaperCropDrawable) background).withScreenOrigin(rect[0], rect[1],
                                    () -> slot.hint.draw(canvas));
                        } else slot.hint.draw(canvas);
                    } finally { canvas.restoreToCount(saved); }
                }
            }
        } finally { canvas.restoreToCount(outer); }
    }

    Object prepareLiveFlip(Promotion ticket, WorkspaceLayout geometry, WorkspaceLayout target,
            int width, int height, java.util.List<LiveTaskSwapDriver.Peer> peers,
            Object[] cellBackgrounds) throws Throwable {
        Object hostSurface = liveHostRootSurface();
        for (int i = 1; i < host.CELL_SLOT_COUNT; i++) {
            Slot peer = host.slots[i];
            // The moving cell must not reveal its placeholder inside the settle
            // transaction: the incoming task has no frame yet, so a stale page-color
            // backing would flash under it. Peer cells keep theirs.
            cellBackgrounds[i - 1] = peer == ticket.slot ? null : peer.cellBackground;
            if (peer == ticket.slot || !peer.occupied) continue;
            if (peer.parked || peer.frozen || peer.carrier || peer.launching || peer.promoting
                    || peer.windowMode != 6 || peer.taskToken == null || peer.controller == null)
                throw new IllegalStateException("peer not live for flip slot=" + i);
            ticket.livePeers.add(new LivePeerBinding(peer));
            Object peerInfo = host.slotRegistry.rootInfo(new LiveTask(peer));
            if (!host.slotRegistry.ordinaryRoot(peerInfo, 6))
                throw new IllegalStateException("peer user or identity changed slot=" + i);
            int[] cell = geometry.cellBounds(i - 1);
            peers.add(new LiveTaskSwapDriver.Peer(new LiveSwapGeometry.Task(peer.taskId,
                    peer.taskToken, cell[2]-cell[0], cell[3]-cell[1],
                    WorkspaceLiveGeometry.liveCellRect(geometry, i), WorkspaceLiveGeometry.liveCellRect(target, i)),
                    peer.controller, XposedHelpers.getIntField(peerInfo, "userId")));
        }
        ticket.liveBackdrop = LiveBackdrop.create(hostSurface, host.wallpaperBackdrop, width, height,
                // Include the main/side gap while its normal Host backing is hidden.
                WorkspaceLiveGeometry.liveSideStrip(geometry), WorkspaceLiveGeometry.liveSideStrip(target),
                canvas -> drawLiveEmptyHints(canvas, geometry, target, 0), WorkspaceHost::log);
        return hostSurface;
    }

    void commitPromotedHandoff(Promotion ticket,Object organizer,Object transitions,Object controller,
            Object fallback,Object info,HandoffOwner owner,Object area,Object canonical) throws Exception {
            Object tx = TransactionCompat.newSurfaceTransaction();
            boolean unregistered = false;
            try {
                TopTaskTransition bridge=host.topTransition;
                boolean liveCenterReveal=ticket.liveMainReveal && bridge!=null;
                ticket.liveMainReveal=liveCenterReveal;
                // A cross-fade hands the task back at its resting workspace frame and
                // only its alpha moves, so the window never jumps orientation. The
                // workspace scales the slot's own leash, so the canonical surface must
                // stay at identity or the window would be scaled twice and shrink.
                // Prepare every surface operation before changing listener ownership.
                XposedHelpers.callMethod(area, "reparentToDisplayArea", 0, canonical, tx);
                XposedHelpers.callMethod(tx, "setMatrix", canonical,
                        1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setPosition", canonical, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", canonical,
                        new Rect(0, 0, host.screenW, host.screenH));
                XposedHelpers.callMethod(tx, "setAlpha", canonical,
                        liveCenterReveal ? 0f : 1f);
                XposedHelpers.callMethod(tx, ticket.background ? "hide" : "show", canonical);
                ticket.handoffAttempted = true;
                if (owner == HandoffOwner.OUR_CONTROLLER) {
                    ShellCompat.call(transitions, "unregisterTaskView", controller);
                    unregistered = true;
                    ShellCompat.call(organizer, "removeListener", controller);
                    if (XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback) {
                        throw new IllegalStateException("native fullscreen listener did not take ownership");
                    }
                }
                // Copied handles still name the same native layer. OS2's vanished
                // callback detaches it, so finish old-owner cleanup before the
                // final canonical reparent transaction can become visible.
                if (owner == HandoffOwner.OUR_CONTROLLER) {
                    XposedHelpers.callMethod(controller, "onTaskVanished", info);
                    ShellCompat.call(transitions, "registerTaskView", controller);
                }
                boolean waitForCommit = ticket.restoreMainOpacityAfterHandoff;
                if (waitForCommit) {
                    try {
                        XposedHelpers.callMethod(tx, "addTransactionCommittedListener", (Executor) host.main::post,
                                (android.view.SurfaceControl.TransactionCommittedListener)
                                        () -> host.taskTransfer.restoreRetainedMainOpacityAfterHandoff(ticket));
                    } catch (Throwable unavailable) {
                        waitForCommit = false;
                    }
                }
                XposedHelpers.callMethod(tx, "apply");
                if(liveCenterReveal) {
                    ticket.liveRevealSurface=canonical;
                    bridge.setLiveSurface(canonical);
                }
                unregistered = false;
                ticket.handoffComplete = true;
                if (ticket.restoreMainOpacityAfterHandoff && !waitForCommit)
                    host.main.post(() -> host.taskTransfer.restoreRetainedMainOpacityAfterHandoff(ticket));
                host.debug.traceSwapEvent("after-native-handoff");
                WorkspaceHost.log("promote native handoff task=" + ticket.taskId + " owner="
                        + fallback.getClass().getName() + " parent=default-task-display-area");
            } finally {
                host.sideRows.closeSurfaceObject(tx, "close");
                // A completed native detach may leave an empty view if re-registration failed.
                // Keep it frozen for exit rather than restoring a task now owned by fullscreen.
                if (unregistered) WorkspaceHost.log("promote handoff interrupted; keep target frozen until exit");
            }
        }

    static LiveSwapGeometry.Rect liveMainRect(WorkspaceLayout geometry) {
        return new LiveSwapGeometry.Rect(geometry.mainOffsetX, geometry.mainOffsetY,
                geometry.width * geometry.mainScale, geometry.height * geometry.mainScale);
    }

    static LiveSwapGeometry.Rect liveCellRect(WorkspaceLayout geometry, int index) {
        int[] bounds = geometry.cellBounds(index - 1);
        return new LiveSwapGeometry.Rect(bounds[0], bounds[1],
                bounds[2] - bounds[0], bounds[3] - bounds[1]);
    }

    static Rect liveSideStrip(WorkspaceLayout geometry) {
        if(geometry.tabletLandscape)return new Rect(0,geometry.toolHeight,geometry.width,geometry.topStrip);
        return new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                geometry.mainOnRight ? geometry.sideWidth : geometry.width, geometry.height);
    }
}
