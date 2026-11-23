package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider-side capture records use the existing bounded inbox and same-user read grants. */
final class CaptureInboxStore {
    private CaptureInboxStore(){}
    static Bundle store(Context context,byte[] encoded) {
        if(encoded==null || encoded.length>ContentCapturePolicy.MAX_ENCODED)throw new IllegalArgumentException("Oversized capture");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if(!ContentCapturePolicy.validImage(encoded.length,options.outWidth,options.outHeight)
                || !"image/jpeg".equals(options.outMimeType))throw new IllegalArgumentException("Invalid capture");
        synchronized(ContentInboxProvider.class) {
            String id=UUID.randomUUID().toString();File file=ContentInboxProvider.file(context,id);
            try {
                try(FileOutputStream output=new FileOutputStream(file)){output.write(encoded);}
                JSONObject row=new JSONObject().put("id",id).put("name","主窗截取.jpg").put("mime","image/jpeg")
                        .put("captured",true).put("time",System.currentTimeMillis());
                ContentInboxProvider.append(context,new JSONArray().put(row));
                context.grantUriPermission(Packages.SYSTEM_UI,ContentInboxProvider.uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Bundle result=new Bundle();result.putString("id",id);result.putString("uri",ContentInboxProvider.uri(id).toString());return result;
            } catch(Exception failure){
                try{discard(context,id);}catch(RuntimeException cleanup){android.util.Log.w(Tags.PROBE,"capture metadata rollback failed",cleanup);}
                if(file.exists() && !file.delete())android.util.Log.w(Tags.PROBE,"capture rollback failed");
                throw new IllegalStateException("Capture store failed",failure);}
        }
    }

    static Bundle discard(Context context,String id) {
        if(!ContentInboxPolicy.validId(id))throw new IllegalArgumentException("Invalid capture identity");
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
