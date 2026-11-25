package io.github.zerohomers0.onesteplite;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Developer-only broadcast console; capability checks stay in the host entry. */
final class DebugConsole {
    private DebugConsole() {}

    static void handle(android.content.Intent intent, android.content.Context context,
            Object factory, java.util.concurrent.Executor executor, Object transitions,
            java.util.function.Consumer<String> log) {
        if (context == null) return;

        if (intent.getBooleanExtra("stopInputTrace", false)) {
            InputTrace.stop();
            if (context != null) context.sendBroadcast(new Intent(InputTrace.ACTION)
                    .setPackage("android").putExtra("stopInputTrace", true));
            return;
        }
        if (!EntryGuard.canOpen(context)) return;
        if (intent.hasExtra("legacyBottomSign")) {
            HotZoneGesture.completeBottomReplay(intent.getIntExtra("legacyBottomSign", 0),
                    intent.getLongExtra("legacyBottomDown", -1),
                    intent.getLongExtra("legacyBottomTime", -1));
            return;
        }
        if (intent.getBooleanExtra("traceInput", false)) {
            InputTrace.arm(context, false, context.getClassLoader());
            context.sendBroadcast(new Intent(InputTrace.ACTION).setPackage("android"));
            return;
        }
        if (intent.getBooleanExtra("traceNextSwap", false)) {
            WorkspaceHost.debugTraceNextSwap();
            return;
        }
        int slot = intent.getIntExtra("slot", -1);
        String pkg = intent.getStringExtra("pkg");
        boolean swap = intent.getBooleanExtra("swap", false);
        if (intent.getBooleanExtra("dumpViews", false)) {
            log.accept("broadcast DEBUG dumpViews");
            WorkspaceHost.debugDumpViews();
            return;
        }
        if (intent.getBooleanExtra("roots", false)) {
            log.accept("broadcast DEBUG roots");
            WorkspaceHost.debugRoots();
            return;
        }
        if (intent.getBooleanExtra("leashAnim", false)) {
            final int animSlot = intent.getIntExtra("slot", -1);
            final int duration = intent.getIntExtra("durationMs", 400);
            final String animPkg = intent.getStringExtra("pkg");
            log.accept("broadcast DEBUG leashAnim slot=" + animSlot + " duration=" + duration
                    + " pkg=" + animPkg);
            if (factory == null || executor == null || transitions == null) {
                log.accept("broadcast DEBUG leashAnim: capabilities not ready, ignored");
                return;
            }
            WorkspaceHost.toggleOrStartIfNeeded(context, executor, factory,
                    transitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> WorkspaceHost.debugLeashAnim(animSlot, duration, animPkg), 1500L);
            return;
        }
        if (intent.hasExtra("mirrorTask") || intent.getBooleanExtra("mirror", false)) {
            final int task = intent.getIntExtra("mirrorTask", -1);
            final int x = intent.getIntExtra("mirrorX", 0);
            final int y = intent.getIntExtra("mirrorY", 622);
            final int w = intent.getIntExtra("mirrorW", 400);
            final int h = intent.getIntExtra("mirrorH", 912);
            final int hold = intent.getIntExtra("mirrorHoldMs", 6000);
            final boolean hideSource = intent.getBooleanExtra("mirrorHideSource", false);
            final int hideModeExtra = intent.getIntExtra("mirrorHideMode", -1);
            final int hideMode = hideModeExtra >= 0 ? hideModeExtra : (hideSource ? 1 : 0);
            log.accept("broadcast DEBUG mirror task=" + task + " rect=" + x + "," + y + "," + w + "x" + h
                    + " hold=" + hold + " hideMode=" + hideMode);
            if (factory == null || executor == null || transitions == null) {
                log.accept("broadcast DEBUG mirror: capabilities not ready, ignored");
                return;
            }
            WorkspaceHost.toggleOrStartIfNeeded(context, executor, factory,
                    transitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> WorkspaceHost.debugMirror(task, x, y, w, h, hold, hideMode), 1500L);
            return;
        }
        log.accept("broadcast DEBUG slot=" + slot + " pkg=" + pkg + " swap=" + swap);
        if (slot < 0) {
            return;
        }
        if (factory == null || executor == null || transitions == null) {
            log.accept("broadcast DEBUG: capabilities not ready, ignored");
            return;
        }
        WorkspaceHost.toggleOrStartIfNeeded(context, executor, factory, transitions);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (swap) {
                    WorkspaceHost.debugSwap(slot);
                } else if (pkg != null) {
                    WorkspaceHost.debugLaunch(slot, pkg);
                }
            }
        }, 1500L);
    }
}
