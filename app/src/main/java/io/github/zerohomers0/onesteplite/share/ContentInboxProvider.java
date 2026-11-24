package io.github.zerohomers0.onesteplite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
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

    @Override public boolean onCreate(){return true;}

    private void enforceHost() {
        int uid=Binder.getCallingUid();
        if(uid==android.os.Process.myUid())return;
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        if(packages!=null)for(String name:packages)if(Packages.SYSTEM_UI.equals(name))return;
        throw new SecurityException("Not a workspace caller");
    }

    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(method!=null && method.startsWith("portal_"))return PortalRelay.call(getContext(),method,extras);
        if("paste_register".equals(method))return EditorPasteCompat.relay(getContext(),method,extras);
        enforceHost();
        if(Binder.getCallingUid()/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different content user");
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
        java.util.List<ResolveInfo> receivers=getContext().getPackageManager().queryIntentActivities(share,0);
        if(receivers.isEmpty() && operation.isEmpty())
            throw new IllegalArgumentException("Unavailable receiver");
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
        JSONArray old=rows(context),all=new JSONArray();
        for(int i=0;i<added.length();i++)all.put(added.optJSONObject(i));
        for(int i=0;i<old.length();i++)all.put(old.optJSONObject(i));
        if(all.length()>128)throw new IllegalStateException("Inbox full; wait for retained items to expire");
        long bytes=0;for(int i=0;i<all.length();i++) {
            JSONObject row=all.optJSONObject(i);
            if(!row.has("document_uri"))bytes+=file(context,row.optString("id")).length();
        }
        if(bytes>ContentInboxPolicy.MAX_TOTAL)throw new IllegalStateException("Inbox byte budget exceeded");
        context.getSharedPreferences("content_inbox",Context.MODE_PRIVATE).edit().putString("rows",all.toString()).apply();
    }

    static Uri uri(String id){return ROOT.buildUpon().appendPath(id).build();}
    static File file(Context context,String id) {
        if(!ContentInboxPolicy.validId(id))throw new IllegalArgumentException("Invalid inbox identity");
        return new File(context.getCacheDir(),"content-"+id+".bin");
    }

    private static JSONObject find(Context context,Uri uri) {
        String id=uri.getLastPathSegment();JSONArray rows=rows(context);
        for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optString("id").equals(id))return rows.optJSONObject(i);
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new SecurityException("Read only inbox");
        JSONObject row=find(getContext(),uri);
        if(row==null)throw new FileNotFoundException("Inbox item expired");
        if(row.has("document_uri"))return getContext().getContentResolver().openFileDescriptor(
                Uri.parse(row.optString("document_uri")),"r");
        return ParcelFileDescriptor.open(file(getContext(),row.optString("id")),ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        JSONObject row=find(getContext(),uri);if(row==null)return null;
        String[] columns=projection==null?new String[]{"_display_name","_size"}:projection;
        android.database.MatrixCursor cursor=new android.database.MatrixCursor(columns);
        Object[] values=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if("_display_name".equals(columns[i]))values[i]=row.optString("name");
            if("_size".equals(columns[i]))values[i]=row.has("document_uri")?null:file(getContext(),row.optString("id")).length();
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
