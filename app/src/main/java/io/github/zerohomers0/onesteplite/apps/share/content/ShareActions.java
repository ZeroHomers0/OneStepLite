package io.github.zerohomers0.onesteplite;

import android.app.SearchManager;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** Explicit user drop operations; no recognition, network request or lookup while hidden. */
final class ShareActions {
    static final String STAGE="stage",SEARCH="search",TRANSLATE="translate",MAP="map",EDIT="edit",IMAGE_SEARCH="image_search";
    private static final String PREFIX="drag_operation_";
    private ShareActions(){}
    static String operation(ComponentName target) {
        if(target==null || !ContentInboxProvider.MODULE.equals(target.getPackageName()))return "";
        String name=target.getClassName();if(!name.startsWith(PREFIX))return "";
        String value=name.substring(PREFIX.length());return known(value)?value:"";
    }
    static boolean known(String value) {
        return STAGE.equals(value)||SEARCH.equals(value)||TRANSLATE.equals(value)
                ||MAP.equals(value)||EDIT.equals(value)||IMAGE_SEARCH.equals(value);
    }
    static boolean retired(String key){
        ComponentName target=ComponentName.unflattenFromString(key);if(target==null || !ContentInboxProvider.MODULE.equals(target.getPackageName()))return false;
        return "drag_operation_note".equals(target.getClassName()) || "drag_operation_paste".equals(target.getClassName());
    }
    static List<ShareTarget> load(Context context,ClipDescription types,int count,boolean editing) {
        String[] keys={STAGE,SEARCH,TRANSLATE,MAP,EDIT,IMAGE_SEARCH};
        String[] labels={"暂存","搜索文字","翻译文字","地图查询","编辑图片","图片搜索"};
        List<ShareTarget> result=new ArrayList<>();boolean image=types.hasMimeType("image/*"),text=types.hasMimeType("text/plain");
        for(int i=0;i<keys.length;i++) {
            String key=keys[i];boolean imageAction=EDIT.equals(key)||IMAGE_SEARCH.equals(key);
            boolean compatible=STAGE.equals(key) || (imageAction?image && count<=1:text && !image);
            boolean available=true;
            if(!STAGE.equals(key) && !IMAGE_SEARCH.equals(key))try {
                SharePayload sample=new SharePayload(imageAction?new android.content.ClipData("图片",new String[]{"image/jpeg"},
                        new android.content.ClipData.Item(Uri.parse("content://"+ContentInboxProvider.AUTHORITY+"/preview")))
                        :android.content.ClipData.newPlainText("文字","预览"));
                available=!context.getPackageManager().queryIntentActivities(intent(context,sample,key),0).isEmpty();
            }catch(RuntimeException unavailable){available=false;}
            if(!editing && !available)continue;
            String reason=available?"":"未安装可用应用";
            result.add(new ShareTarget(new ComponentName(ContentInboxProvider.MODULE,PREFIX+key),labels[i],new ShareActionIcon(key),available && (editing||compatible),reason));
        }
        return result;
    }
    static Intent intent(Context context,SharePayload payload,String operation) {
        if(!known(operation) || STAGE.equals(operation))throw new IllegalArgumentException("Not a launch operation");
        String text=payload.text;Intent result;
        if(EDIT.equals(operation)) {
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/"))throw new IllegalArgumentException("Single image required");
            result=new Intent(Intent.ACTION_EDIT).setDataAndType(payload.uris.get(0),payload.mime);
        } else if(IMAGE_SEARCH.equals(operation)) {
            if(payload.uris.size()!=1 || !payload.mime.startsWith("image/"))throw new IllegalArgumentException("Single image required");
            result=new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(ContentInboxProvider.MODULE,VisualSearchActivity.class.getName()));
        } else {
            if(!payload.uris.isEmpty() || text.isEmpty())throw new IllegalArgumentException("Text required");
            if(SEARCH.equals(operation)) {
                result=new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY,text);
                if(context.getPackageManager().queryIntentActivities(result,0).isEmpty())result=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.bing.com/search?q="+Uri.encode(text)));
            } else if(TRANSLATE.equals(operation)) {
                result=new Intent("android.intent.action.TRANSLATE").putExtra(Intent.EXTRA_TEXT,text);
                if(context.getPackageManager().queryIntentActivities(result,0).isEmpty())result=new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.bing.com/translator?from=auto&to=zh-Hans&text="+Uri.encode(text)));
            } else if(MAP.equals(operation)) {
                result=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(text)));
                if(context.getPackageManager().queryIntentActivities(result,0).isEmpty())result=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.bing.com/maps?q="+Uri.encode(text)));
            } else throw new IllegalArgumentException("Unsupported operation");
        }
        result.setClipData(payload.clip);result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(!payload.uris.isEmpty())result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);return result;
    }
}
