package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;

/** Drives native collection and live swap through submission, completion, and cancellation. */
final class WorkspaceLiveHandoffController {
    private final WorkspaceHost host;

    WorkspaceLiveHandoffController(WorkspaceHost host) {
        this.host = host;
    }

    void startLiveCollect(Slot slot, LiveTask task) {
        final WorkspaceLayout geometry = host.layout;
        final int width = host.screenW, height = host.screenH;
        final Rect sideBounds = host.slotRegistry.logicalBounds(slot);
        final float sideScale = Math.min(sideBounds.width()/(float)width, sideBounds.height()/(float)height);
        final int sideDensity = Math.max(72, Math.round(host.displayDensityDpi*sideScale));
        final Object hostSurface;
        try { hostSurface = host.liveGeometry.liveHostRootSurface(); }
        catch (Throwable error) {
            host.transferBusy = false;
            WorkspaceHost.log("live collect Host unavailable: " + error);
            host.resumes.finishPendingSuspend();
            return;
        }
        host.taskStates.bindTask(slot, task.id, task.token);
        Promotion ticket = new Promotion(slot, host.slots);
        ticket.sceneEpoch = host.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.previousMain = task;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.nativeSwap = ticket.nativeCollect = true;
        slot.promoting = true;
        host.promotion = ticket;
        host.transferBusy = false;
        OwnedTask candidate = host.ownedTasks.computeIfAbsent(task.token, key -> new OwnedTask(task.id, key));
        candidate.recoveryController = slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        try {
            Rect side = WorkspaceLiveGeometry.liveSideStrip(geometry);
            ticket.liveBackdrop = LiveBackdrop.create(hostSurface, host.wallpaperBackdrop, width, height,
                    side, side, canvas -> host.drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << slot.index), WorkspaceHost::log);
        } catch (Throwable error) {
            host.promotionController.failPromotion(ticket, "collect wallpaper preparation: " + error);
            return;
        }
        // Lease the empty-cell affordance as well as the backing. The cell is
        // about to be filled, and a late updateHint() (TaskView#onInitialized
        // posts one) still sees occupied == false until the settle completes.
        // Without the hint lease that pass re-showed the near-white emptyCell
        // gradient over the incoming task for a frame.
        ticket.liveVisual = new LiveSwapVisualLease(host.sideBackingViews[slot.index], slot.hint);
        host.cellSurfaces.prepareCellSurface(slot);
        if (!host.transitionWindow.setLiveSwapInputBlocked(true)) { host.promotionController.failPromotion(ticket, "transition input window unavailable"); return; }
        host.statusBar.hideFor(ticket, () -> host.promotionController.failPromotion(ticket, "collect status-bar lease expired"));
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(task.id, task.token, width, height,
                WorkspaceLiveGeometry.liveMainRect(geometry), WorkspaceLiveGeometry.liveCellRect(geometry, slot.index));
        host.statusBar.runAfterHiddenFrame(ticket, () -> host.liveGeometry.afterLiveHostFrame(ticket, () -> {
            if (!host.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (host.promotionController.externalPromotionCurrent(ticket) && host.layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < host.zoomAnimUntil || !Boolean.TRUE.equals(
                                ShellCompat.call(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = host.slotRegistry.rootInfo(task);
                        if (!host.slotRegistry.ordinaryRoot(info, 1) || !host.slotRegistry.mainTaskVisible(info))
                            throw new IllegalStateException("collect fullscreen identity changed");
                        candidate.userId = XposedHelpers.getIntField(info, "userId");
                        ticket.collectionHome = host.slotRegistry.existingHomeForCollection(task);
                        Object homeInfo = host.slotRegistry.rootInfo(ticket.collectionHome);
                        if (!host.slotRegistry.isHomeRoot(homeInfo) || host.taskStates.windowModeOf(homeInfo) != 1)
                            throw new IllegalStateException("collect Home identity changed");
                        ticket.liveMainUserId = XposedHelpers.getIntField(homeInfo, "userId");
                        host.taskStates.resetIdleTaskView(slot);
                        // Do not reveal the destination placeholder inside the settle
                        // transaction. The cell is empty here, so a stale backing from a
                        // previous occupant would flash its page colour under the incoming
                        // task before that task's first frame lands. The backing is owned by
                        // updateHint()/cellSurfaces.ensureCellBackground() and is rebuilt under the live
                        // task once the bind completes.
                        accepted = LiveCollect.start(host.ctx.getClassLoader(), host.transitions,
                                slot.controller, app, ticket.collectionHome.token, candidate.userId, ticket.liveMainUserId,
                                sideBounds, sideDensity, hostSurface, null,
                                () -> host.promotionController.externalPromotionCurrent(ticket) && host.layout == geometry,
                                settled -> host.main.post(() -> host.mainTaskActions.prepareCollectHandoff(ticket, settled)),
                                result -> host.liveGeometry.finishLiveResult(ticket, result), WorkspaceHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { WorkspaceHost.log("live collect refused: " + error); }
                if (!accepted) host.main.post(() -> finishLiveCollect(ticket, new LiveSwap.Result(false,
                        false, new IllegalStateException("live collect preflight refused"))));
            })) host.promotionController.failPromotion(ticket, "live collect Shell queue unavailable");
        }));
    }

    void finishLiveCollect(Promotion ticket, LiveSwap.Result result) {
        if (!livePromotionBound(ticket) || ticket.nativeCompleting) return;
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.sideOwned && result.rolesConfirmed) {
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = slot.launching = slot.parked = slot.frozen = false;
            ticket.previousMainCandidate.recoveryCandidate = false;
            HomeTaskReturn savedHome = host.coveredHome;
            if (savedHome != null && savedHome.covers(ticket.taskId, ticket.token)) host.coveredHome = null;
        } else if (result.rolesConfirmed) {
            host.ownedTasks.remove(ticket.token, ticket.previousMainCandidate);
            host.slotLifecycle.clearSlot(slot);
        } else {
            slot.promoting = slot.launching = false;
            slot.occupied = slot.frozen = true;
        }
        if (result.sideOwned && result.rolesConfirmed) {
            host.promotionController.finishPromotionFocus(ticket, () -> completeLiveCollect(ticket, result), 0);
        } else completeLiveCollect(ticket, result);
    }

    void completeLiveCollect(Promotion ticket, LiveSwap.Result result) {
        if (host.promotion != ticket || ticket.cancelled) return;
        Slot slot = ticket.slot;
        ticket.cancelled = true;
        host.promotion = null;
        restoreLiveSwapVisual(ticket);
        host.statusBar.finish(ticket);
        host.slotLifecycle.updateHint(slot);
        host.chrome.updateTouchRegion(slot);
        if (result.sideOwned && result.rolesConfirmed) {
            host.scales.applyFullscreenBounds(slot);
            host.scales.applySlotScale(slot);
        }
        host.publishDesktopSideTasks();
        WorkspaceHost.log("live collect settled task=" + ticket.taskId + " side=" + result.sideOwned
                + " confirmed=" + result.rolesConfirmed + " animated=" + result.succeeded + " error=" + result.error);
        host.main.post(host.cells::compactCellsAfterCollection);
        host.resumes.finishPendingSuspend();
        Runnable launch=ticket.collectionLaunch;ticket.collectionLaunch=null;
        if(launch!=null && result.rolesConfirmed && result.sideOwned)host.main.post(launch);
    }

    void startLiveSwap(Promotion ticket) {
        if (!host.promotionController.externalPromotionCurrent(ticket) || ticket.previousMain == null) {
            host.promotionController.failPromotion(ticket, "live swap scene changed before preparation");
            return;
        }
        final WorkspaceLayout geometry = host.layout;
        final WorkspaceOptions options = host.workspaceOptions;
        final WorkspaceLayout target = host.windowLayout.makeAdaptiveLayout(geometry.tabletLandscape
                ? geometry.mainOnRight : options.mainOnRightAfterSwap(geometry.mainOnRight));
        WorkspaceHost.log("live swap mode=" + options.swapMode + " mainRight=" + geometry.mainOnRight + " -> " + target.mainOnRight);
        final int width = host.screenW, height = host.screenH;
        final Rect sideBounds = host.slotRegistry.logicalBounds(ticket.slot);
        final float sideScale = Math.min(sideBounds.width()/(float)width, sideBounds.height()/(float)height);
        final int sideDensity = Math.max(72, Math.round(host.displayDensityDpi*sideScale));
        final LiveTask first = ticket.previousMain;
        LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(first.id, first.token, width, height,
                WorkspaceLiveGeometry.liveMainRect(geometry), WorkspaceLiveGeometry.liveCellRect(target, ticket.slot.index));
        LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(ticket.taskId, ticket.token,
                sideBounds.width(), sideBounds.height(),
                WorkspaceLiveGeometry.liveCellRect(geometry, ticket.slot.index), WorkspaceLiveGeometry.liveMainRect(target));
        final Object hostSurface;
        final java.util.List<LiveTaskSwapDriver.Peer> peers = new java.util.ArrayList<>();
        final Object[] cellBackgrounds = new Object[host.CELL_SLOT_COUNT - 1];
        try { hostSurface = host.prepareLiveFlip(ticket, geometry, target, width, height, peers, cellBackgrounds); }
        catch (Throwable error) { host.promotionController.failPromotion(ticket, "live flip Host preparation: " + error); return; }
        ticket.nativeSwap = true;
        ticket.nativeFlip = true;
        ticket.previousLayout = geometry;
        ticket.targetLayout = target;
        ticket.targetMainOnRight = target.mainOnRight;
        final LiveSwapZoom.Zoom oldZoom = new LiveSwapZoom.Zoom(host.zoomCommands.nextGeneration(),
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        final LiveSwapZoom.Zoom targetZoom = new LiveSwapZoom.Zoom(host.zoomCommands.nextGeneration(),
                target.mainScale, target.mainOffsetX, target.mainOffsetY);
        ticket.slot.promoting = true;
        OwnedTask candidate = host.ownedTasks.computeIfAbsent(first.token, token -> new OwnedTask(first.id, token));
        candidate.recoveryController = ticket.slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        OwnedTask oldSide = host.ownedTasks.get(ticket.token);
        if (oldSide != null) {
            oldSide.recoveryController = ticket.slot.controller;
            oldSide.recoveryCandidate = true;
        }
        ticket.liveVisual = new LiveSwapVisualLease(host.sideBackingViews[ticket.slot.index], ticket.slot.hint);
        for (int i = 1; i < host.CELL_SLOT_COUNT; i++) if (i != ticket.slot.index)
            ticket.peerVisuals.add(new LiveSwapVisualLease(host.sideBackingViews[i], host.slots[i].hint));
        if (!host.transitionWindow.setLiveSwapInputBlocked(true)) { host.promotionController.failPromotion(ticket, "transition input window unavailable"); return; }
        // Keep input blocked until the borrowed surfaces settle on the final host frame.
        host.statusBar.runAfterHiddenFrame(ticket, () -> host.liveGeometry.afterLiveHostFrame(ticket, () -> {
            if (!host.promotionController.externalPromotionCurrent(ticket) || host.layout != geometry) {
                host.promotionController.failPromotion(ticket, "live swap input frame scene changed");
                return;
            }
            SwapOverlayBridge.acquire(host.ctx,host.main,oldZoom,targetZoom,overlays->{
            if(!host.promotionController.externalPromotionCurrent(ticket) || host.layout!=geometry) {
                overlays.close();host.promotionController.failPromotion(ticket,"live toolbox preparation scene changed");return;
            }
            if (!host.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (host.promotionController.externalPromotionCurrent(ticket) && host.layout == geometry && host.livePeerBindingsCurrent(ticket)) {
                        Object shellTransitions = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < host.zoomAnimUntil || !Boolean.TRUE.equals(
                                ShellCompat.call(shellTransitions, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = host.slotRegistry.rootInfo(first);
                        Object sideInfo = host.slotRegistry.rootInfo(new LiveTask(ticket.slot));
                        if (!host.slotRegistry.ordinaryRoot(info, 1) || !host.slotRegistry.ordinaryRoot(sideInfo, 6))
                            throw new IllegalStateException("live main/side identity changed");
                        int user = XposedHelpers.getIntField(info, "userId");
                        int sideUser = XposedHelpers.getIntField(sideInfo, "userId");
                        ticket.previousMainCandidate.userId = user;
                        if (oldSide != null) oldSide.userId = sideUser;
                        ticket.liveMainUserId = sideUser;
                        host.debug.traceSwapEvent("before-single-native-transition");
                        LiveTaskSwapDriver.Flip request = new LiveTaskSwapDriver.Flip(a, b, user, sideUser,
                                new Rect(0, 0, width, height), sideBounds, sideDensity,
                                hostSurface, oldZoom, targetZoom,
                                (zoom, done) -> host.applyLiveZoom(ticket, zoom, done),
                                (zoom, restoring, done) -> host.applyLiveMainLayout(ticket, zoom, restoring, done),
                                peers.toArray(new LiveTaskSwapDriver.Peer[0]), cellBackgrounds, ticket.liveBackdrop);
                        request.overlays=overlays;
                        accepted = LiveTaskSwapDriver.startFlip(host.ctx.getClassLoader(), host.transitions,
                                ticket.slot.controller, request,
                                () -> host.promotionController.externalPromotionCurrent(ticket) && host.livePeerBindingsCurrent(ticket)
                                        && (host.layout == geometry || host.layout == target),
                                frames -> { /* Native input follows these real task surfaces. */ },
                                result -> host.liveGeometry.finishLiveResult(ticket, result), WorkspaceHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { WorkspaceHost.log("native live swap rejected: " + error); }
                if(!accepted)overlays.close();
                if (!accepted) host.main.post(() -> finishLiveSwap(ticket,
                        new LiveSwap.Result(false, false,
                                new IllegalStateException("native preflight refused; no fallback transition"))));
            })) {overlays.close();host.promotionController.failPromotion(ticket, "live swap Shell queue unavailable");}
            },error->host.promotionController.failPromotion(ticket,error));
        }));
    }

    void startLiveHome(Promotion ticket) {
        if (!host.promotionController.externalPromotionCurrent(ticket) || ticket.previousMain != null || ticket.homeBeforeCover == null) {
            host.promotionController.failPromotion(ticket, "live Home scene/identity changed before preparation");
            return;
        }
        final WorkspaceLayout geometry = host.layout;
        final int width = host.screenW, height = host.screenH;
        final Rect sideBounds = host.slotRegistry.logicalBounds(ticket.slot);
        final float sideScale = Math.min(sideBounds.width()/(float)width, sideBounds.height()/(float)height);
        final int sideDensity = Math.max(72, Math.round(host.displayDensityDpi*sideScale));
        final Object hostSurface;
        try {
            hostSurface = host.liveGeometry.liveHostRootSurface();
            Rect side = WorkspaceLiveGeometry.liveSideStrip(geometry);
            ticket.liveBackdrop = LiveBackdrop.create(hostSurface, host.wallpaperBackdrop, width, height,
                    side, side, canvas -> host.drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << ticket.slot.index), WorkspaceHost::log);
        }
        catch (Throwable error) { host.promotionController.failPromotion(ticket, "live Home Host preparation: " + error); return; }
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(ticket.taskId, ticket.token,
                sideBounds.width(), sideBounds.height(),
                WorkspaceLiveGeometry.liveCellRect(geometry, ticket.slot.index), WorkspaceLiveGeometry.liveMainRect(geometry));
        final LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(host.zoomCommands.nextGeneration(),
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        ticket.nativeSwap = true;
        ticket.nativeHome = true;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.slot.promoting = true;
        OwnedTask owned = host.ownedTasks.get(ticket.token);
        if (owned != null) { owned.recoveryController = ticket.slot.controller; owned.recoveryCandidate = true; }
        ticket.liveVisual = new LiveSwapVisualLease(host.sideBackingViews[ticket.slot.index], ticket.slot.hint);
        if (!host.transitionWindow.setLiveSwapInputBlocked(true)) {
            host.promotionController.failPromotion(ticket, "transition input window unavailable"); return;
        }
        host.statusBar.runAfterHiddenFrame(ticket, () -> host.liveGeometry.afterLiveHostFrame(ticket, () -> {
            if (!host.promotionController.externalPromotionCurrent(ticket) || host.layout != geometry) {
                host.promotionController.failPromotion(ticket, "live Home input frame scene changed");
                return;
            }
            if (!host.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (host.promotionController.externalPromotionCurrent(ticket) && host.layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(host.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < host.zoomAnimUntil || !Boolean.TRUE.equals(
                                ShellCompat.call(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        java.util.List<?> roots = host.slotRegistry.rootInfos();
                        Object info = null, homeInfo = null;
                        LiveTask appIdentity = new LiveTask(ticket.slot);
                        for (Object task : roots) {
                            if (appIdentity.matches(task)) info = task;
                            else if (ticket.homeBeforeCover.matches(task)) homeInfo = task;
                        }
                        if (!host.slotRegistry.ordinaryRoot(info, 6) || !host.slotRegistry.isHomeRoot(homeInfo) || host.taskStates.windowModeOf(homeInfo) != 1)
                            throw new IllegalStateException("live Home/app identity changed");
                        int user = XposedHelpers.getIntField(info, "userId");
                        int homeUser = XposedHelpers.getIntField(homeInfo, "userId");
                        ticket.liveMainUserId = user;
                        if (owned != null) owned.userId = user;
                        java.util.List<LiveTaskSwapDriver.SideRoot> sideRoots = new java.util.ArrayList<>();
                        for (Slot peer : host.slots) {
                            if (peer == ticket.slot || !host.slotRegistry.canRender(peer) || !host.cellSurfaces.presentsSlot(peer)
                                    || !peer.occupied || peer.carrier || peer.frozen || peer.promoting
                                    || peer.launching || peer.closing || peer.taskToken == null || peer.controller == null
                                    || (peer.index == WorkspaceHost.SLOT_TOP && !host.topFace)) continue;
                            LiveTask identity = new LiveTask(peer);
                            for (Object task : roots) {
                                if (identity.matches(task) && host.slotRegistry.ordinaryRoot(task, 6)) {
                                    sideRoots.add(new LiveTaskSwapDriver.SideRoot(identity.id,
                                            XposedHelpers.getIntField(task, "userId"),
                                            identity.token, peer.controller));
                                    break;
                                }
                            }
                        }
                        // Same rule as the flip path: the promoted cell's placeholder
                        // stays out of the settle transaction and is rebuilt by
                        // updateHint()/cellSurfaces.ensureCellBackground() once the task is bound.
                        LiveTaskSwapDriver.Home request = new LiveTaskSwapDriver.Home(app,
                                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token, user, homeUser,
                                new Rect(0, 0, width, height), sideBounds, sideDensity, hostSurface, zoom,
                                sideRoots.toArray(new LiveTaskSwapDriver.SideRoot[0]),
                                new Object[]{null}, ticket.liveBackdrop);
                        accepted = LiveTaskSwapDriver.startHome(host.ctx.getClassLoader(), host.transitions,
                                ticket.slot.controller, request,
                                () -> host.promotionController.externalPromotionCurrent(ticket) && host.layout == geometry,
                                frames -> { /* Real task input follows the same surface transaction. */ },
                                result -> host.liveGeometry.finishLiveResult(ticket, result), WorkspaceHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { WorkspaceHost.log("native Home promotion rejected: " + error); }
                if (!accepted) host.main.post(() -> host.liveGeometry.finishLiveHome(ticket, new LiveSwap.Result(false,
                        true, new IllegalStateException("native Home preflight refused; no fallback transition"))));
            })) host.promotionController.failPromotion(ticket, "live Home Shell queue unavailable");
        }));
    }

    boolean livePromotionBound(Promotion ticket) {
        return host.promotion==ticket && !ticket.cancelled
                && TaskHandoffPolicy.bindingCurrent(host.slotRegistry.isActive(ticket.slot),host.sceneEpoch,ticket.sceneEpoch,
                        ticket.slot.taskEpoch,ticket.bindingEpoch,ticket.slot.taskId,ticket.taskId,
                        ticket.slot.taskToken,ticket.token);
    }

    void finishLiveSwap(Promotion ticket, LiveSwap.Result result) {
        if (host.promotion != ticket || ticket.cancelled || ticket.nativeCompleting || !host.slotRegistry.isActive(ticket.slot)) return;
        if (host.sceneEpoch != ticket.sceneEpoch || ticket.slot.taskEpoch != ticket.bindingEpoch
                || ticket.slot.taskId != ticket.taskId || !ticket.token.equals(ticket.slot.taskToken)) {
            WorkspaceHost.log("stale native completion rejected; scene or original slot binding changed");
            return;
        }
        ticket.nativeCompleting = true;
        // This is a confirmed native finish, after the exact WMS/Host commits.
        // REQUEST_TIMEOUT_MS is a pending-request deadline, not a tap cooldown.
        if (result.rolesConfirmed && ticket.nativeFlip && !host.liveGeometry.hasPendingZoom()
                && ticket.liveZoomGuardUntil > 0L && host.zoomAnimUntil == ticket.liveZoomGuardUntil) {
            host.zoomAnimUntil = 0L;
            host.zoomSession.writeZoom(true);
        }
        Slot slot = ticket.slot;
        if (result.sideOwned) {
            // Facade has retired B and bound the same controller to A already.
            // Clear only the old logical slot; never adopt/relaunch A a second time.
            host.ownedTasks.remove(ticket.token);
            host.slotLifecycle.clearSlot(slot);
            host.taskStates.bindTask(slot, ticket.previousMain.id, ticket.previousMain.token);
            if (ticket.previousMainCandidate != null) ticket.previousMainCandidate.recoveryCandidate = false;
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = true;
            slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = false;
            slot.launching = false;
            slot.parked = false;
            host.slotLifecycle.updateHint(slot);
            host.scales.applyFullscreenBounds(slot);
            host.scales.applySlotScale(slot);
            host.promotionController.finishPromotionFocus(ticket, () -> {
                if (host.promotion != ticket || ticket.cancelled) return;
                host.debug.traceSwapEvent("single-native-finish-and-focus");
                ticket.cancelled = true;
                host.promotion = null;
                host.coveredHome = null;
                restoreLiveSwapVisual(ticket);
                host.statusBar.finish(ticket);
                host.publishDesktopSideTasks();
                WorkspaceHost.log("single native swap roles settled main=" + ticket.taskId + " side="
                        + slot.taskId + " animated=" + result.succeeded + " error=" + result.error);
                host.resumes.finishPendingSuspend();
            }, 0);
        } else {
            ticket.cancelled = true;
            host.promotion = null;
            boolean original = host.slotRegistry.controllerOwns(slot, ticket.taskId, ticket.token)
                    && host.scales.mayWriteSideSurface(slot) && result.rolesConfirmed;
            if (original) {
                if (ticket.previousMainCandidate != null)
                    host.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
                OwnedTask retained = host.ownedTasks.get(ticket.token);
                if (retained != null) retained.recoveryCandidate = false;
            } else if (!ticket.nativeSubmitted && ticket.previousMainCandidate != null) {
                // Facade refused before a WCT, so this fullscreen A was never ours.
                host.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
            }
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            if (original) {
                host.scales.applyFullscreenBounds(slot);
                host.scales.applySlotScale(slot);
            }
            host.statusBar.finish(ticket);
            host.publishDesktopSideTasks();
            WorkspaceHost.log("single native swap unconfirmed; original side retained=" + original + " error=" + result.error);
            host.resumes.finishPendingSuspend();
        }
    }

    void restoreLiveSwapVisual(Promotion ticket) {
        LiveSwapVisualLease visual = ticket.liveVisual;
        ticket.liveVisual = null;
        if (visual != null) visual.restore();
        for (LiveSwapVisualLease peer : ticket.peerVisuals) peer.restore();
        ticket.peerVisuals.clear();
        // Native ownership and the final logical bindings now agree. Reconcile
        // once, after the replacement, rather than hiding between clear/bind.
        for (Slot cell : host.slots) if (cell.index > WorkspaceHost.SLOT_MAIN) host.cellSurfaces.updateCellSurfaceVisibility(cell);
        if (host.root != null) { host.root.requestLayout(); host.root.invalidate(); }
        if (ticket.liveBackdrop != null) {
            ticket.liveBackdrop.closeAfterFrame(host.root); ticket.liveBackdrop = null;
        }
        if (host.geometryRefreshAfterLive) {
            host.geometryRefreshAfterLive = false;
            host.displays.scheduleDisplayGeometryRefresh();
        }
        if (host.cells.pending() && host.sideRows.isScrolling()) host.cells.requestCompaction();
        // The draw also restores backing/hints and removes the wallpaper lease.
        // A gesture begun while the real tasks were borrowed remains consumed to UP.
        host.transitionWindow.setLiveSwapInputBlocked(false,ticket.dragHover);
        if(ticket.keepTopBarAppearance && host.promotion==null)host.chrome.updateTopBarState();
        host.promotionController.resumeNotificationLaunch(ticket);
    }

    void requestLiveSwapCancellation(Promotion ticket, String reason) {
        if (host.promotion != ticket || ticket.cancelled || ticket.cancelRequested || ticket.nativeCompleting) return;
        // WCT may already be applied while Shell still waits for onTransitionReady.
        // Keep this binding/ticket until the facade's unique recovery completion.
        ticket.cancelRequested = true;
        WorkspaceHost.log("native swap cancellation requested; retain owner until recovery: " + reason);
        if (!host.executeOnShell(() -> {
            if (host.promotion != ticket || ticket.cancelled || ticket.nativeCompleting) return;
            if (ticket.nativeSubmitted) LiveSwap.cancel(ticket.slot.controller);
            else host.main.post(() -> {
                LiveSwap.Result result = new LiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native swap cancelled before submission: " + reason));
                if (ticket.nativeCollect) finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) host.liveGeometry.finishLiveHome(ticket, result);
                else finishLiveSwap(ticket, result);
            });
        })) {
            // cancel() serializes itself on the original Shell executor.
            if (ticket.nativeSubmitted) LiveSwap.cancel(ticket.slot.controller);
            else {
                LiveSwap.Result result = new LiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native cancellation Shell queue unavailable"));
                if (ticket.nativeCollect) finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) host.liveGeometry.finishLiveHome(ticket, result);
                else finishLiveSwap(ticket, result);
            }
        }
    }

    void closeSwapPreview() {
        closeSwapPreview(null);
    }

    void closeSwapPreview(Object fallbackStatusOwner) {
        host.statusBar.finish(fallbackStatusOwner);
    }

    void closeCellMovePreview() {
        if (host.cellMovePreview == null) return;
        host.cellMovePreview.close();
        host.cellMovePreview = null;
        if (host.root != null) host.root.requestLayout();
    }

    void finishSwapPreview(Object statusOwner, long settleMs) {
        if(statusOwner instanceof Promotion ticket && ticket.dragHover){
            closeSwapPreview(statusOwner);
            return;
        }
        // Two host frames settle the replacement SurfaceView before revealing it.
        Runnable reveal = () -> closeSwapPreview(statusOwner);
        if (host.root != null) host.root.postOnAnimation(() -> host.root.postOnAnimation(() -> {
            if (settleMs <= 0L) reveal.run();
            else host.main.postDelayed(reveal, settleMs);
        }));
        else reveal.run();
    }

    static void releaseCompactSnapshot(Promotion ticket) {
        if (ticket == null) return;
        if (ticket.compactSnapshot != null && !ticket.compactSnapshot.isRecycled()) {
            ticket.compactSnapshot.recycle();
        }
        ticket.compactSnapshot = null;
        ticket.compactSnapshotRect = null;
    }
}
