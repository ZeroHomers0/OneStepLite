package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Module-owned shortcut preferences with read access for SystemUI. */
final class ShortcutSettingsClient {
    static final Uri URI=Uri.parse("content://io.github.zerohomers0.onesteplite.settings/shortcuts");
    static final String APPS="apps",SHARE="share";
    static final String LEGACY_APPS="onesteplite_quick_app_pins_v1",LEGACY_SHARE="onesteplite_share_targets_v1";
    private ShortcutSettingsClient(){}

    static String readForUser(Context context,String kind,int user) {
        return user==android.os.Process.myUid()/100000?read(context,kind):null;
    }

    static String read(Context context,String kind) {
        Bundle result=context.getContentResolver().call(URI,"read_shortcuts",kind,null);
        if(result==null)throw new IllegalStateException("Shortcut settings unavailable");
        return result.getString("pins");
    }

    static Boolean write(Context context,String kind,String pins) {
        try {
            Bundle data=new Bundle();data.putString("pins",pins);
            Bundle result=context.getContentResolver().call(URI,"write_shortcuts",kind,data);
            return result==null?null:result.getBoolean("saved");
        } catch(RuntimeException unavailable){return null;}
    }
}
