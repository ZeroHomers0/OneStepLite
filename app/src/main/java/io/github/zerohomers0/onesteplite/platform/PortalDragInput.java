package io.github.zerohomers0.onesteplite;

import android.os.IBinder;
import android.view.MotionEvent;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The native drag takes the original application's touch stream, never fabricated input. */
final class PortalDragInput {
    static final String TITLE="OneStepLitePortalSource";
    private static final String ORIGINAL_WINDOW="portal_drag_original_window";
    private static Object observedDisplay,listener;

    static Object originalWindow(Object portalWindow) {
        return XposedHelpers.getAdditionalInstanceField(portalWindow,ORIGINAL_WINDOW);
    }

    static String sourcePackage(CharSequence identity) {
        String value=identity.toString();
        int delimiter=value.indexOf('\n');
        return delimiter<0?value:value.substring(0,delimiter);
    }
    private static volatile Touch touch;
    private record Touch(Object display,IBinder input,long down,int device,int pointer) {}
    private PortalDragInput(){}
    static void install(ClassLoader loader,Supplier<Object> display,BooleanSupplier active) {
        try {
            Class<?> input=XposedHelpers.findClass("com.android.server.input.InputManagerService",loader);
            if(XposedBridge.hookAllMethods(input,"startDragAndDrop",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    Object target=display.get();if(target==null || !active.getAsBoolean() || call.args.length!=2)return;
                    try {
                        Object wm=XposedHelpers.getObjectField(target,"mWmService");
                        Object lock=XposedHelpers.getObjectField(wm,"mGlobalLock");
                        synchronized(lock){
                            IBinder original=source(wm,target,call.args[0]);
                            if(original!=null)call.args[0]=original;
                        }
                    } catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"portal native input transfer rejected",unavailable);}
                }
            }).isEmpty())throw new IllegalStateException("Native drag input method missing");
        } catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"portal native input unavailable",unavailable);}
    }

    static void observeDisplay(Object display,ClassLoader loader,BooleanSupplier active) {
        if(observedDisplay==display)return;
        if(listener!=null){
            try{XposedHelpers.callMethod(observedDisplay,"unregisterPointerEventListener",listener);}
            catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"portal touch observer retirement failed",unavailable);}
        }
        observedDisplay=display;listener=null;touch=null;
        try {
            Class<?> type=Class.forName("android.view.WindowManagerPolicyConstants$PointerEventListener",false,loader);
            Object observer=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                switch(method.getName()){
                    case "onPointerEvent" -> {
                        if(active.getAsBoolean() && args[0] instanceof MotionEvent event)observeTouch(display,event);
                        else touch=null;
                    }
                    case "hashCode" -> {return System.identityHashCode(proxy);}
                    case "equals" -> {return proxy==args[0];}
                    case "toString" -> {return "OneStepLitePortalTouch";}
                }
                return null;
            });
            XposedHelpers.callMethod(display,"registerPointerEventListener",observer);listener=observer;
        }catch(Exception unavailable){android.util.Log.w(Tags.PROBE,"portal touch observer unavailable",unavailable);}
    }

    static void observeTouch(Object display,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN){
            touch=null;return;
        }
        if(action!=MotionEvent.ACTION_DOWN)return;
        touch=null;
        if(event.getPointerCount()!=1 || !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN))return;
        try {
            Object wm=XposedHelpers.getObjectField(display,"mWmService");
            synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")){
                Object winner=XposedHelpers.callMethod(display,"getTouchableWinAtPointLocked",event.getRawX(),event.getRawY());
                Object task=winner==null?null:XposedHelpers.callMethod(winner,"getTask");
                if(task!=null && Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode"))
                        && XposedHelpers.getObjectField(winner,"mInputChannelToken") instanceof IBinder input)
                    touch=new Touch(display,input,event.getDownTime(),event.getDeviceId(),event.getPointerId(0));
            }
        }catch(RuntimeException unavailable){ /* The established focused-window route remains available. */ }
    }

    static String identity(String pkg,MotionEvent event) {
        return pkg+"\n"+event.getDownTime()+":"+event.getDeviceId()+":"+event.getPointerId(0);
    }
    private static IBinder source(Object wm,Object display,Object token) {
        Object source=null;
        for(Object window:((Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap")).values()) {
            if(token.equals(XposedHelpers.getObjectField(window,"mInputChannelToken"))){source=window;break;}
        }
        if(source==null || !WindowIdentity.ownsPicker(source,display))return null;
        WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(source,"mAttrs");
        if(!TITLE.contentEquals(attrs.getTitle()))return null;
        Object label=XposedHelpers.getObjectField(attrs,"accessibilityTitle");
        if(!(label instanceof CharSequence identity))return null;
        String[] parts=identity.toString().split("\n",2);
        String expected=parts[0];
        Touch activeTouch=touch;
        if(parts.length==2 && activeTouch!=null && activeTouch.display()==display
                && (activeTouch.down()+":"+activeTouch.device()+":"+activeTouch.pointer()).equals(parts[1])){
            for(Object window:((Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap")).values()){
                if(activeTouch.input().equals(XposedHelpers.getObjectField(window,"mInputChannelToken")))
                    return bindInput(wm,source,window,eligibleTouch(window,source,expected));
            }
            reportInput(wm,source,ShareDiagnostics.Code.LIFT_INPUT_CHANNEL_MISSING);
            return null;
        }
        Object focus=XposedHelpers.getObjectField(display,"mCurrentFocus");
        if(focus==null || focus==source || !Boolean.TRUE.equals(XposedHelpers.callMethod(focus,"isVisible"))){
            reportInput(wm,source,ShareDiagnostics.Code.LIFT_INPUT_FOCUS_MISMATCH);
            return null;
        }
        WindowManager.LayoutParams focused=(WindowManager.LayoutParams)XposedHelpers.getObjectField(focus,"mAttrs");
        if(!expected.equals(focused.packageName)){
            reportInput(wm,source,ShareDiagnostics.Code.LIFT_INPUT_FOCUS_MISMATCH);
            return null;
        }
        if(subWindow(focused)){
            if(!eligibleTouch(focus,source,expected))return bindInput(wm,source,focus,false);
            focus=XposedHelpers.callMethod(focus,"getParentWindow");
        }
        return bindInput(wm,source,focus,eligible(focus,source,expected));
    }

    private static IBinder bindInput(Object wm,Object source,Object window,boolean allowed) {
        IBinder channel=allowed?remember(source,window):null;
        ShareDiagnostics.Code result;
        if(channel!=null)result=ShareDiagnostics.Code.LIFT_INPUT_BOUND;
        else if(allowed)result=ShareDiagnostics.Code.LIFT_INPUT_CHANNEL_MISSING;
        else {
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            result=(attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0?ShareDiagnostics.Code.LIFT_INPUT_SECURE_WINDOW
                    :ShareDiagnostics.Code.LIFT_INPUT_WINDOW_REJECTED;
        }
        reportInput(wm,source,result);
        return channel;
    }

    private static void reportInput(Object wm,Object source,ShareDiagnostics.Code code) {
        // These events use the same user as the verified SystemUI source window.
        int sourceUser=XposedHelpers.getIntField(source,"mOwnerUid")/100000;
        ShareDiagnosticReport.forward(()->{
            android.content.Context context=(android.content.Context)XposedHelpers.getObjectField(wm,"mContext");
            return (android.content.Context)XposedHelpers.callMethod(context,"createContextAsUser",
                    android.os.UserHandle.getUserHandleForUid(sourceUser*100000),0);
        },ShareDiagnostics.Stage.IMAGE_LIFT,code);
    }

    private static IBinder remember(Object portalWindow,Object window) {
        IBinder channel=(IBinder)XposedHelpers.getObjectField(window,"mInputChannelToken");
        if(channel!=null)XposedHelpers.setAdditionalInstanceField(portalWindow,ORIGINAL_WINDOW,window);
        return channel;
    }

    private static boolean eligibleTouch(Object window,Object source,String expected) {
        WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
        if(!subWindow(attrs))return eligible(window,source,expected);
        if(window==source || !expected.equals(attrs.packageName) || (attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible")))return false;
        Object parent=XposedHelpers.callMethod(window,"getParentWindow");
        return parent!=null && eligible(parent,source,expected)
                && XposedHelpers.getIntField(parent,"mOwnerUid")==XposedHelpers.getIntField(window,"mOwnerUid")
                && XposedHelpers.callMethod(parent,"getTask")==XposedHelpers.callMethod(window,"getTask");
    }

    private static boolean eligible(Object window,Object source,String expected) {
        if(window==source || !Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible")))return false;
        WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
        if(!expected.equals(attrs.packageName) || subWindow(attrs)
                || (attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return false;
        Object task=XposedHelpers.callMethod(window,"getTask");
        if(task==null || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode")))return false;
        int uid=XposedHelpers.getIntField(window,"mOwnerUid"),owner=XposedHelpers.getIntField(source,"mOwnerUid");
        return uid>=0 && uid/100000==owner/100000;
    }

    private static boolean subWindow(WindowManager.LayoutParams attrs) {
        return attrs.type>=WindowManager.LayoutParams.FIRST_SUB_WINDOW && attrs.type<=WindowManager.LayoutParams.LAST_SUB_WINDOW;
    }
}
