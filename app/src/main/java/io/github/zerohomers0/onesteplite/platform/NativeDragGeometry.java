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
            XC_MethodHook dropGuard=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call){
                    if(call.args.length!=3 && call.args.length!=5)return;
                    if(active.getAsBoolean() && sameSourceContentDrop(call.thisObject,call.args[0])){
                        reject(call);return;
                    }
                    Object value=XposedHelpers.getAdditionalInstanceField(call.thisObject,KEY);
                    if(value instanceof Session)try{((Session)value).drop(call);}
                    catch(Throwable unavailable){reject(call);}
                }
            };
            boolean publicRoute=!XposedBridge.hookAllMethods(type,"reportDropWindowLock",dropGuard).isEmpty();
            boolean directRoute=!XposedBridge.hookAllMethods(type,"reportDropWindowLockInner",dropGuard).isEmpty();
            if(!publicRoute && !directRoute)return false;
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
            Object wm=XposedHelpers.getObjectField(state,"mService");
            Object origin=((java.util.Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap"))
                    .get(XposedHelpers.getObjectField(state,"mLocalWin"));
            if(origin==null)return false;
            Object source=origin;
            Object originalApp=PortalDragInput.originalWindow(origin);
            if(originalApp!=null)origin=originalApp;
            if(originalApp==null && !nativeShareContent(state,source))return false;
            if(inputToken.equals(XposedHelpers.getObjectField(source,"mInputChannelToken"))
                    || inputToken.equals(XposedHelpers.getObjectField(origin,"mInputChannelToken")))return true;
            return sameOrigin(origin,dropWindow(state,wm,inputToken));
        }catch(RuntimeException unavailable){/* Keep the supported shadow-session fallback. */}
        return false;
    }

    private static boolean nativeShareContent(Object state,Object source) {
        // Older QQ drags may only carry app-local state.
        if("com.tencent.mobileqq".equals(XposedHelpers.callMethod(source,"getOwningPackage")))return true;
        if((XposedHelpers.getIntField(state,"mFlags")&android.view.View.DRAG_FLAG_GLOBAL)==0)return false;
        Object payload=XposedHelpers.getObjectField(state,"mData");
        return payload instanceof ClipData clip && (clip.getDescription().hasMimeType("text/*")
                || clip.getDescription().hasMimeType("image/*") || NativeContentDragCard.files(clip));
    }

    private static Object dropWindow(Object state,Object wm,Object token) {
        try{
            Object window=XposedHelpers.callMethod(XposedHelpers.getObjectField(wm,"mInputToWindowMap"),"get",token);
            if(window!=null)return window;
        }catch(RuntimeException unavailable){/* Older releases only expose their notified windows. */}
        Object notified=XposedHelpers.getObjectField(state,"mNotifiedWindows");
        if(notified instanceof java.util.Collection<?> windows)for(Object target:windows)
            if(token.equals(XposedHelpers.getObjectField(target,"mInputChannelToken")))return target;
        return null;
    }

    private static boolean sameOrigin(Object origin,Object target) {
        if(origin==null || target==null)return false;
        if(origin==target)return true;
        try {
            return XposedHelpers.getIntField(origin,"mOwnerUid")==XposedHelpers.getIntField(target,"mOwnerUid")
                    && java.util.Objects.equals(XposedHelpers.callMethod(origin,"getOwningPackage"),
                            XposedHelpers.callMethod(target,"getOwningPackage"))
                    && !WindowIdentity.ownsShareReceiver(target);
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
            Object original=source==null?null:PortalDragInput.originalWindow(source);
            origin=original!=null?original:source;
            handler=(Handler)XposedHelpers.getObjectField(wm,"mH");
            handler.postDelayed(expiry,60_000L);
        }
        void drop(XC_MethodHook.MethodHookParam call){
            if(stopped || !active.getAsBoolean() || !surface.isValid()
                    || XposedHelpers.getObjectField(state,"mSurfaceControl")!=surface || origin==null)return;
            Object token=call.args[0];
            if(token==null)return;
            if(token.equals(XposedHelpers.getObjectField(origin,"mInputChannelToken"))){reject(call);return;}
            Object wm=XposedHelpers.getObjectField(state,"mService");
            if(sameOrigin(origin,dropWindow(state,wm,token)))reject(call);
        }
        void stop(){
            if(stopped)return;
            stopped=true;handler.removeCallbacks(expiry);handler.post(card::recycleThumbnail);
        }
    }
}
