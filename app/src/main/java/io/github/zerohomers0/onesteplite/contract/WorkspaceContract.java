package io.github.zerohomers0.onesteplite;

/** Stable wire contract shared by system_server, SystemUI and the settings process. */
final class WorkspaceContract {
    static final String SETTINGS_CHANGED = Actions.SETTINGS_CHANGED;
    static final String DESKTOP_ICON_LAUNCH = Actions.DESKTOP_ICON_LAUNCH;
    static final String DESKTOP_SIDE_TASKS = Actions.DESKTOP_SIDE_TASKS;
    static final String ZOOM = Actions.ZOOM;
    static final String ZOOM_LEASE = Actions.ZOOM_LEASE;
    static final String ZOOM64 = Actions.ZOOM64;
    static final String ZOOM64_ACK = Actions.ZOOM64_ACK;
    static final String LANDSCAPE_MODE = Actions.LANDSCAPE_MODE;
    static final String TOP_TASK_STATE = Actions.TOP_TASK_STATE;
    static final String RETAINED_WINDOW = Actions.RETAINED_WINDOW;
    static final String RETAINED_READY = Actions.RETAINED_READY;

    static final String EXTRA_FULLSCREEN = "fullscreen";
    static final String EXTRA_TASK = "task";
    static final String EXTRA_USER = "user";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_LANDSCAPE = "landscape";
    static final String EXTRA_FOCUSED = "focused";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PORTRAIT = "portrait";
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_REQUEST = "request";
    static final String EXTRA_VIEWPORT_ORIENTATION = "viewportOrientation";
    static final String EXTRA_ISSUED = "issued";
    static final String EXTRA_GENERATION = "generation";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_CANCEL = "cancel";
    static final String EXTRA_SCALE = "scale";
    static final String EXTRA_X = "x";
    static final String EXTRA_Y = "y";
    static final String EXTRA_ZOOM_REQUEST = "request";
    static final String EXTRA_OFFSET_Y = "offsetY";
    static final String EXTRA_OFFSET_X = "offsetX";
    static final String EXTRA_LEASE = "lease";

    /** Immutable TOP task direction payload; transports only add Android Intent/Bundle wrappers. */
    record TopTaskSignal(int taskId, int userId, Object token, boolean landscape,
            boolean fullscreen, boolean focused, int mode, boolean portrait, String page,
            int request, int viewportOrientation, long issued) {
        boolean hasTaskIdentity() {
            return taskId >= 0 && userId >= 0 && token != null;
        }
    }

    private WorkspaceContract() {}
}