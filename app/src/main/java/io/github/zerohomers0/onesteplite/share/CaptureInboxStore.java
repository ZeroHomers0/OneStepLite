package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validated captures enter the short-lived drag cache; legacy inbox rows remain readable. */
final class CaptureInboxStore {
    private CaptureInboxStore(){}
    static void shared(Context context,Intent intent) {
        java.util.List<android.net.Uri> delivered=new java.util.ArrayList<>(new SharePayload(intent.getClipData()).uris);
        if(delivered.isEmpty())return;
        DragCacheStore.markShared(context,intent.getClipData());
        new Thread(()->{
            try {
            if(!WorkspaceSettingsStore.read(context).deleteSharedImages)return;
            synchronized(ContentInboxProvider.class) {
                JSONArray rows=ContentInboxProvider.rows(context);boolean changed=false;
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.optJSONObject(i);
                    if(row==null || row.optBoolean("staged") || !ContentCapturePolicy.cleanupAfterShare(true,row.optBoolean("captured"),
                            row.has("document_uri"),row.optString("mime"))
                            || !delivered.contains(ContentInboxProvider.uri(row.optString("id"))))continue;
                    try{row.put("delete_after",System.currentTimeMillis()+10*60_000L);changed=true;}
                    catch(org.json.JSONException invalid){android.util.Log.w(Tags.PROBE,"capture cleanup mark failed",invalid);}
                }
                if(changed)context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit()
                        .putString("rows",rows.toString()).apply();
            }
            }catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"shared image cleanup deferred",unavailable);}
        },"SharedImageRetention").start();
    }
    static Bundle store(Context context,byte[] encoded) {
        long identity=android.os.Binder.clearCallingIdentity();
        try {
            Bundle result=storeAsOwner(context,encoded);
            ShareDiagnostics.event(ShareDiagnostics.Stage.IMAGE_STORE,ShareDiagnostics.Code.ACCEPTED);
            return result;
        } catch(RuntimeException failure){
            ShareDiagnostics.event(ShareDiagnostics.Stage.IMAGE_STORE,ShareDiagnostics.Code.FAILED);
            throw failure;
        }
        finally {android.os.Binder.restoreCallingIdentity(identity);}
    }
    private static Bundle storeAsOwner(Context context,byte[] encoded) {
        if(encoded==null || encoded.length>ContentCapturePolicy.MAX_ENCODED)throw new IllegalArgumentException("Oversized capture");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if(!ContentCapturePolicy.validImage(encoded.length,options.outWidth,options.outHeight)
                || !"image/jpeg".equals(options.outMimeType))throw new IllegalArgumentException("Invalid capture");
        try {
            return DragCacheStore.store(context,encoded,"主窗截取.jpg","image/jpeg");
        } catch(java.io.IOException failure){
            throw new IllegalStateException("Capture store failed",failure);
        }
    }

    static Bundle discard(Context context,String id) {
        if(!ContentInboxPolicy.validId(id))throw new IllegalArgumentException("Invalid capture identity");
        if(DragCacheStore.contains(context,id)){DragCacheStore.discard(context,id);return new Bundle();}
        synchronized(ContentInboxProvider.class) {
            JSONArray old=ContentInboxProvider.rows(context),retained=new JSONArray();boolean found=false;
            for(int i=0;i<old.length();i++) {
                JSONObject row=old.optJSONObject(i);
                if(id.equals(row.optString("id")) && row.optBoolean("captured"))found=true;else retained.put(row);
            }
            if(found) {
                if(!context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",retained.toString()).commit())
                    throw new IllegalStateException("Capture discard failed");
                context.revokeUriPermission(ContentInboxProvider.uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
                File file=ContentInboxProvider.file(context,id);
                if(file.exists() && !file.delete())android.util.Log.w(Tags.PROBE,"capture discard file retained");
            }
            return new Bundle();
        }
    }
}
