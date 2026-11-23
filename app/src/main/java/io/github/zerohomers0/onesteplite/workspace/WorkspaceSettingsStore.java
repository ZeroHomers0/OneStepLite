package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** The module app owns persistence. SystemUI receives only a read-only snapshot. */
final class WorkspaceSettingsStore {
    static final Uri URI = Uri.parse("content://io.github.zerohomers0.onesteplite.settings/options");
    static final String READ = "read", LUNAR = "show_lunar", SWAP = "swap_mode",
            ENTRY_GESTURE = "entry_gesture", KEEP_ON_LOCK = "keep_on_lock",
            SCROLL_SIDE_TASKS = "scroll_side_tasks", LANDSCAPE_VIDEO = "landscape_video",
            HIDE_RECENTS = "hide_side_recents", PARK_MAIN = "park_main_on_launch", DELETE_SHARED_IMAGES = "delete_shared_images";
    private WorkspaceSettingsStore() {}

    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("workspace_settings", Context.MODE_PRIVATE);
    }

    static WorkspaceOptions read(Context context) {
        SharedPreferences preferences = preferences(context);
        return new WorkspaceOptions(0, preferences.getInt(SWAP, 0),
                preferences.getInt(ENTRY_GESTURE, WorkspaceOptions.STATUS_BAR_INWARD),
                preferences.getBoolean(KEEP_ON_LOCK, false),
                preferences.getBoolean(SCROLL_SIDE_TASKS, false), preferences.getBoolean(LUNAR, true),
                preferences.getInt(LANDSCAPE_VIDEO, WorkspaceOptions.VIDEO_TURNED),
                preferences.getBoolean(HIDE_RECENTS, false),
                preferences.getBoolean(PARK_MAIN, false),preferences.getBoolean(DELETE_SHARED_IMAGES,false));
    }

    static boolean write(Context context, WorkspaceOptions options) {
        if (options.entryGesture != read(context).entryGesture
                && !EntryGesturePolicy.canSelect(options.entryGesture,
                        EntryGestureSettings.lineHidden(context))) return false;
        boolean saved = preferences(context).edit().remove("hidden_buttons").putBoolean(LUNAR, options.showLunar)
                .putInt(SWAP, options.swapMode).putInt(ENTRY_GESTURE, options.entryGesture)
                .putBoolean(KEEP_ON_LOCK, options.keepOnLock)
                .putBoolean(SCROLL_SIDE_TASKS, options.scrollSideTasks)
                .putInt(LANDSCAPE_VIDEO, options.landscapeVideo)
                .putBoolean(HIDE_RECENTS, options.hideSideRecents)
                .putBoolean(DELETE_SHARED_IMAGES,options.deleteSharedImages).putBoolean(PARK_MAIN, options.parkMainOnLaunch).remove("allow_secure_capture").remove("rounded_windows").commit();
        if (saved) {
            context.getContentResolver().notifyChange(URI, null);
            // A wakeup only: receivers reread the authenticated provider, never extras.
            try {
                context.sendBroadcast(new android.content.Intent(Actions.SETTINGS_CHANGED).setPackage(Packages.SYSTEM_UI));
            } catch(RuntimeException unavailable) { /* The provider observer remains the primary channel. */ }
        }
        return saved;
    }

    static Bundle bundle(WorkspaceOptions options) {
        Bundle result = new Bundle();
        result.putBoolean(LUNAR, options.showLunar); result.putInt(SWAP, options.swapMode);
        result.putInt(ENTRY_GESTURE, options.entryGesture);
        result.putBoolean(KEEP_ON_LOCK, options.keepOnLock);
        result.putBoolean(SCROLL_SIDE_TASKS, options.scrollSideTasks);
        result.putInt(LANDSCAPE_VIDEO, options.landscapeVideo);
        result.putBoolean(HIDE_RECENTS, options.hideSideRecents);
        result.putBoolean(PARK_MAIN, options.parkMainOnLaunch);
        result.putBoolean(DELETE_SHARED_IMAGES,options.deleteSharedImages);
        return result;
    }

    static WorkspaceOptions decode(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("Settings provider returned no snapshot");
        return new WorkspaceOptions(0, bundle.getInt(SWAP, 0),
                bundle.getInt(ENTRY_GESTURE, WorkspaceOptions.STATUS_BAR_INWARD),
                bundle.getBoolean(KEEP_ON_LOCK, false), bundle.getBoolean(SCROLL_SIDE_TASKS, false), bundle.getBoolean(LUNAR, true),
                bundle.getInt(LANDSCAPE_VIDEO, WorkspaceOptions.VIDEO_TURNED),
                bundle.getBoolean(HIDE_RECENTS, false),
                bundle.getBoolean(PARK_MAIN, false),bundle.getBoolean(DELETE_SHARED_IMAGES,false));
    }
}
