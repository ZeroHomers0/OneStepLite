package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Keeps app and share pin drafts separate; only explicit saves become authoritative. */
final class ShortcutSettingsStore {
    private ShortcutSettingsStore(){}
    static Bundle call(Context context,String method,String kind,Bundle data) {
        if(!ShortcutSettingsClient.APPS.equals(kind) && !ShortcutSettingsClient.SHARE.equals(kind))
            throw new IllegalArgumentException("Unknown shortcut kind");
        SharedPreferences preferences=context.createDeviceProtectedStorageContext()
                .getSharedPreferences("workspace_shortcuts",Context.MODE_PRIVATE);
        Bundle result=new Bundle();
        if("read_shortcuts".equals(method)){result.putString("pins",preferences.getString(kind,null));return result;}
        if(!"write_shortcuts".equals(method))throw new UnsupportedOperationException("Unknown shortcut operation");
        String pins=data==null?null:data.getString("pins");
        if(pins==null || pins.length()>32768 || pins.split("\n").length>256)
            throw new IllegalArgumentException("Invalid shortcut selection");
        boolean saved=preferences.edit().putString(kind,pins).commit();result.putBoolean("saved",saved);
        if(saved){context.getContentResolver().notifyChange(ShortcutSettingsClient.URI,null);
            context.getContentResolver().notifyChange(WorkspaceSettingsStore.URI,null);}
        return result;
    }
}
