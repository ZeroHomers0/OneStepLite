package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Optional OEM extraction adapter; an unaccepted offer leaves the OEM operation intact. */
final class PortalHooks {
    private static final ThreadLocal<ImagePick> IMAGE_PICK=new ThreadLocal<>();
    private static final ThreadLocal<Object> SUPPRESSED_IMAGE_TASK=new ThreadLocal<>();
    private static final String IMAGE_PICK_KEY="onesteplite_image_pick";

    private static final class ImagePick {
        final ImagePick parent;
        Bundle state;
        Object injector;
        Object mode;
        Bitmap bitmap;
        Context owner;
        boolean offered;
        ImagePick(ImagePick parent){this.parent=parent;}
    }

    private PortalHooks(){}
    static void install(ClassLoader loader) {
        try {
            Class<?> service=XposedHelpers.findClass("com.miui.contentextension.services.TextContentExtensionService",loader);
            installImageTaskHandoff(loader,service);
            boolean observesBitmap=!XposedBridge.hookAllMethods(service,"setBitmap",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    ImagePick image=IMAGE_PICK.get();
                    if(image!=null && image.state!=null && !call.hasThrowable() && call.args.length==1)
                        image.bitmap=call.args[0] instanceof Bitmap?(Bitmap)call.args[0]:null;
                }
            }).isEmpty();
            for(String method:new String[]{"onPickText","onPickImage"}) {
                boolean imageMethod="onPickImage".equals(method);
                boolean installed=!XposedBridge.hookAllMethods(service,method,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        ImagePick image=null;
                        if(imageMethod && observesBitmap){
                            image=new ImagePick(IMAGE_PICK.get());
                            call.setObjectExtra(IMAGE_PICK_KEY,image);IMAGE_PICK.set(image);
                        }
                        if(call.args.length!=1 || !(call.thisObject instanceof Context))return;
                        try {
                            Context context=(Context)call.thisObject;
                            Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_status",null,null);
                            if(state==null){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_SESSION);return;}
                            Object injector=XposedHelpers.callMethod(call.args[0],"getInjectorToken");
                            String readySource=state.getString("ready_image_source");
                            if(imageMethod && state.getBoolean("prefer_image") && readySource!=null && injector!=null
                                    && readySource.equals(XposedHelpers.callMethod(injector,"getPkgName"))){
                                call.setResult(null);
                                return;
                            }
                            Object values=XposedHelpers.callMethod(call.args[0],"getParams",imageMethod?"image_pick":"text_pick");
                            if(SystemExtractPayload.append(injector,values,imageMethod,state)){
                                if(!imageMethod && "图片".equals(state.getString("text").strip())){
                                    ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
                                    return;
                                }
                                if(offer(context,state))call.setResult(null);
                            }else if(image!=null && injector!=null){
                                image.injector=injector;image.mode=values;image.state=state;image.owner=context;
                            }else ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
                        } catch(Throwable unavailable){
                            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
                            android.util.Log.w(Tags.PROBE,"portal extraction left to system",unavailable);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        ImagePick image=(ImagePick)call.getObjectExtra(IMAGE_PICK_KEY);
                        if(image==null)return;
                        if(image.parent==null)IMAGE_PICK.remove();else IMAGE_PICK.set(image.parent);
                        if(image.state==null || image.offered || call.hasThrowable())return;
                        try {
                            if(SystemExtractPayload.append(image.injector,image.bitmap,true,image.state)){
                                SystemExtractPayload.copyGeometry(image.mode,true,image.state);
                                offer((Context)call.thisObject,image.state);
                            }else if(SystemExtractPayload.appendRegion(image.injector,image.mode,image.state))
                                offer((Context)call.thisObject,image.state);
                            else ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
                        }catch(RuntimeException unavailable){
                            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
                            android.util.Log.w(Tags.PROBE,"portal resolved image unavailable",unavailable);
                        }
                    }
                }).isEmpty();
                if("onPickImage".equals(method))ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,
                        installed?ShareDiagnostics.Code.READY:ShareDiagnostics.Code.UNSUPPORTED);
            }
        } catch(Throwable unavailable){
            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
            android.util.Log.w(Tags.PROBE,"portal extraction adapter unavailable",unavailable);
        }
    }

    private static void installImageTaskHandoff(ClassLoader loader,Class<?> service) {
        try {
            if(XposedBridge.hookAllMethods(service,"startPickImageTask",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    Object owner=SUPPRESSED_IMAGE_TASK.get();
                    if(owner!=null && owner==call.thisObject)call.setResult(null);
                }
            }).isEmpty())return;
            Class<?> worker=XposedHelpers.findClass("com.miui.contentextension.setting.TaplusWorker",loader);
            XposedBridge.hookAllMethods(worker,"postToMain",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    ImagePick image=IMAGE_PICK.get();
                    if(image==null || image.state==null || image.offered || image.bitmap==null
                            || !image.state.getBoolean("prefer_image") || call.args.length!=1
                            || !(call.args[0] instanceof Runnable task))return;
                    try {
                        if(!SystemExtractPayload.append(image.injector,image.bitmap,true,image.state))return;
                        SystemExtractPayload.copyGeometry(image.mode,true,image.state);
                        image.offered=true;
                        if(!offer(image.owner,image.state))return;
                        Context owner=image.owner;
                        call.args[0]=(Runnable)()->{
                            Object previous=SUPPRESSED_IMAGE_TASK.get();
                            SUPPRESSED_IMAGE_TASK.set(owner);
                            try {task.run();}
                            finally {
                                if(previous==null)SUPPRESSED_IMAGE_TASK.remove();
                                else SUPPRESSED_IMAGE_TASK.set(previous);
                            }
                        };
                    }catch(Throwable unavailable){
                        ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
                    }
                }
            });
        }catch(Throwable unavailable){
            // ROMs without this worker keep their original post-pick fallback.
        }
    }

    private static boolean offer(Context context,Bundle state) {
        Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_offer",null,state);
        boolean accepted=result!=null && result.getBoolean("accepted");
        ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,
                accepted?ShareDiagnostics.Code.ACCEPTED:ShareDiagnostics.Code.REJECTED);
        return accepted;
    }
}
