package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Short-lived per-transfer files; never part of the persistent content inbox. */
final class DragCacheStore {
    private static final long TTL_MS=10L*60L*1000L;
    private static final long MAX_TOTAL=256L*1024L*1024L;
    private static final int MAX_ENTRIES=64;
    private static final String PREFS="drag_cache";
    private DragCacheStore(){}

    static Bundle store(Context context,byte[] bytes,String name,String mime) throws IOException {
        if(bytes==null || bytes.length==0)throw new IOException("Empty drag content");
        return store(context,new java.io.ByteArrayInputStream(bytes),name,mime);
    }

    static Bundle storeUri(Context context,Uri source,String name,String mime) throws IOException {
        if(source==null || !"content".equals(source.getScheme()))throw new IOException("Unsupported source URI");
        String resolved=mime;
        if(resolved==null || resolved.isEmpty())try{resolved=context.getContentResolver().getType(source);}
        catch(RuntimeException unavailable){/* Keep the declared MIME. */}
        if(resolved==null || resolved.isEmpty())resolved="application/octet-stream";
        String display=name;
        if(display==null || display.isBlank())try(android.database.Cursor cursor=context.getContentResolver()
                .query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst())display=cursor.getString(0);
        }catch(RuntimeException unavailable){/* Optional metadata. */}
        try(InputStream input=context.getContentResolver().openInputStream(source)){
            if(input==null)throw new IOException("Source is unavailable");
            return store(context,input,display,resolved);
        }
    }

    private static Bundle store(Context context,InputStream input,String name,String mime) throws IOException {
        String id=UUID.randomUUID().toString();
        String cleanMime=mime==null || mime.isBlank()?"application/octet-stream":mime;
        String cleanName=cleanName(name,cleanMime);
        File target=file(context,id),temporary=new File(target.getPath()+".tmp");
        long size=0L;
        try(FileOutputStream output=new FileOutputStream(temporary)) {
            byte[] buffer=new byte[64*1024];int read;
            while((read=input.read(buffer))>=0) {
                if(read==0)continue;
                size+=read;
                if(size>ContentInboxPolicy.MAX_FILE)throw new IOException("Drag content too large");
                output.write(buffer,0,read);
            }
            output.getFD().sync();
        } catch(IOException | RuntimeException failure){
            if(!temporary.delete())temporary.deleteOnExit();
            throw failure;
        }
        if(size==0L){temporary.delete();throw new IOException("Empty drag content");}
        if(!temporary.renameTo(target)){
            temporary.delete();
            throw new IOException("Unable to publish drag content");
        }
        try {
            JSONObject row=new JSONObject().put("id",id).put("name",cleanName).put("mime",cleanMime)
                    .put("time",System.currentTimeMillis()).put("size",size);
            add(context,row);
            Uri uri=uri(id);
            try{context.grantUriPermission(Packages.SYSTEM_UI,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"drag cache grant unavailable",unavailable);}
            Bundle result=new Bundle();result.putString("id",id);result.putString("uri",uri.toString());
            result.putString("mime",cleanMime);result.putString("name",cleanName);return result;
        } catch(org.json.JSONException failure){
            delete(target);
            throw new IllegalStateException("Drag metadata invalid",failure);
        } catch(RuntimeException failure){
            delete(target);
            throw failure;
        }
    }

    static ClipData promoteForStage(Context context,ClipData source) throws Exception {
        if(source==null || !hasDragUri(source))return source;
        ClipData promoted=null;
        for(int i=0;i<source.getItemCount();i++) {
            ClipData.Item item=source.getItemAt(i);ClipData.Item next=null;
            if(isDragUri(item.getUri()))next=new ClipData.Item(promoteOne(context,item.getUri()));
            else if(item.getUri()!=null)next=new ClipData.Item(item.getUri());
            else if(item.getText()!=null)next=new ClipData.Item(item.getText().toString());
            if(next==null)continue;
            if(promoted==null)promoted=new ClipData(new android.content.ClipDescription(source.getDescription()),next);else promoted.addItem(next);
        }
        if(promoted==null)throw new IOException("No staged content");
        return promoted;
    }

    private static Uri promoteOne(Context context,Uri uri) throws Exception {
        JSONObject entry=find(context,uri);
        if(entry==null)throw new IOException("Drag content expired");
        String id=UUID.randomUUID().toString(),oldId=uri.getPathSegments().get(1);
        File source=file(context,oldId),target=ContentInboxProvider.file(context,id);
        boolean copied=false;
        try {
            try(FileInputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(target)) {
                byte[] buffer=new byte[64*1024];int read;
                while((read=input.read(buffer))>=0)if(read>0)output.write(buffer,0,read);
            }
            copied=true;
            String mime=entry.optString("mime","application/octet-stream"),name=entry.optString("name","拖曳内容");
            JSONObject row=new JSONObject().put("id",id).put("name",name).put("mime",mime).put("time",System.currentTimeMillis());
            if(mime.startsWith("image/")) {
                row.put("captured",true);
                ContentInboxProvider.appendCapture(context,row);
            } else ContentInboxProvider.append(context,new JSONArray().put(row));
        }catch(Exception failure) {
            if(copied && target.exists() && !target.delete())target.deleteOnExit();
            throw failure;
        }
        discard(context,oldId);
        return ContentInboxProvider.uri(id);
    }

    private static boolean hasDragUri(ClipData clip){
        for(int i=0;i<clip.getItemCount();i++)if(isDragUri(clip.getItemAt(i).getUri()))return true;return false;
    }

    static boolean isDragUri(Uri uri){
        return uri!=null && ContentInboxProvider.AUTHORITY.equals(uri.getAuthority())
                && uri.getPathSegments().size()==2 && "drag".equals(uri.getPathSegments().get(0))
                && validId(uri.getPathSegments().get(1));
    }

    static Uri uri(String id){return ContentInboxProvider.ROOT.buildUpon().appendPath("drag").appendPath(id).build();}

    static JSONObject find(Context context,Uri uri){
        if(!isDragUri(uri))return null;
        JSONArray rows=load(context);cleanupLocked(context,rows);return find(rows,uri.getPathSegments().get(1));
    }

    static File file(Context context,String id){
        if(!validId(id))throw new IllegalArgumentException("Invalid drag identity");
        return new File(context.getCacheDir(),"drag-"+id+".bin");
    }

    static void discard(Context context,String id){
        JSONArray rows=load(context),kept=new JSONArray();boolean found=false;
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(row!=null && id.equals(row.optString("id")))found=true;else if(row!=null)kept.put(row);
        }
        if(found){
            save(context,kept);
            try{context.revokeUriPermission(uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"drag grant cleanup unavailable",unavailable);}
            delete(file(context,id));
        }
    }

    static boolean contains(Context context,String id){return find(load(context),id)!=null;}

    static void markShared(Context context,ClipData clip){
        if(clip==null)return;
        try {
            JSONArray rows=load(context);boolean changed=false;
            for(int i=0;i<clip.getItemCount();i++) {
                Uri uri=clip.getItemAt(i).getUri();if(!isDragUri(uri))continue;
                JSONObject row=find(rows,uri.getPathSegments().get(1));if(row==null)continue;
                row.put("delete_after",System.currentTimeMillis()+TTL_MS);changed=true;
            }
            if(changed)save(context,rows);
        }catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"drag retention update deferred",unavailable);}
        catch(org.json.JSONException invalid){android.util.Log.w(Tags.PROBE,"drag retention update invalid",invalid);}
    }

    static void cleanup(Context context){JSONArray rows=load(context);cleanupLocked(context,rows);}

    private static void add(Context context,JSONObject added){
        JSONArray old=load(context),all=new JSONArray().put(added);
        for(int i=0;i<old.length();i++)all.put(old.optJSONObject(i));
        cleanupLocked(context,all);
        while(all.length()>MAX_ENTRIES || bytes(all)>MAX_TOTAL) {
            JSONObject victim=all.optJSONObject(all.length()-1);if(victim==null)break;delete(context,victim);all.remove(all.length()-1);
        }
        save(context,all);
    }

    private static void cleanupLocked(Context context,JSONArray rows){
        JSONArray kept=new JSONArray();long now=System.currentTimeMillis();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            long deadline=row.optLong("delete_after",0),created=row.optLong("time",0);
            boolean expired=deadline>0?now>=deadline:created<=0 || now-created>TTL_MS;
            if(expired)delete(context,row);else kept.put(row);
        }
        if(kept.length()!=rows.length())save(context,kept);
    }

    private static void delete(Context context,JSONObject row){
        String id=row.optString("id");
        try{context.revokeUriPermission(uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"drag grant cleanup unavailable",unavailable);}
        delete(file(context,id));
    }
    private static void delete(File file){if(file!=null && file.exists() && !file.delete())file.deleteOnExit();}

    private static long bytes(JSONArray rows){
        long total=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)total+=Math.max(0,row.optLong("size",0));}return total;
    }
    private static JSONObject find(JSONArray rows,String id){
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null && id.equals(row.optString("id")))return row;}return null;
    }
    private static JSONArray load(Context context){
        try{return new JSONArray(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("rows","[]"));}
        catch(org.json.JSONException invalid){return new JSONArray();}
    }
    private static void save(Context context,JSONArray rows){
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("rows",rows.toString()).commit())
            throw new IllegalStateException("Drag cache metadata save failed");
    }
    private static boolean validId(String id){return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    private static String cleanName(String name,String mime){
        String clean=name==null?"":name.replace('\\','/');clean=clean.substring(clean.lastIndexOf('/')+1)
                .replaceAll("\\p{Cntrl}"," ").trim();if(clean.isEmpty())clean=mime.startsWith("image/")?"拖曳图片":"拖曳文件";
        if(clean.codePointCount(0,clean.length())>120)clean=clean.substring(0,clean.offsetByCodePoints(0,120));return clean;
    }
}
