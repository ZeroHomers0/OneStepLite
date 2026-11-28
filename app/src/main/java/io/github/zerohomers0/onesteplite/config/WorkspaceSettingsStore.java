package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** The module app owns persistence. SystemUI receives only a read-only snapshot. */
final class WorkspaceSettingsStore {
    static final Uri URI = Uri.parse(SettingsProtocol.OPTIONS_URI);
    static final String READ = SettingsProtocol.READ_OPTIONS, LUNAR = SettingsProtocol.KEY_LUNAR,
            TIME = SettingsProtocol.KEY_TIME, DATE = SettingsProtocol.KEY_DATE, SWAP = SettingsProtocol.KEY_SWAP,
            ENTRY_GESTURE = SettingsProtocol.KEY_ENTRY_GESTURE, KEEP_ON_LOCK = SettingsProtocol.KEY_KEEP_ON_LOCK,
            SCROLL_SIDE_TASKS = SettingsProtocol.KEY_SCROLL_SIDE_TASKS,
            LANDSCAPE_VIDEO = SettingsProtocol.KEY_LANDSCAPE_VIDEO,
            HIDE_RECENTS = SettingsProtocol.KEY_HIDE_RECENTS, PARK_MAIN = SettingsProtocol.KEY_PARK_MAIN,
            DELETE_SHARED_IMAGES = SettingsProtocol.KEY_DELETE_SHARED_IMAGES;
    static final String DRAG_SHARE=SettingsProtocol.KEY_DRAG_SHARE;
    static final String THEME=SettingsProtocol.KEY_THEME;
    static final String FOLLOW_TOP_BAR_SIDE=SettingsProtocol.KEY_FOLLOW_TOP_BAR_SIDE;
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
                preferences.getBoolean(PARK_MAIN, false),preferences.getBoolean(DELETE_SHARED_IMAGES,false),preferences.getInt(THEME,0),
                preferences.getBoolean(FOLLOW_TOP_BAR_SIDE,true),preferences.getBoolean(DRAG_SHARE,true),
                preferences.getBoolean(TIME,true),preferences.getBoolean(DATE,true));
    }

    static boolean write(Context context, WorkspaceOptions options) {
        if (options.entryGesture != read(context).entryGesture
                && !EntryGesturePolicy.canSelect(options.entryGesture,
                        EntryGestureSettings.lineHidden(context))) return false;
        boolean saved = preferences(context).edit().remove("hidden_buttons").putBoolean(LUNAR, options.showLunar)
                .putBoolean(TIME, options.showTime).putBoolean(DATE, options.showDate)
                .putInt(THEME,options.themeMode).putInt(SWAP, options.swapMode).putInt(ENTRY_GESTURE, options.entryGesture)
                .putBoolean(FOLLOW_TOP_BAR_SIDE,options.followTopBarSide).putBoolean(DRAG_SHARE,options.dragShareEnabled)
                .putBoolean(KEEP_ON_LOCK, options.keepOnLock)
                .putBoolean(SCROLL_SIDE_TASKS, options.scrollSideTasks)
                .putInt(LANDSCAPE_VIDEO, options.landscapeVideo)
                .putBoolean(HIDE_RECENTS, options.hideSideRecents)
                .putBoolean(DELETE_SHARED_IMAGES,options.deleteSharedImages).putBoolean(PARK_MAIN, options.parkMainOnLaunch).remove("allow_secure_capture").remove("rounded_windows").commit();
        if (saved) {
            context.getContentResolver().notifyChange(URI, null);
            // A wakeup only: receivers reread the authenticated provider, never extras.
            try {
                context.sendBroadcast(new android.content.Intent(Actions.SETTINGS_CHANGED).setPackage(Packages.SYSTEM_UI).putExtra(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT),
                        Actions.SETTINGS_WAKEUP_PERMISSION);
            } catch(RuntimeException unavailable) { /* The provider observer remains the primary channel. */ }
        }
        return saved;
    }

    static Bundle bundle(WorkspaceOptions options) {
        Bundle result = new Bundle();
        result.putInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT);
        result.putInt(THEME,options.themeMode);result.putBoolean(LUNAR, options.showLunar);
        result.putBoolean(TIME,options.showTime);result.putBoolean(DATE,options.showDate);result.putInt(SWAP, options.swapMode);
        result.putInt(ENTRY_GESTURE, options.entryGesture);
        result.putBoolean(KEEP_ON_LOCK, options.keepOnLock);
        result.putBoolean(SCROLL_SIDE_TASKS, options.scrollSideTasks);
        result.putInt(LANDSCAPE_VIDEO, options.landscapeVideo);
        result.putBoolean(HIDE_RECENTS, options.hideSideRecents);
        result.putBoolean(PARK_MAIN, options.parkMainOnLaunch);
        result.putBoolean(DELETE_SHARED_IMAGES,options.deleteSharedImages);
        result.putBoolean(FOLLOW_TOP_BAR_SIDE,options.followTopBarSide);
        result.putBoolean(DRAG_SHARE,options.dragShareEnabled);
        return result;
    }

    static WorkspaceOptions decode(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("Settings provider returned no snapshot");
        int protocol=bundle.getInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT);
        if(!ProtocolVersion.compatible(protocol))throw new IllegalArgumentException("Unsupported settings protocol");
        return new WorkspaceOptions(0, bundle.getInt(SWAP, 0),
                bundle.getInt(ENTRY_GESTURE, WorkspaceOptions.STATUS_BAR_INWARD),
                bundle.getBoolean(KEEP_ON_LOCK, false), bundle.getBoolean(SCROLL_SIDE_TASKS, false), bundle.getBoolean(LUNAR, true),
                bundle.getInt(LANDSCAPE_VIDEO, WorkspaceOptions.VIDEO_TURNED),
                bundle.getBoolean(HIDE_RECENTS, false),
                bundle.getBoolean(PARK_MAIN, false),bundle.getBoolean(DELETE_SHARED_IMAGES,false),bundle.getInt(THEME,0),
                bundle.getBoolean(FOLLOW_TOP_BAR_SIDE,true),bundle.getBoolean(DRAG_SHARE,true),
                bundle.getBoolean(TIME,true),bundle.getBoolean(DATE,true));
    }
}
