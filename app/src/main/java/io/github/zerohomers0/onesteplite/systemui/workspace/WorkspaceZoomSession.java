package io.github.zerohomers0.onesteplite;

import android.os.SystemClock;
import android.view.View;

/** Publishes workspace zoom commands and owns the live lease heartbeat. */
final class WorkspaceZoomSession {
    private final WorkspaceHost host;
    private final ZoomTransport zoomTransport;
    private Runnable workspaceHeartbeat;
    private Runnable reassertZoom;
    private long lastWorkspaceRepair;

    WorkspaceZoomSession(WorkspaceHost host) {
        this.host = host;
        zoomTransport = new ZoomTransport(host.ctx, WorkspaceHost::log);
    }

    void setZoomState(boolean on) {
        String sceneViolation = host.sceneTracker.observe(host.scene);
        if (sceneViolation != null) WorkspaceHost.log(sceneViolation);
        on = on && !host.keyguardLocked && host.screenOn && !host.lockExitPending;
        host.zoomed = on;
        if (host.topBar != null) host.topBar.setWorkspaceActive(on);
        if (on) startWorkspaceHeartbeat();
        else if (workspaceHeartbeat != null) host.main.removeCallbacks(workspaceHeartbeat);
        if (!on && reassertZoom != null) { host.main.removeCallbacks(reassertZoom); reassertZoom = null; }
        if (!on) host.unlockGeometryReplay.clear();
        HostEntry.sHostZoomed=on;
        StatusBarSampling.geometryChanged();
        if (on) HotZoneGesture.suspend();
        else HotZoneGesture.raiseAll();
    }

    private String zoomOnCommand() {
        return host.layout.mainScale + " " + host.layout.mainOffsetY + " " + host.layout.mainOffsetX;
    }

    void writeZoomAnimated(boolean on) {
        host.zoomAnimUntil=SystemClock.uptimeMillis()+WorkspaceHost.ZOOM_ANIM_MS+250L;
        host.workspaceZoomStart=SystemClock.uptimeMillis()+WorkspaceMotionSpec.LEAD_MS;
        host.zoomCommands.animated(on,on?zoomOnCommand():"1 0 0",host.workspaceZoomStart,WorkspaceHost.ZOOM_ANIM_MS);
    }

    void writeZoom(boolean on) {
        host.zoomCommands.write(on?zoomOnCommand():"1 0 0");
    }

    void requestReassert() {
        if (reassertZoom != null) return;
        reassertZoom = () -> {
            reassertZoom = null;
            if (host.root == null || !WorkspaceHost.isCurrentInstance(host) || host.scene != WorkspaceHost.ACTIVE
                    || !host.screenOn || host.keyguardLocked || host.root.getVisibility() != View.VISIBLE
                    || host.layout == null || !host.zoomed || host.retainedForLock
                    || host.liveGeometry.holdsLiveFlipZoom()
                    || SystemClock.uptimeMillis() < host.zoomAnimUntil) return;
            writeZoom(true);
        };
        host.main.postDelayed(reassertZoom, 80L);
    }

    private void writeZoomLease() {
        if (zoomTransport.leaseAcked()) {
            zoomTransport.maintainLease();
            // A native transition can replace magnification while the Binder lease remains alive.
            writeZoom(true);
            return;
        }
        if (host.layout != null && !zoomTransport.renew(zoomOnCommand(), System.currentTimeMillis()))
            writeZoom(true);
    }

    void writeZoomLine(String command) {
        writeZoomLine(command, System.currentTimeMillis());
    }

    void writeZoomLine(String command, long issuedAt) {
        if(host.keyguardLocked || !host.screenOn || host.lockExitPending)command="1 0 0";
        zoomTransport.publish(command,issuedAt);
    }

    void startWorkspaceHeartbeat() {
        if (workspaceHeartbeat == null) workspaceHeartbeat = new Runnable() {
            @Override public void run() {
                if (host.root == null || !WorkspaceHost.isCurrentInstance(host) || host.scene != WorkspaceHost.ACTIVE
                        || !host.screenOn || host.keyguardLocked || host.root.getVisibility() != View.VISIBLE) return;
                String sceneViolation = host.sceneTracker.observe(host.scene);
                if (sceneViolation != null) WorkspaceHost.log(sceneViolation);
                long now = SystemClock.uptimeMillis();
                boolean leaseAcked = zoomTransport.leaseAcked();
                if (!host.liveGeometry.holdsLiveFlipZoom() && now >= host.zoomAnimUntil) {
                    if (host.layout != null) writeZoomLease();
                    if (WorkspaceHeartbeatPolicy.repairDue(now, lastWorkspaceRepair, leaseAcked)) {
                        lastWorkspaceRepair = now;
                        if (host.carrierSlotIndex >= 0) host.carriers.reassertCarrier();
                        if (!host.zoomed && !HotZoneGesture.isAttached()) HotZoneGesture.raiseAll();
                        for (Slot slot : host.slots) {
                            if (slot.carrier || slot.index == host.carriers.leashPocSlot()) continue;
                            host.cellSurfaces.ensureCellBackground(slot);
                            if (!slot.occupied || slot.promoting) continue;
                            host.scales.reapplyScale(slot);
                            if (!slot.taskVisible) host.retainedSurfaces.scheduleSideVisibilityRepair(slot);
                        }
                    }
                }
                host.main.postDelayed(this, WorkspaceHeartbeatPolicy.heartbeatDelay(zoomTransport.leaseAcked()));
            }
        };
        host.main.removeCallbacks(workspaceHeartbeat);
        host.main.postDelayed(workspaceHeartbeat, WorkspaceHeartbeatPolicy.heartbeatDelay(zoomTransport.leaseAcked()));
    }
}
