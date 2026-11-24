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

    static int arrowOffset(DragEvent event) {
        int declared=ContentDragShadow.offset(event.getClipDescription());
        if(declared==0)return 0;
        android.os.PersistableBundle extras=event.getClipDescription().getExtras();
        int touch=extras.getInt("onesteplite_shadow_touch_y",0);
        if(touch<=0)return declared;
        try {
            float actual=((Number)XposedHelpers.callMethod(event,"getOffsetY")).floatValue();
            if(Float.isFinite(actual) && actual>0 && actual<=4096) {
                int scaled=Math.round(declared*actual/touch);if(scaled>0 && scaled<=2048)return scaled;
            }
        } catch(NoSuchMethodError | RuntimeException unavailable) { /* Older drag events retain the declared physical offset. */ }
        return declared;
    }
    static float shadowHalfWidth(DragEvent event){
        if(ContentDragShadow.offset(event.getClipDescription())<=0)return 0;
        try{float actual=((Number)XposedHelpers.callMethod(event,"getOffsetX")).floatValue();
            if(Float.isFinite(actual) && actual>0 && actual<=2048)return actual;
        }catch(NoSuchMethodError | RuntimeException unavailable){/* Use the declared width on older releases. */}
        android.os.PersistableBundle extras=event.getClipDescription().getExtras();
        int value=extras==null?0:extras.getInt("onesteplite_shadow_half_width",0);return value>0 && value<=2048?value:0;
    }

    static DropPermissionContract delegate(DragEvent event,android.content.ClipData clip) throws Exception {
        Object permissions=XposedHelpers.callMethod(event,"getDragAndDropPermissions");
        if(!(permissions instanceof android.os.IInterface))throw new SecurityException("Missing drop permissions");
        return DropPermissionContract.request(((android.os.IInterface)permissions).asBinder(),clip);
    }
}
