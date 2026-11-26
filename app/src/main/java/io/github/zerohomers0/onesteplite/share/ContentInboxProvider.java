package io.github.zerohomers0.onesteplite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Module-owned inbox files and one-use launch capabilities, never arbitrary filesystem paths. */
public final class ContentInboxProvider extends ContentProvider {
    static final String AUTHORITY="io.github.zerohomers0.onesteplite.content";
    static final String MODULE="io.github.zerohomers0.onesteplite";
    static final Uri ROOT=Uri.parse("content://"+AUTHORITY);
    private static final ShareLaunchRequests<Launch> LAUNCHES=new ShareLaunchRequests<>(8,
            request->DropPermissionContract.release(request.grant));
    private static final long RETENTION=24L*60*60*1000;

    @Override public boolean onCreate(){
        if(getContext()!=null)try{DragCacheStore.cleanup(getContext());}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"drag cache startup cleanup deferred",unavailable);}
        return true;
    }

    private void enforceHost() {
        int uid=Binder.getCallingUid();
        if(uid==android.os.Process.myUid())return;
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        if(packages!=null)for(String name:packages)if(Packages.SYSTEM_UI.equals(name))return;
        throw new SecurityException("Not a workspace caller");
    }

    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(method!=null && method.startsWith("portal_"))return PortalRelay.call(getContext(),method,arg,extras);
        if("paste_register".equals(method))return EditorPasteCompat.relay(getContext(),method,extras);
        enforceHost();
        if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different content user");
        if("share_diagnostics".equals(method)){Bundle result=new Bundle();result.putString("events",ShareDiagnostics.snapshot());return result;}
        if("conversation_list".equals(method))return EditorPasteCompat.relay(getContext(),method,extras);
        if(method!=null && method.startsWith("shelf_")) {
            if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different shelf user");
            return ContentShelfStore.call(getContext(),method,extras);
        }
        if(method!=null && method.startsWith("paste_"))return EditorPasteCompat.relay(getContext(),method,extras);
        if("capture_image".equals(method) || "discard_capture".equals(method)) {
            if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different capture user");
            return "capture_image".equals(method)?CaptureInboxStore.store(getContext(),extras==null?null:extras.getByteArray("image"))
                    :CaptureInboxStore.discard(getContext(),arg);
        }
        if("list".equals(method)) {
            JSONArray rows=rows(getContext());
            for(int i=0;i<rows.length();i++) {
                String id=rows.optJSONObject(i).optString("id");
                getContext().grantUriPermission(Packages.SYSTEM_UI,uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            Bundle result=new Bundle();result.putString("rows",rows.toString());return result;
        }
        if("prepare_share".equals(method))return prepare(extras);
        if("prepare_external".equals(method))return prepareExternal(extras);
        throw new IllegalArgumentException("Unknown inbox operation");
    }

    private Bundle prepareExternal(Bundle extras) {
        Intent requested=extras==null?null:extras.getParcelable("intent",Intent.class);
        android.os.IBinder grant=extras==null?null:extras.getBinder("grant");
        if(requested==null || requested.getComponent()==null && requested.getPackage()==null || grant==null)
            throw new SecurityException("Missing delegated drop");
        SharePayload payload=new SharePayload(requested.getClipData());
        if(payload.uris.isEmpty())throw new IllegalArgumentException("No external files");
        android.content.ComponentName target=requested.getComponent()==null
                ?new android.content.ComponentName(requested.getPackage(),""):requested.getComponent();
        String operation=extras.getString("operation","");
        if(!operation.isEmpty() && !ShareActions.known(operation))throw new SecurityException("Unknown operation");
        if(MODULE.equals(target.getPackageName()) && operation.isEmpty())throw new SecurityException("Recursive share");
        Intent clean=payload.intent(target);
        clean.setClipData(payload.clip);ShareRoutes.copyShortcut(requested,clean);
        String token=UUID.randomUUID().toString();
        long now=android.os.SystemClock.uptimeMillis();LAUNCHES.put(token,new Launch(clean,now,grant,extras.getBinder("receipt"),operation),now);
        Bundle result=new Bundle();result.putString("token",token);return result;
    }

    private Bundle prepare(Bundle extras) {
        Intent requested=extras==null?null:extras.getParcelable("intent",Intent.class);
        if(requested==null || requested.getComponent()==null && requested.getPackage()==null || requested.getClipData()==null)
            throw new IllegalArgumentException("Missing explicit share");
        SharePayload payload=new SharePayload(requested.getClipData());
        for(Uri uri:payload.uris) {
            if(!AUTHORITY.equals(uri.getAuthority()) || find(getContext(),uri)==null)
                throw new SecurityException("Not an inbox item");
        }
        android.content.ComponentName target=requested.getComponent()==null
                ?new android.content.ComponentName(requested.getPackage(),""):requested.getComponent();
        String operation=extras.getString("operation","");
        if(!operation.isEmpty() && !ShareActions.known(operation))throw new SecurityException("Unknown operation");
        if(MODULE.equals(target.getPackageName()) && operation.isEmpty())throw new SecurityException("Recursive inbox share");
        Intent share=payload.intent(target);
        share.setClipData(payload.clip);ShareRoutes.copyShortcut(requested,share);
        // Do not re-query the target here. ShareTargetCatalog already filtered the
        // drop, while MIUI can transiently hide a small-window share activity from
        // PackageManager. Starting the explicit intent is the authoritative check.
        String token=UUID.randomUUID().toString();
        long now=android.os.SystemClock.uptimeMillis();LAUNCHES.put(token,new Launch(share,now,null,extras.getBinder("receipt"),operation),now);
        Bundle result=new Bundle();result.putString("token",token);return result;
    }

    static Launch consumeRequest(String token) {
        return LAUNCHES.consume(token,android.os.SystemClock.uptimeMillis());
    }


    static synchronized JSONArray rows(Context context) {
        JSONArray result=new JSONArray();
        try {
            JSONArray saved=new JSONArray(context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE)
                    .getString("rows","[]"));
            boolean cleanup=WorkspaceSettingsStore.read(context).deleteSharedImages;
            for(int i=0;i<saved.length();i++) {
                JSONObject row=saved.getJSONObject(i);String id=row.getString("id");
                if(!ContentInboxPolicy.validId(id))continue;
                if(!cleanup)row.remove("delete_after");
                if(!row.has("document_uri") && (System.currentTimeMillis()-row.getLong("time")>RETENTION
                        || ContentCapturePolicy.sharedImageExpired(row.optBoolean("captured"),row.has("document_uri"),
                                row.optLong("delete_after",0),System.currentTimeMillis()))) {
                    context.revokeUriPermission(uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    File expired=file(context,id);
                    if(expired.exists() && !expired.delete())android.util.Log.w(Tags.PROBE,"inbox expiry deletion failed");
                } else result.put(row);
            }
            context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",result.toString()).apply();
        } catch(Exception invalid) { /* Fail closed on malformed metadata. */ }
        pruneOrphans(context,result);
        return result;
    }

    private static void pruneOrphans(Context context,JSONArray rows) {
        java.util.Set<String> retained=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++)retained.add("content-"+rows.optJSONObject(i).optString("id")+".bin");
        File[] files=context.getCacheDir().listFiles();if(files==null)return;
        long now=System.currentTimeMillis();
        for(File candidate:files) {
            String name=candidate.getName();
            if(!name.startsWith("content-") || !name.endsWith(".bin") || name.length()!=48)continue;
            String id=name.substring(8,name.length()-4);
            if(!ContentInboxPolicy.validId(id) || retained.contains(name) || now-candidate.lastModified()<60*60_000L)continue;
            if(candidate.isFile() && !candidate.delete())android.util.Log.w(Tags.PROBE,"orphan inbox cleanup failed");
        }
    }

    static synchronized void append(Context context,JSONArray added) {
        append(context,added,false);
    }

    static synchronized void appendCapture(Context context,JSONObject capture) {
        append(context,new JSONArray().put(capture),true);
    }

    private static void append(Context context,JSONArray added,boolean capture) {
        JSONArray old=rows(context),all=new JSONArray();
        for(int i=0;i<added.length();i++)all.put(added.optJSONObject(i));
        for(int i=0;i<old.length();i++)all.put(old.optJSONObject(i));
        java.util.ArrayList<String> evicted=evictForCapacity(context,all,added.length(),capture);
        if(!context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",all.toString()).commit())
            throw new IllegalStateException("Inbox metadata save failed");
        discardEvicted(context,evicted);
    }

    /** Evict only unprotected temporary rows; staged shelf items are never removed implicitly. */
    private static java.util.ArrayList<String> evictForCapacity(Context context,JSONArray rows,int addedCount,boolean capture) {
        java.util.ArrayList<String> evicted=new java.util.ArrayList<>();
        int normal=0,reserved=0;long bytes=0;
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(isReservedCapture(row)){reserved++;continue;}
            normal++;if(!row.has("document_uri"))bytes+=file(context,row.optString("id")).length();
        }
        boolean removeAny=!ContentInboxPolicy.withinInbox(normal,bytes);
        boolean[] removed=new boolean[rows.length()];
        if(removeAny) {
            for(int rank=0;rank<=2;rank++) {
                for(int i=rows.length()-1;i>=addedCount;i--) {
                    if(ContentInboxPolicy.withinInbox(normal,bytes))break;
                    JSONObject row=rows.optJSONObject(i);
                    if(row==null || removed[i] || ContentInboxPolicy.evictionPriority(
                            row.optBoolean("staged"),row.optBoolean("shelf_removed"),isReservedCapture(row))!=rank)continue;
                    removed[i]=true;evicted.add(row.optString("id"));
                    if(isReservedCapture(row))reserved--;
                    else {
                        normal--;
                        if(!row.has("document_uri"))bytes-=file(context,row.optString("id")).length();
                    }
                }
                if(ContentInboxPolicy.withinInbox(normal,bytes))break;
            }
        }
        if(!ContentInboxPolicy.withinInbox(normal,bytes) && capture
                && ContentInboxPolicy.canReserveCapture(true,reserved)) {
            JSONObject newest=rows.optJSONObject(0);
            try{newest.put("capture_reserve",true);}
            catch(org.json.JSONException invalid){throw new IllegalStateException("Invalid capture record",invalid);}
            normal--;if(!newest.has("document_uri"))bytes-=file(context,newest.optString("id")).length();reserved++;
        }
        if(!ContentInboxPolicy.withinInbox(normal,bytes)) {
            if(capture)ShareDiagnostics.event(ShareDiagnostics.Stage.IMAGE_STORE,ShareDiagnostics.Code.CAPACITY);
            throw new CapacityExceeded();
        }
        for(int i=removed.length-1;i>=0;i--)if(removed[i])rows.remove(i);
        return evicted;
    }

    private static void discardEvicted(Context context,java.util.List<String> ids) {
        for(String id:ids)if(ContentInboxPolicy.validId(id)) {
            try{context.revokeUriPermission(uri(id),Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"inbox grant cleanup unavailable",unavailable);}
            File file=file(context,id);
            if(file.exists() && !file.delete())android.util.Log.w(Tags.PROBE,"inbox eviction deletion failed");
        }
    }

    static boolean isReservedCapture(JSONObject row) {
        return row.optBoolean("capture_reserve") && row.optBoolean("captured") && !row.has("document_uri");
    }

    static final class CapacityExceeded extends IllegalStateException {
        CapacityExceeded(){super("Inbox capacity exceeded");}
    }

    static Uri uri(String id){return ROOT.buildUpon().appendPath(id).build();}
    static File file(Context context,String id) {
        if(!ContentInboxPolicy.validId(id))throw new IllegalArgumentException("Invalid inbox identity");
        return new File(context.getCacheDir(),"content-"+id+".bin");
    }

    private static JSONObject find(Context context,Uri uri) {
        if(DragCacheStore.isDragUri(uri))return DragCacheStore.find(context,uri);
        String id=uri.getLastPathSegment();JSONArray rows=rows(context);
        for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optString("id").equals(id))return rows.optJSONObject(i);
        return null;
    }

    static String normalize(Context context,Uri uri,String expected)throws java.io.IOException {
        if(!AUTHORITY.equals(uri.getAuthority()))throw new SecurityException("Unowned image metadata");
        JSONObject snapshot=find(context,uri);if(snapshot==null)throw new FileNotFoundException("Expired share record");
        String mime=snapshot.optString("mime","application/octet-stream");ShareImageInfo info;
        if(snapshot.has("document_uri")){
            long identity=Binder.clearCallingIdentity();
            try(java.io.InputStream input=context.getContentResolver().openInputStream(Uri.parse(snapshot.optString("document_uri")))){
                if(input==null)throw new FileNotFoundException("Document image unavailable");
                info=ShareImageInfo.read(input,snapshot.optString("name"),mime,expected);
            }finally{Binder.restoreCallingIdentity(identity);}
        }
        else try(java.io.InputStream input=new java.io.FileInputStream(DragCacheStore.isDragUri(uri)
                ?DragCacheStore.file(context,snapshot.optString("id")):file(context,snapshot.optString("id")))){
            info=ShareImageInfo.read(input,snapshot.optString("name"),mime,expected);
        }
        if(DragCacheStore.isDragUri(uri))return info.mime;
        synchronized(ContentInboxProvider.class){
            JSONArray rows=rows(context);JSONObject current=null;
            for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optString("id").equals(snapshot.optString("id")))current=rows.optJSONObject(i);
            if(current==null)throw new FileNotFoundException("Expired share metadata");
            if(!info.mime.equals(current.optString("mime")) || !info.name.equals(current.optString("name")))try{
                current.put("mime",info.mime).put("name",info.name);
                if(!context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",rows.toString()).commit())
                    throw new java.io.IOException("Metadata save failed");
            }catch(org.json.JSONException invalid){throw new java.io.IOException("Metadata invalid",invalid);}
        }
        return info.mime;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new SecurityException("Read only inbox");
        JSONObject row=find(getContext(),uri);
        if(row==null)throw new FileNotFoundException("Inbox item expired");
        try{
            ParcelFileDescriptor descriptor;
            if(DragCacheStore.isDragUri(uri)){
                File dragFile=DragCacheStore.file(getContext(),uri.getPathSegments().get(1));
                if(!dragFile.isFile())throw new FileNotFoundException("Drag item expired");
                descriptor=ParcelFileDescriptor.open(dragFile,ParcelFileDescriptor.MODE_READ_ONLY);
            }else if(row.has("document_uri")){
                long identity=Binder.clearCallingIdentity();
                try{descriptor=getContext().getContentResolver().openFileDescriptor(Uri.parse(row.optString("document_uri")),"r");}
                finally{Binder.restoreCallingIdentity(identity);}
            }else descriptor=ParcelFileDescriptor.open(file(getContext(),row.optString("id")),ParcelFileDescriptor.MODE_READ_ONLY);
            if(descriptor==null)throw new FileNotFoundException("Document did not provide a descriptor");
            ShareDiagnostics.event(ShareDiagnostics.Stage.READ,ShareDiagnostics.Code.OPENED);return descriptor;
        }catch(FileNotFoundException | RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.READ,ShareDiagnostics.Code.FAILED);throw unavailable;}
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        JSONObject row=find(getContext(),uri);if(row==null)return null;
        String[] columns=projection==null?new String[]{"_display_name","_size"}:projection;
        Long documentSize=null;
        if(row.has("document_uri")){
            long identity=Binder.clearCallingIdentity();
            try(Cursor original=getContext().getContentResolver().query(Uri.parse(row.optString("document_uri")),new String[]{"_size"},null,null,null)){
                if(original!=null && original.moveToFirst() && !original.isNull(0) && original.getLong(0)>=0)documentSize=original.getLong(0);
            }catch(RuntimeException unavailable){/* Unknown document size does not remove its read capability. */}
            finally{Binder.restoreCallingIdentity(identity);}
        }
        android.database.MatrixCursor cursor=new android.database.MatrixCursor(columns);
        Object[] values=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if("_display_name".equals(columns[i]))values[i]=row.optString("name");
            if("_size".equals(columns[i]))values[i]=row.has("document_uri")?documentSize
                    :Long.valueOf(DragCacheStore.isDragUri(uri)?DragCacheStore.file(getContext(),row.optString("id")).length()
                    :file(getContext(),row.optString("id")).length());
        }
        cursor.addRow(values);return cursor;
    }
    @Override public String getType(Uri uri){JSONObject row=find(getContext(),uri);return row==null?null:row.optString("mime");}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
    static final class Launch {
        final Intent intent;final long time;final android.os.IBinder grant;
        final android.os.IBinder receipt;final String operation;
        Launch(Intent intent,long time){this(intent,time,null,null,"");}
        Launch(Intent intent,long time,android.os.IBinder grant){this(intent,time,grant,null,"");}
        Launch(Intent intent,long time,android.os.IBinder grant,android.os.IBinder receipt,String operation){
            this.intent=intent;this.time=time;this.grant=grant;this.receipt=receipt;this.operation=operation;
        }
    }
}
