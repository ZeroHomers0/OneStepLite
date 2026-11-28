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
        Bundle result=new Bundle();result.putInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT);
        if(SettingsProtocol.READ_SHORTCUTS.equals(method)){result.putString(SettingsProtocol.EXTRA_PINS,preferences.getString(kind,null));return result;}
        if(!SettingsProtocol.WRITE_SHORTCUTS.equals(method))throw new UnsupportedOperationException("Unknown shortcut operation");
        String pins=data==null?null:data.getString(SettingsProtocol.EXTRA_PINS);
        if(pins==null || pins.length()>32768 || pins.split("\n").length>256)
            throw new IllegalArgumentException("Invalid shortcut selection");
        boolean saved=preferences.edit().putString(kind,pins).commit();result.putBoolean(SettingsProtocol.EXTRA_SAVED,saved);
        if(saved){context.getContentResolver().notifyChange(ShortcutSettingsClient.URI,null);
            context.getContentResolver().notifyChange(WorkspaceSettingsStore.URI,null);}
        return result;
    }
}
