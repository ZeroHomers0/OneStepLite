package io.github.zerohomers0.onesteplite;

import android.os.IBinder;
import android.view.WindowManager;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The native drag takes the original application's touch stream, never fabricated input. */
final class PortalDragInput {
    static final String TITLE="OneStepLitePortalSource";
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
    private static IBinder source(Object wm,Object display,Object token) {
        Object source=null;
        for(Object window:((Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap")).values()) {
            if(token.equals(XposedHelpers.getObjectField(window,"mInputChannelToken"))){source=window;break;}
        }
        if(source==null || !WindowIdentity.ownsPicker(source,display))return null;
        WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(source,"mAttrs");
        if(!TITLE.contentEquals(attrs.getTitle()))return null;
        Object focus=XposedHelpers.getObjectField(display,"mCurrentFocus");
        if(focus==null || focus==source || !Boolean.TRUE.equals(XposedHelpers.callMethod(focus,"isVisible")))return null;
        WindowManager.LayoutParams focused=(WindowManager.LayoutParams)XposedHelpers.getObjectField(focus,"mAttrs");
        Object expected=XposedHelpers.getObjectField(attrs,"accessibilityTitle");
        if(!(expected instanceof CharSequence) || !expected.toString().equals(focused.packageName))return null;
        Object task=XposedHelpers.callMethod(focus,"getTask");
        if(task==null || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode")))return null;
        int uid=XposedHelpers.getIntField(focus,"mOwnerUid"),owner=XposedHelpers.getIntField(source,"mOwnerUid");
        if(uid/100000!=owner/100000)return null;
        return (IBinder)XposedHelpers.getObjectField(focus,"mInputChannelToken");
    }
}
