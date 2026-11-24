package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

/** A published conversation is a share activity plus its standard shortcut ID, never a guessed deep link. */
final class ShareRoutes {
    private static final String SUFFIX="#conversation:";
    private ShareRoutes(){}
    static ComponentName conversation(ComponentName target,String id) {
        if(id==null || id.isEmpty() || id.length()>256)throw new IllegalArgumentException("Invalid conversation");
        return new ComponentName(target.getPackageName(),target.getClassName()+SUFFIX+
                Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP|Base64.URL_SAFE));
    }
    static ComponentName activity(ComponentName target) {
        int split=target.getClassName().indexOf(SUFFIX);
        return split<0?target:new ComponentName(target.getPackageName(),target.getClassName().substring(0,split));
    }
    static Intent intent(SharePayload payload,ComponentName target) {
        Intent result=payload.intent(activity(target));String name=target.getClassName();int split=name.indexOf(SUFFIX);
        if(split>=0){String id=new String(Base64.decode(name.substring(split+SUFFIX.length()),Base64.NO_WRAP|Base64.URL_SAFE),StandardCharsets.UTF_8);
            if(id.isEmpty() || id.length()>256)throw new IllegalArgumentException("Invalid conversation");
            result.putExtra(Intent.EXTRA_SHORTCUT_ID,id);}
        result.setClipData(payload.clip);return result;
    }
    static void copyShortcut(Intent source,Intent target) {
        String id=source.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if(id!=null && !id.isEmpty() && id.length()<=256)target.putExtra(Intent.EXTRA_SHORTCUT_ID,id);
    }
}
