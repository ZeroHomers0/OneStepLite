package io.github.zerohomers0.onesteplite;

/** Every broadcast action this module sends or listens for, in one place. */
final class Actions {
    static final String SETTINGS_CHANGED = "io.github.zerohomers0.onesteplite.SETTINGS_CHANGED";
    /** Debug console: toggles OneStepLite and accepts developer commands. */
    static final String DEBUG = "io.github.zerohomers0.onesteplite.DEBUG";
    static final String TOGGLE = "io.github.zerohomers0.onesteplite.TOGGLE";
    /** Desktop icon launch and the side-task registry it consults. */
    static final String DESKTOP_ICON_LAUNCH = "io.github.zerohomers0.onesteplite.DESKTOP_ICON_LAUNCH";
    static final String DESKTOP_SIDE_TASKS = "io.github.zerohomers0.onesteplite.DESKTOP_SIDE_TASKS";
    /** Shell hands one live layout handoff to the host. */
    static final String ZOOM = "io.github.zerohomers0.onesteplite.ZOOM";
    static final String ZOOM64 = "io.github.zerohomers0.onesteplite.ZOOM64";
    static final String ZOOM64_ACK = "io.github.zerohomers0.onesteplite.ZOOM64_ACK";
    static final String TRANSFER_TOUCH = "io.github.zerohomers0.onesteplite.TRANSFER_TOUCH";
    static final String PREPARE_TOP_VIEWPORT = "io.github.zerohomers0.onesteplite.PREPARE_TOP_VIEWPORT";
    static final String SWAP_OVERLAY_SURFACES = "io.github.zerohomers0.onesteplite.SWAP_OVERLAY_SURFACES";
    /** Workspace chrome and the retained native window it reveals. */
    static final String RETAINED_WINDOW = "io.github.zerohomers0.onesteplite.RETAINED_WINDOW";
    static final String RETAINED_READY = "io.github.zerohomers0.onesteplite.RETAINED_READY";
    static final String UNLOCK_HANDOFF_RELEASE = "io.github.zerohomers0.onesteplite.UNLOCK_HANDOFF_RELEASE";
    static final String WORKSPACE_WALLPAPER_PARENT = "io.github.zerohomers0.onesteplite.WORKSPACE_WALLPAPER_PARENT";
    static final String MAIN_UI_CONFIGURATION = "io.github.zerohomers0.onesteplite.MAIN_UI_CONFIGURATION";
    static final String LANDSCAPE_MODE = "io.github.zerohomers0.onesteplite.LANDSCAPE_MODE";
    /** Top task controls and the edge-back gesture they own. */
    static final String TOP_TASK_STATE = "io.github.zerohomers0.onesteplite.TOP_TASK_STATE";
    /** Native video overlay docked into the workspace. */
    static final String VIDEO_HOST = "io.github.zerohomers0.onesteplite.VIDEO_HOST";
    static final String VIDEO_STATE = "io.github.zerohomers0.onesteplite.VIDEO_STATE";
    static final String VIDEO_COMMAND = "io.github.zerohomers0.onesteplite.VIDEO_COMMAND";
    /** Bottom input replay and raw input tracing. */
    static final String BOTTOM_INPUT_OPTIONS = "io.github.zerohomers0.onesteplite.BOTTOM_INPUT_OPTIONS";
    static final String TRACE_INPUT = "io.github.zerohomers0.onesteplite.TRACE_INPUT";

    private Actions() {}
}
