package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Module-owned shortcut preferences with read access for SystemUI. */
final class ShortcutSettingsClient {
    static final Uri URI=Uri.parse(SettingsProtocol.SHORTCUTS_URI);
    static final String APPS=SettingsProtocol.KEY_KIND_APPS,SHARE=SettingsProtocol.KEY_KIND_SHARE;
    static final String LEGACY_APPS=SettingsProtocol.LEGACY_APPS,LEGACY_SHARE=SettingsProtocol.LEGACY_SHARE;
    private ShortcutSettingsClient(){}

    static String readForUser(Context context,String kind,int user) {
        return user==android.os.Process.myUid()/100000?read(context,kind):null;
    }

    static String read(Context context,String kind) {
        Bundle request=new Bundle();request.putInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT);
        Bundle result=context.getContentResolver().call(URI,SettingsProtocol.READ_SHORTCUTS,kind,request);
        if(result==null)throw new IllegalStateException("Shortcut settings unavailable");
        return result.getString(SettingsProtocol.EXTRA_PINS);
    }

    static Boolean write(Context context,String kind,String pins) {
        try {
            Bundle data=new Bundle();data.putString(SettingsProtocol.EXTRA_PINS,pins);
            data.putInt(WorkspaceContract.EXTRA_PROTOCOL_VERSION,ProtocolVersion.CURRENT);
            Bundle result=context.getContentResolver().call(URI,SettingsProtocol.WRITE_SHORTCUTS,kind,data);
            return result==null?null:result.getBoolean(SettingsProtocol.EXTRA_SAVED);
        } catch(RuntimeException unavailable){return null;}
    }
}
