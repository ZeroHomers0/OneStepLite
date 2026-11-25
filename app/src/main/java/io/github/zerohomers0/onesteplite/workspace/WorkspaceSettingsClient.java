package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.function.Consumer;

/** One observer for the process-lifetime Host; provider IPC never blocks SystemUI main. */
final class WorkspaceSettingsClient {
    private final Context context;
    private final Handler main, worker;
    private final Consumer<WorkspaceOptions> changed;
    private boolean reportedFailure;
    private boolean observing;
    private volatile long generation;
    private final ContentObserver observer;
    private final Runnable load = this::load;

    WorkspaceSettingsClient(Context context, Handler main, Consumer<WorkspaceOptions> changed) {
        this.context = context;
        this.main = main;
        this.changed = changed;
        HandlerThread thread = new HandlerThread("OneStepLite-settings");
        thread.start();
        worker = new Handler(thread.getLooper());
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        try {
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ignored,android.content.Intent intent) { refresh(); }
            },new android.content.IntentFilter(Actions.SETTINGS_CHANGED),Actions.SETTINGS_WAKEUP_PERMISSION,main,Context.RECEIVER_EXPORTED);
        } catch(RuntimeException unavailable) {
            android.util.Log.w(Tags.PROBE,"settings wakeup receiver unavailable",unavailable);
        }
        refresh();
    }

    void refresh() {
        generation++;
        if (!observing) try {
            context.getContentResolver().registerContentObserver(WorkspaceSettingsStore.URI, false, observer);
            observing = true;
        } catch (Exception error) {
            android.util.Log.w(Tags.PROBE, "workspace settings observer unavailable", error);
        }
        worker.removeCallbacks(load); worker.post(load);
    }

    private void load() {
        final long request=generation;
        try {
            WorkspaceOptions options = WorkspaceSettingsStore.decode(context.getContentResolver().call(
                    WorkspaceSettingsStore.URI, WorkspaceSettingsStore.READ, null, null));
            main.post(() -> {if(request==generation)changed.accept(options);});
            reportedFailure = false;
        } catch (Exception error) {
            if (!reportedFailure) android.util.Log.w(Tags.PROBE, "workspace settings unavailable; keep last choices", error);
            reportedFailure = true;
        }
    }
}
