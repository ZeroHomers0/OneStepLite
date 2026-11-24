package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Explicit bounded staging uses owned inbox data, independently of system recent files. */
final class ContentShelfStore {
    private ContentShelfStore(){}
    static Bundle call(Context context,String method,Bundle input) {
        if("shelf_list".equals(method))return list(context);
        if("shelf_remove".equals(method)){remove(context,input);return new Bundle();}
        if(!"shelf_add".equals(method))throw new IllegalArgumentException("Unknown shelf operation");
        stage(context,input==null?null:input.getParcelable("clip",ClipData.class));return new Bundle();
    }

    static void stage(Context context,ClipData clip) {
        SharePayload payload=new SharePayload(clip);
        synchronized(ContentInboxProvider.class) {
            try {
                JSONArray rows=ContentInboxProvider.rows(context);
                for(android.net.Uri uri:payload.uris) {
                    if(!ContentInboxProvider.AUTHORITY.equals(uri.getAuthority()))throw new SecurityException("Unowned shelf item");
                    JSONObject found=null;
                    for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("id").equals(uri.getLastPathSegment()))found=rows.getJSONObject(i);
                    if(found==null)throw new IllegalStateException("Expired shelf item");
                    found.put("staged",true).remove("shelf_removed");found.remove("delete_after");
                    context.grantUriPermission(Packages.SYSTEM_UI,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                for(int index=0;index<payload.clip.getItemCount();index++) {
                    CharSequence text=payload.clip.getItemAt(index).getText();if(text==null || text.length()==0)continue;
                    JSONObject found=null;
                    for(int i=0;i<rows.length();i++)if(text.toString().equals(rows.getJSONObject(i).optString("text")))found=rows.getJSONObject(i);
                    if(found==null){found=new JSONObject().put("id",UUID.randomUUID().toString())
                            .put("text",text.toString()).put("name",text.toString()).put("mime","text/plain")
                            .put("time",System.currentTimeMillis());rows.put(found);}
                    found.put("staged",true).remove("shelf_removed");
                }
                int count=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optBoolean("staged"))count++;
                if(count>64)throw new IllegalStateException("Shelf full");
                if(rows.length()>128)throw new IllegalStateException("Inbox full");
                save(context,rows);
            }catch(org.json.JSONException invalid){throw new IllegalStateException(invalid);}
        }
    }
    private static Bundle list(Context context) {
        JSONArray rows=ContentInboxProvider.rows(context),staged=new JSONArray();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(!row.optBoolean("staged"))continue;
            if(!row.has("text"))context.grantUriPermission(Packages.SYSTEM_UI,ContentInboxProvider.uri(row.optString("id")),Intent.FLAG_GRANT_READ_URI_PERMISSION);
            staged.put(row);
        }
        Bundle result=new Bundle();result.putString("rows",staged.toString());
        return result;
    }
    private static void remove(Context context,Bundle input) {
        java.util.ArrayList<String> ids=input==null?null:input.getStringArrayList("ids");
        synchronized(ContentInboxProvider.class) {
            JSONArray rows=ContentInboxProvider.rows(context);
            try{for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                if(row.optBoolean("staged") && (ids==null || ids.contains(row.optString("id"))))
                    row.put("staged",false).put("shelf_removed",true);
            }}catch(org.json.JSONException invalid){throw new IllegalStateException(invalid);}
            save(context,rows);
        }
    }
    private static void save(Context context,JSONArray rows) {
        if(!context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",rows.toString()).commit())
            throw new IllegalStateException("Shelf save failed");
    }
}
