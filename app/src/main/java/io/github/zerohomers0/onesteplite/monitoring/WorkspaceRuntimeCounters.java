package io.github.zerohomers0.onesteplite;

import java.util.concurrent.atomic.AtomicLong;

/** Low-cost lifetime counters used only when diagnostics are requested. */
final class WorkspaceRuntimeCounters {
    static final AtomicLong TOP_APP_REFRESHES = new AtomicLong();
    static final AtomicLong MEDIA_WATCHDOG_POLLS = new AtomicLong();
    static final AtomicLong MEDIA_STATE_REFRESHES = new AtomicLong();
    static final AtomicLong MEDIA_STATE_CHANGES = new AtomicLong();
    static final AtomicLong TASK_VIEWS_CREATED = new AtomicLong();
    static final AtomicLong EMPTY_ROW_TASK_VIEWS_RELEASED = new AtomicLong();
    static final AtomicLong SURFACE_TRANSFORMS = new AtomicLong();
    static final AtomicLong TASK_LOOKUPS = new AtomicLong();
    static final AtomicLong SIDE_ROWS_TRIMMED = new AtomicLong();

    private WorkspaceRuntimeCounters() {}

    static String snapshot() {
        return "topRefresh=" + TOP_APP_REFRESHES.get()
                + " mediaPoll=" + MEDIA_WATCHDOG_POLLS.get()
                + " mediaStateRefresh=" + MEDIA_STATE_REFRESHES.get()
                + " mediaStateChange=" + MEDIA_STATE_CHANGES.get()
                + " taskViewCreated=" + TASK_VIEWS_CREATED.get()
                + " emptyRowTaskViewsReleased=" + EMPTY_ROW_TASK_VIEWS_RELEASED.get()
                + " surfaceTransform=" + SURFACE_TRANSFORMS.get()
                + " taskLookup=" + TASK_LOOKUPS.get()
                + " rowsTrimmed=" + SIDE_ROWS_TRIMMED.get();
    }
}
