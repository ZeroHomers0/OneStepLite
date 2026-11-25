package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.os.SystemClock;

/** Developer broadcast entrypoints and passive diagnostics for one WorkspaceHost. */
final class WorkspaceDebugController {
    private final WorkspaceHost host;
    private boolean traceNextSwap;
    private SwapTrace swapTrace;

    WorkspaceDebugController(WorkspaceHost host) {
        this.host = host;
    }

    void debugLaunch(int slot, String pkg) {
        host.main.post(() -> host.coordinatorLaunchWhenReady(slot, pkg, 0));
    }

    void debugSwap(int slot) {
        host.main.post(() -> host.coordinatorSwapWithMain(slot));
    }

    void debugTraceNextSwap() {
        host.main.post(() -> {
            if (host.scene != WorkspaceHost.ACTIVE || !host.screenOn || host.keyguardLocked
                    || host.promotion != null || host.transferBusy) return;
            traceNextSwap = true;
            WorkspaceHost.log("passive trace armed for next one swap; 6s/384 events; no transaction mutations");
        });
    }

    void traceSwap(Promotion ticket) {
        if (!traceNextSwap) return;
        traceNextSwap = false;
        SwapTrace old = swapTrace;
        if (old != null) old.close();
        swapTrace = null;
        try {
            LiveTask previous = ticket.previousMain;
            swapTrace = new SwapTrace(host.main,
                    new java.io.File(host.ctx.getFilesDir(), "onesteplite-swap-traces"),
                    WorkspaceHost::log, previous == null ? -1 : previous.id,
                    previous == null ? null : previous.token, ticket.taskId, ticket.token,
                    ticket.slot.controller, host.coordinatorShellOrganizer(), host.layout.describe(),
                    closed -> { if (swapTrace == closed) swapTrace = null; });
        } catch (Throwable error) {
            WorkspaceHost.log("passive trace unavailable: " + error);
        }
    }

    void traceSwapEvent(String event) {
        SwapTrace trace = swapTrace;
        if (trace != null) trace.snapshot(event);
    }

    SwapTrace swapTrace() {
        return swapTrace;
    }

    void stopTrace() {
        traceNextSwap = false;
        SwapTrace trace = swapTrace;
        swapTrace = null;
        if (trace != null) trace.close();
    }

    void debugMirror(int taskId, int x, int y, int w, int h, int holdMs, int hideMode) {
        if (w <= 0 || h <= 0) {
            WorkspaceHost.log("debugMirror: bad rect " + w + "x" + h);
            return;
        }
        host.main.post(() -> host.coordinatorStartMirrorPoc(taskId,
                new Rect(x, y, x + w, y + h), Math.max(1000, holdMs), hideMode));
    }

    void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        host.main.post(() -> host.coordinatorRunLeashAnimPoc(slotIndex, Math.max(150, durationMs), pkg));
    }

    void debugRoots() {
        if (!host.coordinatorExecuteOnShell(() -> host.coordinatorDumpRoots("debug")))
            WorkspaceHost.log("debugRoots skipped: no shell queue");
    }

    void debugDumpViews() {
        host.main.post(() -> WorkspaceDiagnostics.dumpViewTree(host.root, WorkspaceHost::log));
    }
}
