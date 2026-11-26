package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Publishes a module-owned image as a short-lived MediaStore item for direct chat drops. */
final class MediaStoreImageBridge {
    private static final long TTL_MS=10L*60L*1000L;
    private static final String FOLDER=Environment.DIRECTORY_PICTURES+"/.OneStep";
    private static final Handler CLEANUP=new Handler(Looper.getMainLooper());
    private static boolean noMediaAttempted;
    private MediaStoreImageBridge(){}

    static Uri publishUri(Context context,Uri source){
        if(context==null || source==null || !ContentInboxProvider.AUTHORITY.equals(source.getAuthority()))return null;
        return publishOne(context,source);
    }

    static ClipData publish(Context context,ClipData source){
        if(context==null || source==null)return source;
        ClipData result=null;boolean changed=false;
        for(int i=0;i<source.getItemCount();i++) {
            ClipData.Item original=source.getItemAt(i);ClipData.Item item=original;
            Uri uri=original.getUri();
            if(uri!=null) {
                Uri published=publishUri(context,uri);
                if(published!=null && !published.equals(uri)){item=new ClipData.Item(published);changed=true;}
            }
            if(item==null)continue;
            if(result==null)result=new ClipData(new ClipDescription(source.getDescription()),item);else result.addItem(item);
        }
        return changed?result:source;
    }

    private static Uri publishOne(Context context,Uri source){
        String mime=null;
        try{mime=context.getContentResolver().getType(source);}catch(RuntimeException unavailable){}
        if(mime==null || !mime.startsWith("image/"))return null;
        String extension=extension(mime);ensureNoMedia(context);
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,"OneStep-"+UUID.randomUUID()+extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri target=null;
        try{
            target=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(target==null)return null;
            try(InputStream input=context.getContentResolver().openInputStream(source);
                OutputStream output=context.getContentResolver().openOutputStream(target,"w")) {
                if(input==null || output==null)throw new java.io.IOException("MediaStore stream unavailable");
                byte[] buffer=new byte[64*1024];int read;
                while((read=input.read(buffer))>=0)if(read>0)output.write(buffer,0,read);
                output.flush();
            }
            ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);
            context.getContentResolver().update(target,complete,null,null);
            scheduleDelete(context.getApplicationContext(),target);
            return target;
        }catch(Exception failure){
            if(target!=null)try{context.getContentResolver().delete(target,null,null);}catch(RuntimeException ignored){}
            android.util.Log.w(Tags.PROBE,"MediaStore image publication failed",failure);
            return null;
        }
    }

    private static synchronized void ensureNoMedia(Context context){
        if(noMediaAttempted)return;noMediaAttempted=true;
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,".nomedia");
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri marker=null;
        try{
            marker=context.getContentResolver().insert(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
            if(marker==null)return;
            try(OutputStream output=context.getContentResolver().openOutputStream(marker,"w")){
                if(output!=null)output.write(new byte[0]);
            }
            ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);
            context.getContentResolver().update(marker,complete,null,null);
        }catch(Exception failure){
            if(marker!=null)try{context.getContentResolver().delete(marker,null,null);}catch(RuntimeException ignored){}
            android.util.Log.w(Tags.PROBE,"hidden MediaStore marker unavailable",failure);
        }
    }

    private static String extension(String mime){
        return switch(mime){
            case "image/png"->".png";case "image/webp"->".webp";case "image/gif"->".gif";
            case "image/heic","image/heif"->".heic";case "image/avif"->".avif";
            default->".jpg";
        };
    }

    private static void scheduleDelete(Context context,Uri uri){
        CLEANUP.postDelayed(()->{
            try{context.getContentResolver().delete(uri,null,null);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"MediaStore image cleanup deferred",unavailable);}
        },TTL_MS);
    }
}
