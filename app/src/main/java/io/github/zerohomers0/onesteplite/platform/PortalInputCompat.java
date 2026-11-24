package io.github.zerohomers0.onesteplite;

import android.graphics.Point;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Observes physical pointer identity only while the workspace is active. */
final class PortalInputCompat implements AutoCloseable {
    private Object monitor,receiver;
    private static final ThreadLocal<MotionEvent> dragTouch=new ThreadLocal<>();
    private static final ThreadLocal<Object> dragRoot=new ThreadLocal<>();
    private static boolean installed;
    PortalInputCompat(ClassLoader loader,Consumer<MotionEvent> sink) {
        Class<?> compat=XposedHelpers.findClass("com.android.systemui.shared.system.InputMonitorCompat",loader);
        Class<?> listener=XposedHelpers.findClass("com.android.systemui.shared.system.InputChannelCompat$InputEventListener",loader);
        monitor=XposedHelpers.newInstance(compat,"OneStepLitePortal",0);
        try {
            Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(self,method,args)->{
                if("onInputEvent".equals(method.getName()) && args[0] instanceof MotionEvent) {
                    try{ContentDragMotion.motion((MotionEvent)args[0]);EditorDragOutline.motion((MotionEvent)args[0]);sink.accept((MotionEvent)args[0]);}catch(RuntimeException failure){android.util.Log.w(Tags.PROBE,"portal pointer rejected",failure);}
                } else if("hashCode".equals(method.getName()))return System.identityHashCode(self);
                else if("equals".equals(method.getName()))return self==args[0];
                else if("toString".equals(method.getName()))return "PortalPointerObserver";
                return null;
            });
            receiver=XposedHelpers.callMethod(monitor,"getInputReceiver",Looper.getMainLooper(),Choreographer.getInstance(),proxy);
            if(receiver==null)throw new IllegalStateException("Pointer receiver missing");
            install(loader);
        } catch(Throwable failure){close();throw failure;}
    }
    private static synchronized void install(ClassLoader loader) {
        if(installed)return;
        Class<?> root=XposedHelpers.findClass("android.view.ViewRootImpl",loader);
        for(String method:new String[]{"getLastTouchPoint","getLastTouchSource","getLastTouchDeviceId","getLastTouchPointerId"})
            XposedBridge.hookAllMethods(root,method,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    MotionEvent event=dragTouch.get();if(event==null || dragRoot.get()!=call.thisObject)return;
                    if("getLastTouchPoint".equals(method)){((Point)call.args[0]).set(Math.round(event.getRawX()),Math.round(event.getRawY()));call.setResult(null);}
                    else call.setResult("getLastTouchSource".equals(method)?event.getSource():
                            "getLastTouchDeviceId".equals(method)?event.getDeviceId():event.getPointerId(0));
                }
            });
        installed=true;
    }
    static boolean start(View source,android.content.ClipData clip,MotionEvent event) {
        ContentDragShadow shadow=new ContentDragShadow(source,1);shadow.mark(clip);
        try{
            dragRoot.set(XposedHelpers.callMethod(source,"getViewRootImpl"));dragTouch.set(event);
            boolean started=source.startDragAndDrop(clip,shadow,null,View.DRAG_FLAG_GLOBAL|View.DRAG_FLAG_GLOBAL_URI_READ);
            if(!started)ContentDragMotion.close();return started;
        }catch(Throwable unavailable){ContentDragMotion.close();throw new IllegalStateException("Native source input unavailable",unavailable);}
        finally{dragTouch.remove();dragRoot.remove();}
    }
    static void bindSource(android.view.WindowManager.LayoutParams attrs,String source) {
        try{XposedHelpers.setObjectField(attrs,"accessibilityTitle",source);}
        catch(Throwable unavailable){throw new IllegalStateException("Source identity field unavailable",unavailable);}
    }
    @Override public void close() {
        ContentDragMotion.close();
        try{if(receiver!=null)XposedHelpers.callMethod(receiver,"dispose");}
        catch(Throwable failure){android.util.Log.w(Tags.PROBE,"portal receiver disposal failed",failure);}
        finally{
            receiver=null;
            try{if(monitor!=null)XposedHelpers.callMethod(monitor,"dispose");}
            catch(Throwable failure){android.util.Log.w(Tags.PROBE,"portal monitor disposal failed",failure);}
            monitor=null;
        }
    }
}
