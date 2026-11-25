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
        if(view?(!ViewImageSource.supports(caller) || !names.contains(caller))
                :!names.contains(PortalWire.PACKAGE) && !names.contains(SystemExtractHooks.PACKAGE))
            throw new SecurityException("Not an extraction service");
        IBinder target;synchronized(PortalRelay.class){target=host;}
        if(target==null || !target.isBinderAlive()){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_SESSION);return null;}
        if("portal_status".equals(method) || "portal_view_status".equals(method))
            return PortalWire.call(target,PortalWire.STATUS,null);
        if(!"portal_offer".equals(method) && !"portal_view_offer".equals(method) || data==null)
            throw new IllegalArgumentException("Source operation");
        byte[] image=data.getByteArray("image");String id=null;
        if(view && (image==null || data.containsKey("text")))throw new IllegalArgumentException("Missing view image");
        boolean region=!view && image==null && !data.containsKey("text") && data.getBoolean("region_only");
        Bundle request=new Bundle();request.putLong("down",data.getLong("down"));
        request.putInt("generation",data.getInt("generation"));request.putString("text",data.getString("text"));
        String source=view?caller:data.getString("source");
        if(source==null || source.isEmpty() || source.length()>255)throw new IllegalArgumentException("Source identity missing");
        request.putString("source",source);
        if(view)request.putBoolean("view_image",true);
        request.putBoolean("region_only",region);
        if(!view){
            request.putIntArray("source_rect",data.getIntArray("source_rect"));
            request.putFloatArray("source_touch",data.getFloatArray("source_touch"));
        }
        boolean system=names.contains(SystemExtractHooks.PACKAGE);
        int priority=region?PortalGesturePolicy.REGION:image==null?(system?PortalGesturePolicy.SYSTEM_TEXT:PortalGesturePolicy.PORTAL_TEXT)
                :view?PortalGesturePolicy.VIEW_IMAGE
                :system?PortalGesturePolicy.SYSTEM_IMAGE:PortalGesturePolicy.PORTAL_IMAGE;
        request.putInt("source_priority",priority);
        try {
            if(image!=null) {
                Bundle stored=CaptureInboxStore.store(context,image);id=stored.getString("id");
                request.putString("uri",stored.getString("uri"));
                if(view)request.putString("view_capture_id",id);
            }
            Bundle result=PortalWire.call(target,PortalWire.OFFER,request);
            if(id!=null && (result==null || !result.getBoolean("accepted")))CaptureInboxStore.discard(context,id);
            return result;
        } catch(RuntimeException failure){if(id!=null)CaptureInboxStore.discard(context,id);throw failure;}
    }
}
