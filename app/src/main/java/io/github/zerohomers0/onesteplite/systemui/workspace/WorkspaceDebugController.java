package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.os.SystemClock;

/** Developer broadcast entrypoints and passive diagnostics for one WorkspaceHost. */
final class WorkspaceDebugController {
    private final WorkspacePorts.DebugStatePort state;
    private final WorkspacePorts.DebugTaskPort tasks;
    private final WorkspacePorts.DebugSurfacePort surfaces;
    private boolean traceNextSwap;
    private SwapTrace swapTrace;

    WorkspaceDebugController(WorkspacePorts.DebugPorts ports) {
        state=ports.state();
        tasks=ports.tasks();
        surfaces=ports.surfaces();
    }

    void debugLaunch(int slot, String pkg) {
        state.main().post(() -> tasks.launches().launchWhenReady(slot, pkg, 0));
    }

    void debugSwap(int slot) {
        state.main().post(() -> tasks.mainTaskActions().swapWithMain(slot));
    }

    void debugTraceNextSwap() {
        state.main().post(() -> {
            if (state.scene() != WorkspaceHost.ACTIVE || !state.screenOn() || state.keyguardLocked()
                    || state.promotion() != null || state.transferBusy()) return;
            traceNextSwap = true;
            log("passive trace armed for next one swap; 6s/384 events; no transaction mutations");
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
            swapTrace = new SwapTrace(state.main(),
                    new java.io.File(surfaces.context().getFilesDir(), "onesteplite-swap-traces"),
                    WorkspaceDebugController::log, previous == null ? -1 : previous.id,
                    previous == null ? null : previous.token, ticket.taskId, ticket.token,
                    ticket.slot.controller, tasks.carriers().shellOrganizer(), surfaces.layout().describe(),
                    closed -> { if (swapTrace == closed) swapTrace = null; });
        } catch (Throwable error) {
            log("passive trace unavailable: " + error);
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
            log("debugMirror: bad rect " + w + "x" + h);
            return;
        }
        state.main().post(() -> tasks.carriers().startMirrorPoc(taskId,
                new Rect(x, y, x + w, y + h), Math.max(1000, holdMs), hideMode));
    }

    void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        state.main().post(() -> tasks.carriers().runLeashAnimPoc(slotIndex, Math.max(150, durationMs), pkg));
    }

    void debugRoots() {
        if (!tasks.executeOnShell(() -> tasks.slotRegistry().dumpRoots("debug")))
            log("debugRoots skipped: no shell queue");
    }

    void debugDumpViews() {
        state.main().post(() -> {
            int taskViews = 0, occupied = 0, prepared = 0, shown = 0, sideRows = surfaces.sideRows().size();
            for (Slot slot : tasks.slots()) {
                if (slot == null) continue;
                if (slot.view != null) taskViews++;
                if (slot.occupied) occupied++;
                if (slot.sidePrepared) prepared++;
                if (slot.sideShown) shown++;
            }
            Runtime runtime = Runtime.getRuntime();
            long heapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L;
            log("runtime scene=" + surfaces.sceneTracker().snapshot()
                    + " viewport=" + surfaces.screenWidth() + "x" + surfaces.screenHeight() + "@" + surfaces.displayRotation()
                    + " slots=" + tasks.slots().length + " taskViews=" + taskViews
                    + " occupied=" + occupied + " sideRows=" + sideRows
                    + " prepared=" + prepared + " shown=" + shown
                    + " heapUsedKb=" + heapUsedKb);
            log(WorkspaceRuntimeCounters.snapshot());
            log(BackgroundExecutor.diagnostics());
            WorkspaceDiagnostics.dumpViewTree(surfaces.root(), WorkspaceDebugController::log);
        });
    }

    private static void log(String message) { ModuleLog.info("workspace-debug", message); }

    String describeTasks() {
        StringBuilder sb = new StringBuilder();
        for (Slot s : tasks.slots()) {
            sb.append(" [").append(s.index).append(':')
                    .append(s.taskId).append(s.occupied ? "" : "(empty)")
                    .append(s.taskVisible ? "/vis" : "/hid");
            if (s.windowState != null) {
                sb.append("/o").append(s.windowState.configurationOrientation)
                        .append("/r").append(s.windowState.resolvedRotation)
                        .append("/m").append(s.windowState.windowingMode)
                        .append(s.windowState.fullscreen ? "/full" : "/windowed");
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
