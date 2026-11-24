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
        String saved=ContentAccessCompat.pins(context);
        if(!editing && saved!=null && saved.trim().isEmpty())return new ArrayList<>();
        return load(context,content,count,editing,QuickAppOrder.decode(saved));
    }

    static List<ShareTarget> load(Context context,ClipDescription content,int count,boolean editing,List<String> pins) {
        String mime=contentMime(content);
        ClipDescription effective=editing?content:new ClipDescription("内容",new String[]{mime});
        LinkedHashMap<String,ResolveInfo> all=new LinkedHashMap<>();
        Set<String> matching=new LinkedHashSet<>();
        for(String action:new String[]{Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE}) {
            collect(context,new Intent(action).setType("*/*"),all,null);
            if(!editing && (count==0 || action.equals(count>1 && !mime.startsWith("text/")?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND)))
                collect(context,new Intent(action).setType(mime),all,matching);
        }
        List<String> keys=pins.isEmpty()?new ArrayList<>(all.keySet()):new ArrayList<>(pins);
        java.util.Map<String,ShareTarget> extra=new LinkedHashMap<>();
        for(ShareTarget action:ShareActions.load(context,effective,count,editing))extra.put(action.key(),action);
        for(ShareTarget action:ConversationShareCompat.load(context,effective,count,editing))extra.put(action.key(),action);
        if(editing || pins.isEmpty())for(String key:extra.keySet())if(!keys.contains(key))keys.add(key);
        if(editing)for(String key:all.keySet())if(!keys.contains(key))keys.add(key);
        List<ShareTarget> result=new ArrayList<>();
        for(String key:keys) {
            if(ShareActions.retired(key))continue;
            if(extra.containsKey(key)){result.add(extra.get(key));continue;}
            ComponentName component=ComponentName.unflattenFromString(key);if(component==null)continue;
            if(!editing && (!ShareActions.operation(component).isEmpty() || !ShareRoutes.activity(component).equals(component)))continue;
            ResolveInfo info=all.get(key);
            if(info==null && !editing)continue;
            String label=info==null?component.getPackageName()+"（入口已失效）":info.loadLabel(context.getPackageManager()).toString();
            android.graphics.drawable.Drawable icon=info==null?context.getDrawable(android.R.drawable.ic_menu_share)
                    :info.loadIcon(context.getPackageManager());
            result.add(new ShareTarget(component,label,icon,info!=null && (editing || matching.contains(key)),info==null?"应用未安装或入口已失效":""));
        }
        if(pins.isEmpty())result.sort((a,b)->{
            int label=a.label.compareToIgnoreCase(b.label);return label!=0?label:a.key().compareTo(b.key());
        });
        return result;
    }

    static Set<String> windowPackages(Context context,ClipDescription content,int count) {
        String mime=contentMime(content);
        Set<String> packages=new LinkedHashSet<>();
        for(String action:new String[]{Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE}) {
            if(count!=0 && !action.equals(count>1 && !mime.startsWith("text/")
                    ?Intent.ACTION_SEND_MULTIPLE:Intent.ACTION_SEND))continue;
            for(ResolveInfo info:context.getPackageManager().queryIntentActivities(new Intent(action).setType(mime),0)) {
                if(info.activityInfo!=null && info.activityInfo.exported)packages.add(info.activityInfo.packageName);
            }
        }
        return packages;
    }

    static String contentMime(ClipDescription description) {
        String result=null;
        for(int i=0;i<description.getMimeTypeCount();i++){
            String type=description.getMimeType(i);
            if(type.equals("text/plain") || type.equals("text/uri-list"))continue;
            if(result==null)result=type;
            else if(!result.equals(type)){
                int slash=result.indexOf('/');String family=slash>0?result.substring(0,slash):"*";
                result=type.startsWith(family+"/")?family+"/*":"*/*";
            }
        }
        return result!=null?(result.startsWith("text/")?"text/plain":result):description.hasMimeType("text/plain")?"text/plain":"application/octet-stream";
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
