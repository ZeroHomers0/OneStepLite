package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/** Coordinates keyguard privacy and restores retained workspace geometry after authentication. */
final class WorkspaceKeyguardController {
    private final WorkspaceHost host;
    private final WindowPrivacy windowPrivacy = new WindowPrivacy(WorkspaceHost::log);

    WorkspaceKeyguardController(WorkspaceHost host) {
        this.host = host;
    }

    void appendHiddenKeyguardSurfaces(Object tx, java.util.List<Long> epochs) {
        if (!host.retainedForLock || !host.keyguardWindowHidden || host.root == null
                || !epochs.contains(host.sceneEpoch) || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)) return;
        int count = 0;
        for (Slot slot : host.slots) {
            if (slot == null || slot.index <= WorkspaceHost.SLOT_MAIN || !host.slotRegistry.isActive(slot) || !slot.occupied
                    || slot.parked || slot.promoting || slot.launching || slot.frozen
                    || !host.scales.mayWriteSideSurface(slot) || !host.slotRegistry.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = WorkspaceNativeHooks.getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash == null || parent == null || leash == parent) continue;
            try {
                XposedHelpers.callMethod(tx, "reparent", leash, parent);
                XposedHelpers.callMethod(tx, "setLayer", leash, 0);
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", leash, host.slotRegistry.logicalBounds(slot));
                float scale = host.scales.scaleFor(slot);
                XposedHelpers.callMethod(tx, "setMatrix", leash, scale, 0f, 0f, scale);
                XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
                XposedHelpers.callMethod(tx, "show", leash);
                count++;
            } catch (Throwable error) { WorkspaceHost.log("hidden retained parent skipped task=" + slot.taskId + ": " + error); }
        }
        if (count > 0) WorkspaceHost.log("native keyguard transaction retained hidden parents=" + count);
    }

    void trackUnlockFinish(Object transaction) {
        if (transaction == null) return;
        java.util.List<Long> tickets = host.unlockFinishTransactions.take(transaction);
        if (tickets.isEmpty()) {
            long ticket = host.unlockFinishGate.begin(host.sceneEpoch);
            if (ticket == 0) return;
            tickets = java.util.Collections.singletonList(ticket);
        }
        host.unlockFinishTransactions.mark(transaction, tickets);
    }

    void appendUnlockFinishCommit(Object transaction) {
        java.util.List<Long> tickets = host.unlockFinishTransactions.take(transaction);
        if (tickets.isEmpty()) return;
        try {
            ((android.view.SurfaceControl.Transaction) transaction).addTransactionCommittedListener(
                    action -> host.main.post(action), () -> host.unlockFinishGate.committed(tickets));
        } catch (Throwable failure) {
            // Keep main held. The existing handoff watchdog returns through hidden cleanup.
            WorkspaceHost.log("native unlock finish commit listener unavailable: " + failure);
        }
    }

    void appendUnlockGeometry(Object transaction, java.util.List<Long> epochs) {
        if (host.root == null || host.layout == null || !epochs.contains(host.sceneEpoch)
                || !host.screenOn || host.keyguardLocked || host.keyguardWindowHidden || !host.zoomed
                || host.workspaceEntering || host.workspaceAnimator != null || host.mainSideAnimator != null
                || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)) return;
        try {
            if (host.unlockGeometryReplay.append((android.view.SurfaceControl.Transaction) transaction,
                    host.sceneEpoch, host.layout.mainScale, host.layout.mainOffsetX, host.layout.mainOffsetY))
                WorkspaceHost.log("authenticated main geometry appended atomically to native unlock finish");
        } catch (Throwable failure) { WorkspaceHost.log("native unlock geometry replay unavailable: " + failure); }
    }

    boolean setKeyguardWindowHidden(boolean hidden) {
        if(hidden) {
            host.topPresentationHost.releaseTopExitWindow();
            if(host.topPresentation!=null)host.topPresentation.releaseVideo();
        }
        if (hidden == host.keyguardWindowHidden) return true;
        if (host.root == null || host.wm == null || host.lp == null) return false;
        boolean updated=windowPrivacy.setHidden(new WindowPrivacy.Window() {
            @Override public float alpha() { return host.lp.alpha; }
            @Override public int flags() { return host.lp.flags; }
            @Override public void attributes(float alpha,int flags) { host.lp.alpha=alpha;host.lp.flags=flags; }
            @Override public void apply() { host.wm.updateViewLayout(host.root,host.lp); }
            @Override public void requestLayout() { host.root.requestLayout(); }
        },hidden,WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if(updated)host.keyguardWindowHidden=hidden;
        return updated;
    }

    boolean handoffLockExit(Runnable nativeHide){
        final WorkspaceHost host = this.host;
        if(!host.retainedForLock||host.unlockHandoff!=null||host.root==null||!EntryGuard.canOpen(host.ctx))return false;
        final long epoch=host.sceneEpoch;
        final UnlockHandoff[] owner={null};
        host.lockExitPending=true;
        host.root.setVisibility(View.GONE);
        host.windowPresentation.setWorkspaceAlpha(0f);
        UnlockHandoff next=new UnlockHandoff(host.ctx,host.main,1f,0f,0f,ack->{
            if(host.unlockHandoff!=owner[0]||!WorkspaceHost.isCurrentInstance(host)||epoch!=host.sceneEpoch||!host.retainedForLock
                    ||!EntryGuard.canOpen(host.ctx)){owner[0].finish();return;}
            Object tx=null;
            try{
                tx=TransactionCompat.newSurfaceTransaction();
                XposedHelpers.callMethod(tx,"merge",ack.finishReplay);
                ((android.view.SurfaceControl.Transaction)tx).addTransactionCommittedListener(action->host.main.post(action),()->{
                    if(host.unlockHandoff==owner[0]&&WorkspaceHost.isCurrentInstance(host)&&host.sceneEpoch==epoch
                            &&host.retainedForLock&&EntryGuard.canOpen(host.ctx))owner[0].finish();
                });
                XposedHelpers.callMethod(tx,"apply");
            }catch(Throwable failure){WorkspaceHost.log("hidden unlock exit commit: "+failure);owner[0].finish();}
            finally{host.sideRows.closeSurfaceObject(tx,"close");}
        },()->{
            if(host.unlockHandoff!=owner[0])return;
            host.unlockHandoff=null;
            if(!WorkspaceHost.isCurrentInstance(host)||host.sceneEpoch!=epoch||!host.retainedForLock||!EntryGuard.canOpen(host.ctx))return;
            host.retainedForLock=false;host.retainedGeometryConfirmed=false;
            host.keyguardLocked=false;host.screenOn=true;host.keyguardResumeIndex=-1;
            host.zoomSession.setZoomState(false);
            setKeyguardWindowHidden(false); // Root remains GONE before changing window attributes.
            host.resumes.suspendSession("unlock-exit");
            nativeHide.run();
            WorkspaceHost.log("authenticated hidden exit; workspace was never revealed");
        });
        owner[0]=next;host.unlockHandoff=next;next.start();return true;
    }

    boolean retainWorkspaceForLock() {
        host.unlockFinishGate.reset(host.sceneEpoch);
        host.unlockGeometryReplay.clear();
        host.mainSide.cancelMainSideSwitch();
        host.retainedGeometryConfirmed = false;
        host.lockExitPending=false;
        if (host.retainedForLock) {
            // A quick relock can cancel an exit after its hidden root became GONE.
            // Restore only attachment here; native window alpha remains zero.
            if(host.root!=null)host.root.setVisibility(View.VISIBLE);
            host.keyguardLocked = true;
            host.zoomSession.writeZoom(false);
            host.zoomSession.setZoomState(false);
            return setKeyguardWindowHidden(true);
        }
        if (host.root == null || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)) return false;
        host.keyguardLocked = true;
        host.retainedForLock = true;
        host.retainedGeometryConfirmed = false;
        host.keyguardResumeIndex = host.scene == WorkspaceHost.RESUMING && host.workspaceEntering ? 1 : -1;
        host.workspaceEntering = false;
        host.workspaceGeneration++;
        android.animation.ValueAnimator previous = host.workspaceAnimator;
        host.workspaceAnimator = null;
        if (previous != null) previous.cancel();
        if (host.topBar != null) host.topBar.closeContentUi();
        host.publishDesktopSideTasks();
        host.statusBar.restore();
        // Hide native overlay/input before publishing physical fullscreen geometry.
        if (!setKeyguardWindowHidden(true)) {
            host.retainedForLock = false;
            return false;
        }
        host.windowLayout.hideIme();
        host.carriers.setMirrorLayerVisible(false);
        host.zoomSession.writeZoom(false);
        host.zoomSession.setZoomState(false);
        host.retainedWindowPrepared = false;
        final long generation = host.retainedWindowGeneration = System.currentTimeMillis();
        final long epoch = host.sceneEpoch;
        final View owner = host.root;
        RetainedWindow.request(host.ctx, host.main, host.root.getWindowToken(), generation,
                host.layout.mainScale, host.layout.mainOffsetX, host.layout.mainOffsetY, hidden -> {
            if (!hidden || !WorkspaceHost.isCurrentInstance(host) || host.root != owner || host.sceneEpoch != epoch
                    || host.retainedWindowGeneration != generation || !host.retainedForLock
                    || !host.keyguardWindowHidden || EntryGuard.canOpen(host.ctx)) return;
            // A hidden-window acknowledgement is not a geometry commit. Keep
            // compositor alpha at zero until WM confirms the actual zoom replay.
            host.retainedWindowPrepared = true;
            WorkspaceHost.log("native-hidden retained Host leased; waiting for authenticated geometry commit");
        });
        WorkspaceHost.log("workspace retained for lock; native lockscreen zoom restored");
        return true;
    }

    void leaveKeyguardPrivacy() {
        if (host.unlockHandoff != null && host.unlockHandoff.active()) return;
        if (!EntryGuard.canOpen(host.ctx)) return;
        // USER_PRESENT can precede SCREEN_ON. The native guard already confirms
        // an interactive, unlocked display before updating our broadcast cache.
        if (host.retainedForLock) host.screenOn = true;
        if (!host.screenOn) return;
        boolean restore = host.retainedForLock;
        if(restore&&!host.workspaceOptions.keepOnLock){WorkspaceSystemEvents.handoffNativeUnlock(()->{});return;}
        if (restore && !host.retainedGeometryConfirmed) {
            // Native wake/USER_PRESENT and the warm-buffer path use the SAME
            // correlated WM commit and Host frame gate. Neither may reveal early.
            WorkspaceSystemEvents.handoffNativeUnlock(() -> {});
            return;
        }
        host.keyguardLocked = false;
        if (restore && host.root != null && (host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING)) {
            // The real workspace stayed attached. Restore its steady geometry
            // before revealing it; unlocking is not a new animated entry.
            host.workspaceGeneration++;
            host.workspaceEntering = false;
            host.zoomAnimUntil = 0L;
            host.windowPresentation.setWorkspaceAlpha(1f);
            restoreRetainedWorkspaceSurfaces();
            host.zoomSession.writeZoom(true);
            // Geometry and its Host frame have both committed before this path.
            host.zoomSession.setZoomState(true);
        }
        if (!setKeyguardWindowHidden(false)) {
            if (restore) {
                host.keyguardLocked = true;
                host.zoomSession.writeZoom(false);
                host.zoomSession.setZoomState(false);
            }
            return;
        }
        host.retainedForLock = false;
        host.publishDesktopSideTasks();
        if (restore && host.root != null && (host.scene == WorkspaceHost.ACTIVE || host.scene == WorkspaceHost.RESUMING)) {
            final int resumeIndex = host.keyguardResumeIndex;
            host.keyguardResumeIndex = -1;
            host.carriers.setMirrorLayerVisible(true);
            if (host.scene == WorkspaceHost.RESUMING && resumeIndex >= 1) host.resumes.resumeNext(resumeIndex, host.sceneEpoch);
            else if (host.scene == WorkspaceHost.ACTIVE) host.retainedSurfaces.reassertSideTaskViews("unlock retained workspace");
            WorkspaceHost.log("unlock restored retained workspace without entry animation: " + host.debug.describeTasks());
        }
        host.chrome.updateTopBarState();
        if (host.scene == WorkspaceHost.ACTIVE && host.screenOn) {
            for (Slot slot : host.slots) {
                if (slot.occupied && !slot.promoting) {
                    host.scales.reapplyScale(slot);
                    host.retainedSurfaces.scheduleSideVisibilityRepair(slot);
                }
            }
        }
        WorkspaceHost.log("keyguard privacy cleared");
    }

    /** Restores existing task parents before the retained overlay becomes visible. */
    private void restoreRetainedWorkspaceSurfaces() {
        if (!host.retainedForLock || !host.keyguardWindowHidden || host.keyguardLocked || !host.screenOn
                || host.root == null || (host.scene != WorkspaceHost.ACTIVE && host.scene != WorkspaceHost.RESUMING)) return;
        Object tx = null;
        try {
            tx = TransactionCompat.newSurfaceTransaction();
            host.retainedSurfaces.appendWorkspaceSurfaces(tx, java.util.Collections.singletonList(host.sceneEpoch));
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { WorkspaceHost.log("retained unlock surface restore failed: " + error); }
        finally { host.sideRows.closeSurfaceObject(tx, "close"); }
    }
}
