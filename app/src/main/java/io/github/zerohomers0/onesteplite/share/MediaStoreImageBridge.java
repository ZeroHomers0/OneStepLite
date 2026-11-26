package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Publishes module-owned images as short-lived hidden MediaStore items for direct chat drops. */
final class MediaStoreImageBridge {
    private static final long TTL_MS=10L*60L*1000L;
    private static final String FOLDER=Environment.DIRECTORY_PICTURES+"/.OneStep";
    private static final Handler CLEANUP=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=BackgroundExecutor.single("OneStepLite-media-store");
    private static boolean noMediaReady;
    private MediaStoreImageBridge(){}

    static boolean isInternalPath(String path){
        return path!=null && (path.equals(FOLDER) || path.startsWith(FOLDER+"/"));
    }

    static Uri publishUri(Context context,Uri source){
        if(context==null || source==null || !ContentInboxProvider.AUTHORITY.equals(source.getAuthority()))return null;
        String mime=null;
        try{mime=context.getContentResolver().getType(source);}catch(RuntimeException unavailable){}
        if(mime==null || !mime.startsWith("image/"))return null;
        return publish(context,mime,output->{
            try(InputStream input=context.getContentResolver().openInputStream(source)){
                if(input==null)throw new IOException("MediaStore source unavailable");
                copy(input,output);
            }
        });
    }

    static Uri publishBytes(Context context,byte[] bytes,String mime){
        if(context==null || bytes==null || bytes.length==0 || mime==null || !mime.startsWith("image/"))return null;
        return publish(context,mime,output->output.write(bytes));
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
            if(result==null)result=new ClipData(new ClipDescription(source.getDescription()),item);else result.addItem(item);
        }
        return changed?result:source;
    }

    static void discardAsync(Context context,Uri uri){
        if(context==null || uri==null)return;
        Context app=context.getApplicationContext();
        IO.execute(()->discard(app==null?context:app,uri));
    }

    static void discardClipAsync(Context context,ClipData clip){
        if(context==null || clip==null)return;
        for(int i=0;i<clip.getItemCount();i++){
            Uri uri=clip.getItemAt(i).getUri();
            if(uri!=null)discardAsync(context,uri);
        }
    }

    static void cleanupAsync(Context context){
        if(context==null)return;
        Context app=context.getApplicationContext();
        IO.execute(()->cleanupExpired(app==null?context:app));
    }

    private static Uri publish(Context context,String mime,Writer writer){
        ensureNoMedia(context);
        String extension=extension(mime);
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,"OneStep-"+UUID.randomUUID()+extension);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri target=null;
        try{
            target=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(target==null)throw new IOException("MediaStore insert rejected");
            try(OutputStream output=context.getContentResolver().openOutputStream(target,"w")) {
                if(output==null)throw new IOException("MediaStore stream unavailable");
                writer.write(output);output.flush();
            }
            ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(context.getContentResolver().update(target,complete,null,null)<=0)
                throw new IOException("MediaStore completion rejected");
            scheduleDelete(context.getApplicationContext()==null?context:context.getApplicationContext(),target);
            return target;
        }catch(Exception failure){
            if(target!=null)try{context.getContentResolver().delete(target,null,null);}catch(RuntimeException ignored){}
            android.util.Log.w(Tags.PROBE,"MediaStore image publication failed",failure);
            return null;
        }
    }

    private static void copy(InputStream input,OutputStream output)throws IOException{
        byte[] buffer=new byte[64*1024];int read;
        while((read=input.read(buffer))>=0)if(read>0)output.write(buffer,0,read);
    }

    private static synchronized void ensureNoMedia(Context context){
        if(noMediaReady)return;
        Uri collection=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor cursor=context.getContentResolver().query(collection,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                new String[]{".nomedia",FOLDER},null)){
            if(cursor!=null && cursor.moveToFirst()){noMediaReady=true;return;}
        }catch(RuntimeException unavailable){/* Try to create the marker below. */}
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,".nomedia");
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri marker=null;
        try{
            marker=context.getContentResolver().insert(collection,values);
            if(marker==null)throw new IOException("Hidden marker insert rejected");
            try(OutputStream output=context.getContentResolver().openOutputStream(marker,"w")){
                if(output==null)throw new IOException("Hidden marker stream unavailable");
                output.write(new byte[0]);
            }
            ContentValues complete=new ContentValues();complete.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(context.getContentResolver().update(marker,complete,null,null)<=0)
                throw new IOException("Hidden marker completion rejected");
            noMediaReady=true;
        }catch(Exception failure){
            if(marker!=null)try{context.getContentResolver().delete(marker,null,null);}catch(RuntimeException ignored){}
            android.util.Log.w(Tags.PROBE,"hidden MediaStore marker unavailable",failure);
        }
    }

    private static void cleanupExpired(Context context){
        long before=System.currentTimeMillis()/1000L-TTL_MS/1000L;
        try(Cursor cursor=context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.DATE_ADDED+"<?",
                new String[]{FOLDER,Long.toString(before)},null)){
            while(cursor!=null && cursor.moveToNext())
                context.getContentResolver().delete(android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)),null,null);
        }catch(RuntimeException unavailable){
            android.util.Log.w(Tags.PROBE,"MediaStore image startup cleanup deferred",unavailable);
        }
    }

    private static void discard(Context context,Uri uri){
        if(!MediaStore.Images.Media.EXTERNAL_CONTENT_URI.getAuthority().equals(uri.getAuthority()))return;
        try(Cursor cursor=context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)){
            if(cursor==null || !cursor.moveToFirst() || !FOLDER.equals(cursor.getString(0)))return;
            context.getContentResolver().delete(uri,null,null);
        }catch(RuntimeException unavailable){
            android.util.Log.w(Tags.PROBE,"MediaStore image cleanup deferred",unavailable);
        }
    }

    private static String extension(String mime){
        return switch(mime){
            case "image/jpeg","image/jpg"->".jpg";case "image/png"->".png";case "image/webp"->".webp";
            case "image/gif"->".gif";case "image/heic","image/heif"->".heic";case "image/avif"->".avif";
            case "image/bmp","image/x-ms-bmp"->".bmp";case "image/tiff"->".tiff";case "image/svg+xml"->".svg";
            case "image/x-icon","image/vnd.microsoft.icon"->".ico";default->".img";
        };
    }

    private static void scheduleDelete(Context context,Uri uri){
        CLEANUP.postDelayed(()->IO.execute(()->discard(context,uri)),TTL_MS);
    }

    @FunctionalInterface private interface Writer{void write(OutputStream output)throws IOException;}
}
