package io.github.zerohomers0.onesteplite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Offers a visible image control for one explicit hold while the app retains its touch stream. */
final class ViewImageSource {
    private static final String WECHAT="com.tencent.mm";
    private static final String LARK="com.ss.android.lark";
    private static final String HOME_IMAGE="com.miui.newhome.view.GestureImage";
    private static final String LARK_IMAGE="com.ss.android.lark.imagewidget.largeimage.LargeImageView";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private Runnable hold;
    private View host;
    private float x,y;

    static boolean supports(String pkg) {
        return WECHAT.equals(pkg) || LARK.equals(pkg) || Packages.LAUNCHER.equals(pkg);
    }

    static void install() {
        ViewImageSource source=new ViewImageSource();
        XposedHelpers.findAndHookMethod(Activity.class,"dispatchTouchEvent",MotionEvent.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                try{source.touch((Activity)call.thisObject,(MotionEvent)call.args[0]);}
                catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
            }
        });
    }

    private void touch(Activity activity,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_MOVE && hold!=null && event.getPointerCount()==1
                && Math.hypot(event.getX()-x,event.getY()-y)<=ViewConfiguration.get(activity).getScaledTouchSlop())
            return;
        if(hold!=null)host.removeCallbacks(hold);
        hold=null;host=null;
        if(action!=MotionEvent.ACTION_DOWN || BUSY.get() || !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
                || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return;
        View decor=activity.getWindow().peekDecorView();
        if(decor==null || !decor.hasWindowFocus())return;
        x=event.getX();y=event.getY();
        String pkg=activity.getPackageName();
        View image=findImage(pkg,decor,(int)event.getRawX(),(int)event.getRawY(),
                Math.round(56*decor.getResources().getDisplayMetrics().density));
        if(image==null)return;
        Drawable drawable=imageDrawable(pkg,image);
        long down=event.getDownTime();
        host=decor;hold=()->{
            hold=null;host=null;
            if(!stillImage(pkg,image,drawable) || !decor.hasWindowFocus()
                    || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)
                return;
            offer(activity,image,drawable,down);
        };
        decor.postDelayed(hold,Math.max(1,ViewConfiguration.getLongPressTimeout()-60L));
    }

    private static View findImage(String pkg,View decor,int x,int y,int minSize) {
        java.util.ArrayDeque<View> views=new java.util.ArrayDeque<>();
        views.push(decor);
        int[] position=new int[2];
        Rect visible=new Rect();
        for(int inspected=0;inspected<256 && !views.isEmpty();inspected++) {
            View view=views.pop();
            if(!view.isShown() || view.getAlpha()<=0)continue;
            view.getLocationOnScreen(position);
            if(x<position[0] || y<position[1] || x>=position[0]+view.getWidth() || y>=position[1]+view.getHeight())
                continue;
            if(view.getWidth()>=minSize && view.getHeight()>=minSize && view.getLocalVisibleRect(visible)
                    && visible.contains(x-position[0],y-position[1])
                    && (imageDrawable(pkg,view)!=null || declaredImage(pkg,view)))return view;
            if(view instanceof ViewGroup group) {
                for(int i=Math.max(0,group.getChildCount()-128);i<group.getChildCount();i++)views.push(group.getChildAt(i));
            }
        }
        return null;
    }

    private static Drawable imageDrawable(String pkg,View view) {
        if(view instanceof ImageView image)return image.getDrawable();
        if(!WECHAT.equals(pkg) || !view.getClass().getName().contains("Image"))return null;
        try {
            Object value=XposedHelpers.getObjectField(view,"mDrawable");
            return value instanceof BitmapDrawable bitmap?bitmap:null;
        }catch(RuntimeException unavailable){return null;}
    }

    private static boolean declaredImage(String pkg,View view) {
        if(view instanceof android.view.SurfaceView || view instanceof android.view.TextureView)return false;
        String type=view.getClass().getName();
        if(LARK.equals(pkg) && LARK_IMAGE.equals(type) || Packages.LAUNCHER.equals(pkg) && HOME_IMAGE.equals(type))return true;
        return WECHAT.equals(pkg) && !(view instanceof ImageView || view instanceof ViewGroup)
                && ImageView.class.getName().contentEquals(java.util.Objects.toString(view.getAccessibilityClassName(),""));
    }

    private static boolean stillImage(String pkg,View view,Drawable drawable) {
        return view.isAttachedToWindow() && view.isShown() && view.getAlpha()>0
                && (drawable!=null?imageDrawable(pkg,view)==drawable:declaredImage(pkg,view));
    }

    private static void offer(Activity activity,View view,Drawable drawable,long down) {
        if(!BUSY.compareAndSet(false,true))return;
        android.content.Context context=activity.getApplicationContext();
        Handler main=new Handler(Looper.getMainLooper());
        WORKER.execute(()->{
            try {
                String pkg=context.getPackageName();
                Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_view_status",pkg,null);
                if(state==null || state.getLong("down")!=down || !state.getBoolean("prefer_image")
                        || !main.post(()->{
                            if(!stillImage(pkg,view,drawable)
                                    || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0) {
                                BUSY.set(false);return;
                            }
                            render(context,view,drawable,state);
                        }))BUSY.set(false);
            } catch(RuntimeException unavailable){BUSY.set(false);}
        });
    }

    private static void render(android.content.Context context,View view,Drawable drawable,Bundle state) {
        Bitmap image=null;
        try {
            Rect bounds=drawable==null?new Rect(0,0,view.getWidth(),view.getHeight()):drawable.getBounds();
            if(bounds.width()<=0 || bounds.height()<=0){BUSY.set(false);return;}
            int[] size=ContentCapturePolicy.fit(bounds.width(),bounds.height());
            if(drawable instanceof BitmapDrawable raster && raster.getBitmap()!=null
                    && raster.getBitmap().getConfig()==Bitmap.Config.HARDWARE) {
                Bitmap original=raster.getBitmap();
                int[] fitted=ContentCapturePolicy.fit(original.getWidth(),original.getHeight());
                Bitmap scaled=Bitmap.createScaledBitmap(original,fitted[0],fitted[1],true);
                try{image=scaled.copy(Bitmap.Config.ARGB_8888,false);}
                finally{if(scaled!=original)scaled.recycle();}
            } else {
                image=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(image);
                canvas.scale(size[0]/(float)bounds.width(),size[1]/(float)bounds.height());
                canvas.translate(-bounds.left,-bounds.top);
                if(drawable==null)view.draw(canvas);else drawable.draw(canvas);
            }
            if(image==null){BUSY.set(false);return;}
        } catch(RuntimeException unavailable) {
            if(image!=null)image.recycle();
            BUSY.set(false);return;
        }
        Bitmap captured=image;
        WORKER.execute(()->{
            try {
                if(SystemExtractPayload.hasNoVisiblePixels(captured)){
                    ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
                    return;
                }
                state.putByteArray("image",CaptureImageEncoder.encode(captured));
                Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_view_offer",context.getPackageName(),state);
                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,
                        result!=null && result.getBoolean("accepted")?ShareDiagnostics.Code.ACCEPTED:ShareDiagnostics.Code.REJECTED);
            } catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
            finally{captured.recycle();BUSY.set(false);}
        });
    }
}
