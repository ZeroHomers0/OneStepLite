package io.github.zerohomers0.onesteplite;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Replaces only the live workspace's ordinary native text shadow, without source app hooks. */
final class NativeTextDragCard {
    private static final String ORIGINAL="native_text_original_surface";
    private static final String REPLACEMENT="native_text_card_surface";
    private NativeTextDragCard(){}
    static void install(ClassLoader loader,Supplier<Object> display,BooleanSupplier active) {
        try {
            Class<?> controller=XposedHelpers.findClass("com.android.server.wm.DragDropController",loader);
            XposedBridge.hookAllMethods(controller,"performDrag",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if(!active.getAsBoolean() || call.args.length!=13 || !(call.args[4] instanceof SurfaceControl)
                            || !(call.args[12] instanceof ClipData))return;
                    try{replace(call,display.get());}
                    catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card left unchanged",unavailable);}
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    try {
                        Object original=call.getObjectExtra(ORIGINAL);if(original instanceof SurfaceControl)((SurfaceControl)original).release();
                        if(call.hasThrowable() || call.getResult()==null) {
                            Object surface=call.getObjectExtra(REPLACEMENT);
                            if(surface instanceof SurfaceControl)remove((SurfaceControl)surface);
                        }
                    }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card cleanup failed",unavailable);}
                }
            });
        }catch(Throwable unavailable){android.util.Log.w(Tags.PROBE,"native text card hook unavailable",unavailable);}
    }
    private static void replace(XC_MethodHook.MethodHookParam call,Object display) {
        ClipData original=(ClipData)call.args[12];String text=plainText(original);
        if(display==null || text==null || ContentDragShadow.offset(original.getDescription())>0
                || (((Integer)call.args[3])&View.DRAG_FLAG_GLOBAL)==0)return;
        Object wm=XposedHelpers.getObjectField(display,"mWmService");Context context;
        synchronized(XposedHelpers.getObjectField(wm,"mGlobalLock")) {
            Map<?,?> windows=(Map<?,?>)XposedHelpers.getObjectField(wm,"mWindowMap");
            android.os.IBinder token=((android.os.IInterface)call.args[2]).asBinder();Object source=windows.get(token);
            if(source==null || XposedHelpers.callMethod(source,"getDisplayContent")!=display
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(source,"isVisible")))return;
            int uid=XposedHelpers.getIntField(source,"mOwnerUid");
            if(uid!=(Integer)call.args[1] || uid/100000!=0)return;
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(source,"mAttrs");
            if(Packages.SYSTEM_UI.equals(attrs.packageName) || ContentInboxProvider.MODULE.equals(attrs.packageName)
                    || (attrs.flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return;
            Object task=XposedHelpers.callMethod(source,"getTask");
            if(task==null || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode")))return;
            context=(Context)XposedHelpers.getObjectField(wm,"mContext");
        }
        ContentDragShadow card=new ContentDragShadow(text,context.getResources().getDisplayMetrics().density);
        Point size=new Point(),touch=new Point();card.onProvideShadowMetrics(size,touch);
        SurfaceControl surface=draw(card,size);ClipData clip=new ClipData(new ClipDescription(original.getDescription()),original.getItemAt(0));
        card.mark(clip);call.setObjectExtra(ORIGINAL,call.args[4]);call.setObjectExtra(REPLACEMENT,surface);
        call.args[4]=surface;call.args[10]=(float)touch.x;call.args[11]=(float)touch.y;call.args[12]=clip;
        android.util.Log.i(Tags.PROBE,"native text drag card applied");
    }
    private static String plainText(ClipData clip) {
        if(clip.getItemCount()!=1 || !clip.getDescription().hasMimeType("text/plain"))return null;
        ClipData.Item item=clip.getItemAt(0);CharSequence text=item.getText();
        if(item.getIntent()!=null || item.getUri()!=null || text==null || text.length()==0 || text.length()>ContentSharePolicy.MAX_TEXT)return null;
        return text.toString();
    }
    private static SurfaceControl draw(ContentDragShadow card,Point size) {
        SurfaceControl control=new SurfaceControl.Builder().setName("OneStepLiteTextDragCard")
                .setBufferSize(size.x,size.y).setFormat(PixelFormat.TRANSLUCENT).build();
        Surface surface=null;boolean drawn=false;
        try {
            surface=new Surface(control);
            Canvas canvas=surface.lockCanvas(null);
            try{canvas.drawColor(android.graphics.Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);card.onDrawShadow(canvas);}
            finally{surface.unlockCanvasAndPost(canvas);}
            drawn=true;return control;
        }finally{if(surface!=null)surface.release();if(!drawn)remove(control);}
    }
    private static void remove(SurfaceControl surface) {
        try{if(surface.isValid())try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){
            XposedHelpers.callMethod(transaction,"remove",surface);transaction.apply();}}
        finally{surface.release();}
    }
}
