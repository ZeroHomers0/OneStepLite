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

/** Offers the rendered image for one explicit hold. The original touch stream stays with WeChat. */
final class WechatImageSource {
    static final String PACKAGE="com.tencent.mm";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private Runnable hold;
    private View host;
    private float x,y;

    static void install() {
        WechatImageSource source=new WechatImageSource();
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
        ImageView image=findImage(decor,(int)x,(int)y,Math.round(56*decor.getResources().getDisplayMetrics().density));
        if(image==null)return;
        Drawable drawable=image.getDrawable();
        long down=event.getDownTime();
        host=decor;hold=()->{
            hold=null;host=null;
            if(!image.isShown() || !decor.hasWindowFocus() || image.getDrawable()!=drawable
                    || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)
                return;
            offer(activity,image,drawable,down);
        };
        decor.postDelayed(hold,Math.max(1,ViewConfiguration.getLongPressTimeout()-60L));
    }

    private static ImageView findImage(View decor,int x,int y,int minSize) {
        java.util.ArrayDeque<View> views=new java.util.ArrayDeque<>();
        views.push(decor);
        int[] position=new int[2];
        Rect visible=new Rect();
        for(int inspected=0;inspected<256 && !views.isEmpty();inspected++) {
            View view=views.pop();
            if(!view.isShown() || view.getAlpha()<=0)continue;
            view.getLocationInWindow(position);
            if(x<position[0] || y<position[1] || x>=position[0]+view.getWidth() || y>=position[1]+view.getHeight())
                continue;
            if(view instanceof ImageView image && image.getWidth()>=minSize && image.getHeight()>=minSize
                    && image.getDrawable()!=null && image.getLocalVisibleRect(visible)
                    && visible.contains(x-position[0],y-position[1]))return image;
            if(view instanceof ViewGroup group) {
                for(int i=Math.max(0,group.getChildCount()-128);i<group.getChildCount();i++)views.push(group.getChildAt(i));
            }
        }
        return null;
    }

    private static void offer(Activity activity,ImageView view,Drawable drawable,long down) {
        if(!BUSY.compareAndSet(false,true))return;
        android.content.Context context=activity.getApplicationContext();
        Handler main=new Handler(Looper.getMainLooper());
        WORKER.execute(()->{
            try {
                Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_wechat_status",null,null);
                if(state==null || state.getLong("down")!=down || !state.getBoolean("prefer_image")
                        || !main.post(()->{
                            if(!view.isAttachedToWindow() || !view.isShown() || view.getDrawable()!=drawable
                                    || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0) {
                                BUSY.set(false);return;
                            }
                            render(context,drawable,state);
                        }))BUSY.set(false);
            } catch(RuntimeException unavailable){BUSY.set(false);}
        });
    }

    private static void render(android.content.Context context,Drawable drawable,Bundle state) {
        Bitmap image=null;
        try {
            Rect bounds=drawable.getBounds();
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
                drawable.draw(canvas);
            }
            if(image==null){BUSY.set(false);return;}
        } catch(RuntimeException unavailable) {
            if(image!=null)image.recycle();
            BUSY.set(false);return;
        }
        Bitmap captured=image;
        WORKER.execute(()->{
            try {
                state.putByteArray("image",CaptureImageEncoder.encode(captured));
                Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_wechat_offer",null,state);
                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,
                        result!=null && result.getBoolean("accepted")?ShareDiagnostics.Code.ACCEPTED:ShareDiagnostics.Code.REJECTED);
            } catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
            finally{captured.recycle();BUSY.set(false);}
        });
    }
}
