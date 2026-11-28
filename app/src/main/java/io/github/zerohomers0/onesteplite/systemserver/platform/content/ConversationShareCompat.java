package io.github.zerohomers0.onesteplite;

import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Reads only published sharing shortcuts through the authenticated system relay. */
final class ConversationShareCompat {
    private ConversationShareCompat(){}
    static List<ShareTarget> load(Context context,ClipDescription description,int count,boolean editing) {
        List<ShareTarget> result=new ArrayList<>();
        try {
            Bundle request=new Bundle();request.putParcelable("types",description);request.putInt("count",count);request.putBoolean("editing",editing);
            Bundle reply=context.getContentResolver().call(android.net.Uri.parse(ContentProtocol.ROOT_URI),"conversation_list",null,request);
            ArrayList<Bundle> rows=reply==null?null:reply.getParcelableArrayList("rows",Bundle.class);if(rows==null)return result;
            for(Bundle row:rows) {
                ComponentName activity=ComponentName.unflattenFromString(row.getString("activity",""));if(activity==null)continue;
                android.graphics.drawable.Drawable icon=context.getPackageManager().getApplicationIcon(activity.getPackageName());
                result.add(new ShareTarget(ShareRoutes.conversation(activity,row.getString("id")),row.getString("label","会话"),icon,row.getBoolean("enabled")));
            }
        }catch(Exception unavailable){/* No published shortcut or missing platform capability leaves ordinary share entries intact. */}
        return result;
    }
    static Bundle query(Context context,Bundle input) {
        Bundle reply=new Bundle();LinkedHashMap<String,Bundle> rows=new LinkedHashMap<>();
        try {
            ClipDescription types=input==null?null:input.getParcelable("types",ClipDescription.class);
            int count=input==null?0:input.getInt("count");boolean editing=input!=null && input.getBoolean("editing");
            List<String> queries=new ArrayList<>();queries.add("*/*");
            if(types!=null)for(int i=0;i<types.getMimeTypeCount();i++)if(!queries.contains(types.getMimeType(i)))queries.add(types.getMimeType(i));
            ShortcutManager manager=context.getSystemService(ShortcutManager.class);
            for(String mime:queries) {
                IntentFilter filter=new IntentFilter(Intent.ACTION_SEND);filter.addDataType(mime);
                Object found=XposedHelpers.callMethod(manager,"getShareTargets",filter);if(!(found instanceof List))continue;
                for(Object candidate:(List<?>)found) {
                    ShortcutInfo shortcut=(ShortcutInfo)XposedHelpers.callMethod(candidate,"getShortcutInfo");
                    ComponentName activity=(ComponentName)XposedHelpers.callMethod(candidate,"getTargetComponent");
                    if(activity==null || shortcut==null || !shortcut.isEnabled() || !android.os.Process.myUserHandle().equals(shortcut.getUserHandle())
                            || !activity.getPackageName().equals(shortcut.getPackage()) || shortcut.getId().length()>256
                            || !context.getPackageManager().getActivityInfo(activity,0).exported)continue;
                    String key=ShareRoutes.conversation(activity,shortcut.getId()).flattenToString();
                    if(!rows.containsKey(key) && rows.size()>=32)continue;
                    Bundle row=rows.get(key);if(row==null){row=new Bundle();row.putString("id",shortcut.getId());
                        row.putString("activity",activity.flattenToString());CharSequence label=shortcut.getShortLabel();
                        CharSequence app=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(activity.getPackageName(),0));
                        row.putString("label",app+" · "+(label==null?"会话":label.toString().substring(0,Math.min(128,label.length()))));rows.put(key,row);}
                    if(editing || !"*/*".equals(mime)) {
                        Intent intent=new Intent(count>1?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND).setType(mime).setComponent(activity);
                        row.putBoolean("enabled",row.getBoolean("enabled") || !context.getPackageManager().queryIntentActivities(intent,0).isEmpty());
                    }
                }
            }
        }catch(Throwable unavailable){rows.clear();}
        reply.putParcelableArrayList("rows",new ArrayList<>(rows.values()));return reply;
    }
}
