package io.github.zerohomers0.onesteplite;

/** Stable provider authority, call methods, bundle keys and preference keys. */
final class SettingsProtocol {
    static final String AUTHORITY = "io.github.zerohomers0.onesteplite.settings";
    static final String OPTIONS_URI = "content://" + AUTHORITY + "/options";
    static final String SHORTCUTS_URI = "content://" + AUTHORITY + "/shortcuts";
    static final String READ_OPTIONS = "read";
    static final String READ_SHORTCUTS = "read_shortcuts";
    static final String WRITE_SHORTCUTS = "write_shortcuts";
    static final String EXTRA_PINS = "pins";
    static final String EXTRA_SAVED = "saved";
    static final String KEY_KIND_APPS = "apps";
    static final String KEY_KIND_SHARE = "share";
    static final String LEGACY_APPS = "onesteplite_quick_app_pins_v1";
    static final String LEGACY_SHARE = "onesteplite_share_targets_v1";

    static final String KEY_LUNAR = "show_lunar";
    static final String KEY_TIME = "show_time";
    static final String KEY_DATE = "show_date";
    static final String KEY_SWAP = "swap_mode";
    static final String KEY_ENTRY_GESTURE = "entry_gesture";
    static final String KEY_KEEP_ON_LOCK = "keep_on_lock";
    static final String KEY_SCROLL_SIDE_TASKS = "scroll_side_tasks";
    static final String KEY_LANDSCAPE_VIDEO = "landscape_video";
    static final String KEY_HIDE_RECENTS = "hide_side_recents";
    static final String KEY_PARK_MAIN = "park_main_on_launch";
    static final String KEY_DELETE_SHARED_IMAGES = "delete_shared_images";
    static final String KEY_DRAG_SHARE = "drag_share_enabled";
    static final String KEY_THEME = "theme_mode";
    static final String KEY_FOLLOW_TOP_BAR_SIDE = "follow_top_bar_side";

    private SettingsProtocol() {}
}