package io.github.zerohomers0.onesteplite;

import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Enumerates both share actions, preserving unavailable pins without moving drop targets. */
final class ShareTargetCatalog {
    private ShareTargetCatalog() {}

    static List<ShareTarget> load(Context context,ClipDescription content,int count,boolean editing) {
        return load(context,content,count,editing,QuickAppOrder.decode(ContentAccessCompat.pins(context)));
    }

    static List<ShareTarget> load(Context context,ClipDescription content,int count,boolean editing,List<String> pins) {
        LinkedHashMap<String,ResolveInfo> all=new LinkedHashMap<>();
        Set<String> matching=new LinkedHashSet<>();
        for(String action:new String[]{Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE}) {
            collect(context,new Intent(action).setType("*/*"),all,null);
            if(!editing && (count==0 || action.equals(count>1?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND)))
                for(int i=0;i<content.getMimeTypeCount();i++)
                    collect(context,new Intent(action).setType(content.getMimeType(i)),all,matching);
        }
        List<String> keys=pins.isEmpty()?new ArrayList<>(all.keySet()):new ArrayList<>(pins);
        if(editing)for(String key:all.keySet())if(!keys.contains(key))keys.add(key);
        List<ShareTarget> result=new ArrayList<>();
        for(String key:keys) {
            ComponentName component=ComponentName.unflattenFromString(key);if(component==null)continue;
            ResolveInfo info=all.get(key);
            String label=info==null?component.getPackageName()+"（入口已失效）":info.loadLabel(context.getPackageManager()).toString();
            android.graphics.drawable.Drawable icon=info==null?context.getDrawable(android.R.drawable.ic_menu_share)
                    :info.loadIcon(context.getPackageManager());
            result.add(new ShareTarget(component,label,icon,info!=null && (editing || matching.contains(key))));
        }
        if(pins.isEmpty())result.sort((a,b)->{
            int label=a.label.compareToIgnoreCase(b.label);return label!=0?label:a.key().compareTo(b.key());
        });
        return result;
    }

    private static void collect(Context context,Intent intent,LinkedHashMap<String,ResolveInfo> all,Set<String> matched) {
        for(ResolveInfo info:context.getPackageManager().queryIntentActivities(intent,0)) {
            if(info.activityInfo==null || !info.activityInfo.exported)continue;
            if(ContentInboxProvider.MODULE.equals(info.activityInfo.packageName))continue;
            String key=new ComponentName(info.activityInfo.packageName,info.activityInfo.name).flattenToString();
            all.put(key,info);if(matched!=null)matched.add(key);
        }
    }
}
