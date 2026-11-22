package io.github.zerohomers0.onesteplite;

import android.content.Context;
import java.util.function.Consumer;

/** Registers screen, authentication and native navigation signals for the workspace. */
final class WorkspaceScreenObserver {
    private WorkspaceScreenObserver() {}
    static void install(Context context,Consumer<String> logger) {
        try {
            android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context receivedContext, android.content.Intent intent) {
                    if (android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                        String reason = intent.getStringExtra("reason");
                        logger.accept("native system dialogs close reason=" + reason);
                        if (NavigationPolicy.shouldSuspend(reason)
                                || "fs_gesture".equals(reason) || "gestureNav".equals(reason)
                                || "recentapps".equals(reason))
                            WorkspaceHost.onSystemNavigation(reason);
                        return;
                    }
                    if (android.content.Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        logger.accept("user present -> clear keyguard privacy");
                        WorkspaceHost.onUserPresent();
                        return;
                    }
                    boolean on = android.content.Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    if (!on || !EntryGuard.canOpen(context)) InputTrace.stop();
                    logger.accept("screen " + (on ? "ON" : "OFF"));
                    WorkspaceHost.onScreenState(on);
                }
            };
            android.content.IntentFilter screenFilter = new android.content.IntentFilter();
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            screenFilter.addAction(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(screenReceiver, screenFilter,
                    android.content.Context.RECEIVER_EXPORTED);
            logger.accept("screen trigger armed");
        } catch (Throwable t) {
            logger.accept("screen trigger failed: " + t);
        }
    }
}
