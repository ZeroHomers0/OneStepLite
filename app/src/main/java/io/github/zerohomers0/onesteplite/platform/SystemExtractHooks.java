package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import java.util.Arrays;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Receives AES results before Portal routing; never patches application APKs or global settings. */
final class SystemExtractHooks {
    static final String PACKAGE="com.miui.contentcatcher";
    private SystemExtractHooks(){}
    static void install(ClassLoader loader) {
        try {
            Class<?> service=XposedHelpers.findClass("com.miui.contentcatcher.ContentCatcherService",loader);
            boolean installed=!XposedBridge.hookAllMethods(service,"onContentCatched",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if(call.args.length!=1 || call.args[0]==null)return;
                    try {
                        Object raw=XposedHelpers.getObjectField(call.args[0],"mResultMap");
                        if(!(raw instanceof Map) || ((Map<?,?>)raw).isEmpty())return;
                        Context context=(Context)XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread",loader),"currentApplication");
                        if(context==null)return;
                        Object injector=XposedHelpers.callMethod(call.args[0],"getInjectorToken");
                        String name=(String)XposedHelpers.callMethod(injector,"getPkgName");
                        String[] packages=context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
                        if(packages==null || !Arrays.asList(packages).contains(name))return;
                        long identity=Binder.clearCallingIdentity();
                        try{if(offer(context,injector,call.args[0]))call.setResult(null);}
                        finally{Binder.restoreCallingIdentity(identity);}
                    }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"system extraction left to OEM",unavailable);}
                }
            }).isEmpty();
            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,installed?ShareDiagnostics.Code.READY:ShareDiagnostics.Code.UNSUPPORTED);
        }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"system extraction adapter unavailable",unavailable);}
    }
    private static boolean offer(Context context,Object injector,Object content) {
        Object raw=XposedHelpers.getObjectField(content,"mResultMap");if(!(raw instanceof Map))return false;
        Bundle state=null;int inspected=0;boolean matched=false;
        for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet()) {
            if(++inspected>64)break;
            if(!(entry.getKey() instanceof String))continue;
            String key=(String)entry.getKey();if(!key.startsWith("com.miui.contentextension#"))continue;
            boolean image=key.endsWith("#image_pick_mode");
            if(!image && !key.endsWith("#pick_mode"))continue;
            matched=true;
            if(state==null){state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_status",null,null);
                if(state==null){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_SESSION);return false;}}
            try{
                Bundle candidate=new Bundle(state);
                if(!SystemExtractPayload.append(injector,entry.getValue(),image,candidate))continue;
                Bundle reply=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_offer",null,candidate);
                if(reply!=null && reply.getBoolean("accepted")){
                    ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.ACCEPTED);return true;
                }
                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.REJECTED);
            }catch(RuntimeException invalid){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
        }
        if(matched)ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
        return false;
    }
}
