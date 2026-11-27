package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Provider entry authenticates each side independently; no exported broadcast carries content. */
final class PortalRelay {
    private static IBinder host;
    private PortalRelay(){}
    static Bundle call(Context context,String method,String caller,Bundle data) {
        int uid=Binder.getCallingUid();
        if(uid/100000!=android.os.Process.myUid()/100000)throw new SecurityException("Different source user");
        String[] packages=context.getPackageManager().getPackagesForUid(uid);
        java.util.List<String> names=packages==null?java.util.Collections.emptyList():java.util.Arrays.asList(packages);
        if("portal_discard_media".equals(method)) {
            if(!names.contains(Packages.SYSTEM_UI))throw new SecurityException("Not SystemUI");
            String encoded=data==null?null:data.getString("uri");
            android.net.Uri uri=encoded==null?null:android.net.Uri.parse(encoded);
            if(uri==null || !android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI.getAuthority()
                    .equals(uri.getAuthority()))throw new IllegalArgumentException("Invalid temporary media URI");
            MediaStoreImageBridge.discardAsync(context,uri);
            return new Bundle();
        }
        if("portal_register".equals(method)) {
            if(!names.contains(Packages.SYSTEM_UI))throw new SecurityException("Not SystemUI");
            synchronized(PortalRelay.class){
                if(data!=null && data.containsKey("unregister")) {
                    if(host==data.getBinder("unregister"))host=null;
                } else host=data==null?null:data.getBinder("host");
            }
            return new Bundle();
        }
        boolean view="portal_view_status".equals(method) || "portal_view_offer".equals(method);
        if(view?(!ViewImageSource.supportsUid(uid) || !names.contains(caller) || context.getPackageName().equals(caller))
                :!names.contains(PortalWire.PACKAGE) && !names.contains(SystemExtractHooks.PACKAGE))
            throw new SecurityException("Not an extraction service");
        IBinder target;synchronized(PortalRelay.class){target=host;}
        if(target==null || !target.isBinderAlive()){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_SESSION);return null;}
        Bundle viewSource=null;
        if(view){
            viewSource=new Bundle();viewSource.putString("source",caller);
            viewSource.putLong("down",data==null?0:data.getLong("down"));
        }
        if("portal_status".equals(method) || "portal_view_status".equals(method))
            return PortalWire.call(target,PortalWire.STATUS,viewSource);
        if(!"portal_offer".equals(method) && !"portal_view_offer".equals(method) || data==null)
            throw new IllegalArgumentException("Source operation");
        byte[] image=data.getByteArray("image");String id=null;
        String words=data.getString("text");
        if(view && (image==null?(words==null || words.isBlank() || words.length()>ContentSharePolicy.MAX_TEXT)
                :data.containsKey("text")))throw new IllegalArgumentException("Invalid view content");
        if(view){
            Bundle state=PortalWire.call(target,PortalWire.STATUS,viewSource);
            if(state==null || state.getLong("down")!=data.getLong("down")
                    || state.getInt("generation")!=data.getInt("generation"))return null;
        }
        boolean region=!view && image==null && !data.containsKey("text") && data.getBoolean("region_only");
        Bundle request=new Bundle();request.putLong("down",data.getLong("down"));
        request.putInt("generation",data.getInt("generation"));request.putString("text",data.getString("text"));
        String source=view?caller:data.getString("source");
        if(source==null || source.isEmpty() || source.length()>255)throw new IllegalArgumentException("Source identity missing");
        request.putString("source",source);
        if(view)request.putBoolean("view_source",true);
        request.putBoolean("region_only",region);
        if(!view){
            request.putIntArray("source_rect",data.getIntArray("source_rect"));
            request.putFloatArray("source_touch",data.getFloatArray("source_touch"));
        }
        boolean system=names.contains(SystemExtractHooks.PACKAGE);
        request.putBoolean("await_image",system && image==null && data.getBoolean("await_image"));
        int priority=view?(image!=null?PortalGesturePolicy.VIEW_IMAGE
                :data.getBoolean("selected")?PortalGesturePolicy.VIEW_SELECTION:PortalGesturePolicy.VIEW_TEXT)
                :region?PortalGesturePolicy.REGION:image==null?(system?PortalGesturePolicy.SYSTEM_TEXT:PortalGesturePolicy.PORTAL_TEXT)
                :system?PortalGesturePolicy.SYSTEM_IMAGE:PortalGesturePolicy.PORTAL_IMAGE;
        request.putInt("source_priority",priority);
        try {
            android.net.Uri published=null;
            if(image!=null) {
                CaptureInboxStore.validateEncoded(image);
                published=MediaStoreImageBridge.publishBytes(context,image,"image/jpeg");
                android.util.Log.i("OneStepLiteShare",BuildIdentity.RUNTIME_MARKER);
                if(published!=null){
                    request.putString("cleanup_uri",published.toString());
                    logPublishedGrant(context,published);
                }
                else {
                    ShareDiagnostics.event(ShareDiagnostics.Stage.GRANT,ShareDiagnostics.Code.FAILED);
                    Bundle stored=CaptureInboxStore.store(context,image);id=stored.getString("id");
                    request.putString("uri",stored.getString("uri"));
                    request.putString("capture_id",id);
                }
            }
            if(published!=null)request.putString("uri",published.toString());
            Bundle result=PortalWire.call(target,PortalWire.OFFER,request);
            if(result==null || !result.getBoolean("accepted")) {
                if(id!=null)CaptureInboxStore.discard(context,id);
                if(published!=null)MediaStoreImageBridge.discardAsync(context,published);
            }
            return result;
        } catch(RuntimeException failure){
            if(id!=null)CaptureInboxStore.discard(context,id);
            if(request.getString("cleanup_uri")!=null)MediaStoreImageBridge.discardAsync(context,
                    android.net.Uri.parse(request.getString("cleanup_uri")));
            throw failure;
        }
    }

    private static void logPublishedGrant(Context context,android.net.Uri uri) {
        ShareDiagnostics.Code result;
        try {
            int uid=context.getPackageManager().getApplicationInfo(Packages.SYSTEM_UI,0).uid;
            boolean granted=context.checkUriPermission(uri,-1,uid,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ==android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean file=uri.getPathSegments().contains("file");
            result=file?(granted?ShareDiagnostics.Code.SOURCE_FILE_GRANTED:ShareDiagnostics.Code.SOURCE_FILE_NOT_GRANTED)
                    :(granted?ShareDiagnostics.Code.SOURCE_IMAGE_GRANTED:ShareDiagnostics.Code.SOURCE_IMAGE_NOT_GRANTED);
        }catch(Exception unavailable){
            result=ShareDiagnostics.Code.SOURCE_CHECK_UNAVAILABLE;
        }
        ShareDiagnostics.event(ShareDiagnostics.Stage.GRANT,result);
    }
}
