package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/** Provider entry authenticates each side independently; no exported broadcast carries content. */
final class PortalRelay {
    private static IBinder host;
    private PortalRelay(){}
    static Bundle call(Context context,String method,Bundle data) {
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
        if(!names.contains(PortalWire.PACKAGE) && !names.contains(SystemExtractHooks.PACKAGE))throw new SecurityException("Not an extraction service");
        IBinder target;synchronized(PortalRelay.class){target=host;}
        if(target==null || !target.isBinderAlive())return null;
        if("portal_status".equals(method))return PortalWire.call(target,PortalWire.STATUS,null);
        if(!"portal_offer".equals(method) || data==null)throw new IllegalArgumentException("Source operation");
        byte[] image=data.getByteArray("image");String id=null;
        Bundle request=new Bundle();request.putLong("down",data.getLong("down"));
        request.putInt("generation",data.getInt("generation"));request.putString("text",data.getString("text"));
        String source=data.getString("source");
        if(source==null || source.isEmpty() || source.length()>255)throw new IllegalArgumentException("Source identity missing");
        request.putString("source",source);
        try {
            if(image!=null) {
                Bundle stored=CaptureInboxStore.store(context,image);id=stored.getString("id");
                request.putString("uri",stored.getString("uri"));
            }
            Bundle result=PortalWire.call(target,PortalWire.OFFER,request);
            if(id!=null && (result==null || !result.getBoolean("accepted")))CaptureInboxStore.discard(context,id);
            return result;
        } catch(RuntimeException failure){if(id!=null)CaptureInboxStore.discard(context,id);throw failure;}
    }
}
