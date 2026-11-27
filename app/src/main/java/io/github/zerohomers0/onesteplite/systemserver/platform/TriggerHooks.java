package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;

/** Hot-zone and broadcast entry points; the Host still owns all task transitions. */
final class TriggerHooks {
    private TriggerHooks() {}

    static void install(android.content.Context context, java.util.concurrent.Executor executor,
            Object factory, Object transitions, java.util.function.Consumer<String> log) {
        Warmup.prepareTaskViews(context, executor, factory);
        final Runnable toggle = new Runnable() {
            @Override
            public void run() {
                if (!EntryGuard.canOpen(context)) return;
                try {
                    WorkspaceHost.toggleOrStart(context, executor, factory, transitions);
                } catch (Throwable t) {
                    log.accept("toggle FAILED: " + t);
                    XposedBridge.log(t);
                }
            }
        };
        HotZoneGesture.install(context, toggle);
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    String action = intent.getAction();
                    if (Actions.DEBUG.equals(action)) {
                        DebugConsole.handle(intent, context, factory, executor, transitions, log);
                        return;
                    }
                    if (WorkspaceHost.LANDSCAPE_MODE_ACTION.equals(action)) {
                        WorkspaceSystemEvents.onLandscapeMode(intent.getBooleanExtra(WorkspaceContract.EXTRA_FULLSCREEN, false));
                        return;
                    }
                    if(RetainedWindow.READY.equals(action)) {
                        WorkspaceSystemEvents.onRetainedNativeReady(intent.getLongExtra(WorkspaceContract.EXTRA_GENERATION,0));return;
                    }
                    if(WorkspaceHost.TOP_TASK_STATE_ACTION.equals(action)) {
                        WorkspaceTopPresentationController.onTopTaskState(intent);return;
                    }
                    log.accept("broadcast " + action + " -> toggle");
                    toggle.run();
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(Actions.TOGGLE);
            filter.addAction(Actions.DEBUG);
            filter.addAction(WorkspaceHost.LANDSCAPE_MODE_ACTION);
            filter.addAction(WorkspaceHost.TOP_TASK_STATE_ACTION);
            filter.addAction(RetainedWindow.READY);
            context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    null, android.content.Context.RECEIVER_EXPORTED);
            log.accept("broadcast trigger armed: TOGGLE / DEBUG");
        } catch (Throwable t) {
            log.accept("broadcast trigger failed: " + t);
        }
        WorkspaceScreenObserver.install(context,log);
    }
}
