package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.os.Bundle;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Optional OEM extraction adapter; an unaccepted offer leaves the OEM operation intact. */
final class PortalHooks {
    private PortalHooks(){}
    static void install(ClassLoader loader) {
        try {
            Class<?> service=XposedHelpers.findClass("com.miui.contentextension.services.TextContentExtensionService",loader);
            for(String method:new String[]{"onPickText","onPickImage"}) {
                XposedBridge.hookAllMethods(service,method,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if(call.args.length!=1 || !(call.thisObject instanceof Context))return;
                        try {
                            Context context=(Context)call.thisObject;
                            Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_status",null,null);
                            if(state==null){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_SESSION);return;}
                            if(!payload(call.args[0],"onPickImage".equals(method),state)){
                                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);return;}
                            Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_offer",null,state);
                            if(result!=null && result.getBoolean("accepted")){
                                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.ACCEPTED);call.setResult(null);
                            }else ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.REJECTED);
                        } catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"portal extraction left to system",unavailable);}
                    }
                });
            }
        } catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"portal extraction adapter unavailable",unavailable);}
    }
    private static boolean payload(Object result,boolean image,Bundle out) {
        Object injector=XposedHelpers.callMethod(result,"getInjectorToken");
        Object values=XposedHelpers.callMethod(result,"getParams",image?"image_pick":"text_pick");
        Object mode=values instanceof Map?((Map<?,?>)values).get(image?"image_pick_mode":"pick_mode")
                :values instanceof Bundle?values:null;
        if(mode==null)mode=values;
        return SystemExtractPayload.append(injector,mode,image,out);
    }
}
