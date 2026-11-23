package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.view.DragEvent;
import java.io.Closeable;
import de.robv.android.xposed.XposedHelpers;

/** Scoped access to drag capabilities and per-user share preferences. */
final class ContentAccessCompat {
    private static final String KEY = ShortcutSettingsClient.LEGACY_SHARE;

    private ContentAccessCompat() {}

    static Context privateStorage(Context context) {
        return (Context)XposedHelpers.callMethod(context,"createCredentialProtectedStorageContext");
    }

    static boolean currentProcessUser() {
        return CurrentUser.id() == Process.myUid() / 100000;
    }

    static String pins(Context context) {
        if (!currentProcessUser()) throw new IllegalStateException("Different active user");
        try {
            String pins=ShortcutSettingsClient.read(context,ShortcutSettingsClient.SHARE);
            if(pins!=null)return pins;
        } catch(RuntimeException unavailable) { /* Keep legacy pins until the provider is available. */ }
        return Settings.Secure.getString(context.getContentResolver(), KEY);
    }

    static boolean savePins(Context context, String value) {
        try {
            if(!currentProcessUser())return false;
            Boolean saved=ShortcutSettingsClient.write(context,ShortcutSettingsClient.SHARE,value);
            return saved!=null?saved:Settings.Secure.putString(context.getContentResolver(), KEY, value);
        } catch (RuntimeException unavailable) { return false; }
    }

    static Closeable take(DragEvent event) {
        Object permission = XposedHelpers.callMethod(event, "getDragAndDropPermissions");
        if (permission == null) throw new IllegalStateException("Missing content permission");
        XposedHelpers.callMethod(permission, "takeTransient");
        return () -> XposedHelpers.callMethod(permission, "release");
    }

    static DropPermissionContract delegate(DragEvent event,android.content.ClipData clip) throws Exception {
        Object permissions=XposedHelpers.callMethod(event,"getDragAndDropPermissions");
        if(!(permissions instanceof android.os.IInterface))throw new SecurityException("Missing drop permissions");
        return DropPermissionContract.request(((android.os.IInterface)permissions).asBinder(),clip);
    }
}
