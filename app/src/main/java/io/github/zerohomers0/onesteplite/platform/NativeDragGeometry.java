package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.os.Handler;
import android.view.SurfaceControl;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Rejects native content drops back into their original app. */
final class NativeDragGeometry {
    private static final String KEY="content_drag_geometry";
    private NativeDragGeometry(){}
    static boolean install(ClassLoader loader,BooleanSupplier active){
        try{
            Class<?> type=XposedHelpers.findClass("com.android.server.wm.DragState",loader);
            if(XposedBridge.hookAllMethods(type,"reportDropWindowLock",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    if(call.args.length!=3 && call.args.length!=5)return;
                    if(active.getAsBoolean() && sameSourceContentDrop(call.thisObject,call.args[0])){
                        reject(call);return;
                    }
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session)try{((Session)value).drop(call);}
                    catch(Throwable unavailable){reject(call);}
                }
            }).isEmpty())return false;
            XposedBridge.hookAllMethods(type,"closeLocked",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session){((Session)value).stop();XposedHelpers.removeAdditionalInstanceField(call.thisObject,KEY);}
                }
            });
            return true;
        }catch(Throwable unavailable){return false;}
    }
    private static boolean sameSourceContentDrop(Object state,Object inputToken) {
        if(inputToken==null)return false;
        try{
            Object payload=XposedHelpers.getObjectField(state,"mData");
            if(!(payload instanceof ClipData clip) || !clip.getDescription().hasMimeType("text/*")
                    && !clip.getDescription().hasMimeType("image/*"))return false;
            Object wm=XposedHelpers.getObjectField(state,"mService");
            Object origin=((java.util.Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap"))
                    .get(XposedHelpers.getObjectField(state,"mLocalWin"));
            if(origin==null)return false;
            if((XposedHelpers.getIntField(state,"mFlags")&android.view.View.DRAG_FLAG_GLOBAL)==0
                    && !"com.tencent.mobileqq".equals(XposedHelpers.callMethod(origin,"getOwningPackage")))return false;
            if(inputToken.equals(XposedHelpers.getObjectField(origin,"mInputChannelToken")))return true;
            for(Object target:(java.util.Collection<?>)XposedHelpers.getObjectField(state,"mNotifiedWindows")) {
                if(inputToken.equals(XposedHelpers.getObjectField(target,"mInputChannelToken")))
                    return sameOrigin(origin,target);
            }
        }catch(RuntimeException unavailable){/* Keep the supported shadow-session fallback. */}
        return false;
    }

    private static boolean sameOrigin(Object origin,Object target) {
        if(origin==null || target==null)return false;
        if(origin==target)return true;
        try {
            return XposedHelpers.getIntField(origin,"mOwnerUid")==XposedHelpers.getIntField(target,"mOwnerUid")
                    && java.util.Objects.equals(XposedHelpers.callMethod(origin,"getOwningPackage"),
                            XposedHelpers.callMethod(target,"getOwningPackage"));
        }catch(RuntimeException unavailable){return false;}
    }

    private static void reject(XC_MethodHook.MethodHookParam call){
        call.setResult(false);
        try{XposedHelpers.callMethod(call.thisObject,"endDragLocked",false,false);}
        catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native drag cancellation unavailable",unavailable);}
    }
    static void mark(ClipData clip){
        android.os.PersistableBundle data=clip.getDescription().getExtras();
        data.putString("onesteplite_geometry_id",UUID.randomUUID().toString());clip.getDescription().setExtras(data);
    }
    static void attach(Object controller,Object display,ContentDragShadow card,SurfaceControl surface,BooleanSupplier active){
        try{
            Object wm=XposedHelpers.getObjectField(display,"mWmService");
            synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")){
                Object state=XposedHelpers.getObjectField(controller,"mDragState");
                if(state==null || XposedHelpers.getObjectField(state,"mSurfaceControl")!=surface){card.recycleThumbnail();return;}
                XposedHelpers.setAdditionalInstanceField(state,KEY,new Session(state,display,wm,card,surface,active));
            }
        }catch(Throwable unavailable){card.recycleThumbnail();}
    }
    private static final class Session {
        final Object state,origin;
        final Handler handler;
        final ContentDragShadow card;
        final SurfaceControl surface;
        final BooleanSupplier active;
        boolean stopped;
        final Runnable expiry=this::stop;
        Session(Object state,Object display,Object wm,ContentDragShadow card,SurfaceControl surface,BooleanSupplier active){
            this.state=state;this.card=card;this.surface=surface;this.active=active;
            Object source=((java.util.Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap")).get(XposedHelpers.getObjectField(state,"mLocalWin"));
            if(source!=null && WindowIdentity.ownsPicker(source,display)){
                Object attrs=XposedHelpers.getObjectField(source,"mAttrs");
                if("OneStepLitePortalSource".equals(String.valueOf(XposedHelpers.callMethod(attrs,"getTitle")))){
                    Object focus=XposedHelpers.getObjectField(display,"mCurrentFocus");
                    if(focus!=null && java.util.Objects.equals(String.valueOf(XposedHelpers.getObjectField(attrs,"accessibilityTitle")),
                            XposedHelpers.getObjectField(XposedHelpers.getObjectField(focus,"mAttrs"),"packageName")))source=focus;
                }
            }
            origin=source;
            handler=(Handler)XposedHelpers.getObjectField(wm,"mH");
            handler.postDelayed(expiry,60_000L);
        }
        void drop(XC_MethodHook.MethodHookParam call){
            if(stopped || !active.getAsBoolean() || !surface.isValid()
                    || XposedHelpers.getObjectField(state,"mSurfaceControl")!=surface || origin==null)return;
            Object token=call.args[0];
            if(token==null)return;
            if(token.equals(XposedHelpers.getObjectField(origin,"mInputChannelToken"))){reject(call);return;}
            for(Object target:(java.util.Collection<?>)XposedHelpers.getObjectField(state,"mNotifiedWindows")) {
                if(token.equals(XposedHelpers.getObjectField(target,"mInputChannelToken")) && sameOrigin(origin,target)){
                    reject(call);return;
                }
            }
        }
        void stop(){
            if(stopped)return;
            stopped=true;handler.removeCallbacks(expiry);handler.post(card::recycleThumbnail);
        }
    }
}
