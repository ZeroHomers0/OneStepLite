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
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Offers the touched image or text control while the app retains its touch stream. */
final class ViewImageSource {
    private static final String WECHAT="com.tencent.mm";
    private static final String LARK="com.ss.android.lark";
    private static final String HOME_IMAGE="com.miui.newhome.view.GestureImage";
    private static final String LARK_IMAGE="com.ss.android.lark.imagewidget.largeimage.LargeImageView";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static boolean installed;
    private Runnable hold;
    private View host;
    private float x,y;

    static boolean supportsUid(int uid) {
        int appId=uid%100000;
        return appId>=10000 && appId<=19999;
    }

    static synchronized void install() {
        if(installed)return;
        ViewImageSource source=new ViewImageSource();
        XposedHelpers.findAndHookMethod(Activity.class,"dispatchTouchEvent",MotionEvent.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                try{source.touch((Activity)call.thisObject,(MotionEvent)call.args[0]);}
                catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
            }
        });
        installed=true;
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
        int rawX=(int)event.getRawX(),rawY=(int)event.getRawY();
        long down=event.getDownTime();
        long timeout=ViewConfiguration.getLongPressTimeout();
        host=decor;hold=()->{
            hold=null;host=null;
            if(!decor.hasWindowFocus())return;
            int minImage=Math.round(56*decor.getResources().getDisplayMetrics().density);
            View view=findContent(pkg,decor,rawX,rawY,minImage);
            if(view==null)return;
            Drawable drawable=imageDrawable(pkg,view);
            if(!canRead(activity,decor,view,rawX,rawY))return;
            boolean semantic=drawable==null && !readableText(view) && !declaredImage(pkg,view);
            if(drawable==null && (view instanceof TextView || semantic)){
                host=decor;hold=()->{
                    hold=null;host=null;
                    if(canRead(activity,decor,view,rawX,rawY))offer(activity,decor,view,null,true,semantic,down,rawX,rawY,minImage);
                };
                decor.postDelayed(hold,Math.max(0,down+timeout+20L-android.os.SystemClock.uptimeMillis()));
            } else if(stillImage(pkg,view,drawable))offer(activity,decor,view,drawable,false,false,down,rawX,rawY,minImage);
        };
        decor.postDelayed(hold,Math.max(1,timeout-60L));
    }

    private static View findContent(String pkg,View decor,int x,int y,int minSize) {
        java.util.ArrayDeque<View> views=new java.util.ArrayDeque<>();
        views.push(decor);
        int[] position=new int[2];
        Rect visible=new Rect();
        View semantic=null;
        for(int inspected=0;inspected<256 && !views.isEmpty();inspected++) {
            View view=views.pop();
            if(!view.isShown() || view.getAlpha()<=0 || view.isAccessibilityDataSensitive())continue;
            view.getLocationOnScreen(position);
            if(x<position[0] || y<position[1] || x>=position[0]+view.getWidth() || y>=position[1]+view.getHeight())
                continue;
            if(view.getLocalVisibleRect(visible) && visible.contains(x-position[0],y-position[1])) {
                if(readableText(view) || view.getWidth()>=minSize && view.getHeight()>=minSize
                        && (imageDrawable(pkg,view)!=null || declaredImage(pkg,view)))return view;
                if(semantic==null && !view.onCheckIsTextEditor() && view.getAccessibilityNodeProvider()!=null)
                    semantic=view;
            }
            if(view instanceof ViewGroup group) {
                for(int i=Math.max(0,group.getChildCount()-128);i<group.getChildCount();i++)views.push(group.getChildAt(i));
            }
        }
        return semantic;
    }

    private static boolean readableText(View view) {
        return view instanceof TextView words && !(words instanceof EditText) && !words.onCheckIsTextEditor()
                && !words.isAccessibilityDataSensitive() && words.getTransformationMethod()==null
                && words.getText().length()>0;
    }

    private static boolean canRead(Activity activity,View decor,View view,int x,int y) {
        if(!decor.hasWindowFocus() || !view.isAttachedToWindow() || !view.isShown() || view.getAlpha()<=0
                || view.isAccessibilityDataSensitive()
                || (activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0)return false;
        int[] at=new int[2];
        view.getLocationOnScreen(at);
        Rect bounds=new Rect();
        return view.getLocalVisibleRect(bounds) && bounds.contains(x-at[0],y-at[1]);
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
        return !(view instanceof ImageView || view instanceof ViewGroup)
                && ImageView.class.getName().contentEquals(java.util.Objects.toString(view.getAccessibilityClassName(),""));
    }

    private static boolean stillImage(String pkg,View view,Drawable drawable) {
        return view.isAttachedToWindow() && view.isShown() && view.getAlpha()>0
                && (drawable!=null?imageDrawable(pkg,view)==drawable:declaredImage(pkg,view));
    }

    private static void offer(Activity activity,View decor,View view,Drawable drawable,boolean text,
            boolean semantic,long down,int x,int y,int minImage) {
        if(!BUSY.compareAndSet(false,true))return;
        android.content.Context context=activity.getApplicationContext();
        Handler main=new Handler(Looper.getMainLooper());
        WORKER.execute(()->{
            try {
                String pkg=context.getPackageName();
                Bundle touch=new Bundle();touch.putLong("down",down);
                Bundle state=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_view_status",pkg,touch);
                if(state==null || state.getLong("down")!=down || !state.getBoolean("prefer_image")
                        || !main.post(()->{
                            try {
                                if(canRead(activity,decor,view,x,y) && (text || stillImage(pkg,view,drawable))) {
                                    if(semantic)offerSemantic(activity,context,decor,view,state,x,y,minImage,main);
                                    else if(text)offerText(context,view,state);
                                    else render(context,view,drawable,state);
                                    return;
                                }
                            } catch(RuntimeException unavailable) {
                                ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
                            }
                            BUSY.set(false);
                        }))BUSY.set(false);
            } catch(RuntimeException unavailable){BUSY.set(false);}
        });
    }

    private static void offerText(android.content.Context context,View view,Bundle state) {
        if(!readableText(view)){BUSY.set(false);return;}
        TextView words=(TextView)view;
        CharSequence value=words.getText();
        int start=words.getSelectionStart(),end=words.getSelectionEnd();
        boolean selected=start>=0 && end>=0 && start!=end && Math.max(start,end)<=value.length();
        if(selected)value=value.subSequence(Math.min(start,end),Math.max(start,end));
        if(value.length()>ContentSharePolicy.MAX_TEXT){BUSY.set(false);return;}
        String text=value.toString();
        if(text.isBlank()){BUSY.set(false);return;}
        submitText(context,state,text,selected);
    }

    private static void offerSemantic(Activity activity,android.content.Context context,View decor,
            View view,Bundle state,int x,int y,int minImage,Handler main) {
        ViewSemanticContent content=ViewSemanticContent.find(view,x,y,minImage);
        if(content==null){BUSY.set(false);return;}
        if(content.text()!=null){submitText(context,state,content.text(),content.selected());return;}
        Rect region=new Rect(content.image());
        int[] location=new int[2];
        view.getLocationInWindow(location);
        region.offset(location[0],location[1]);
        int[] size=ContentCapturePolicy.fit(region.width(),region.height());
        Bitmap image=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(),region,image,result->{
                try {
                    if(result==PixelCopy.SUCCESS && canRead(activity,decor,view,x,y)){
                        ViewSemanticContent current=ViewSemanticContent.find(view,x,y,minImage);
                        int[] now=new int[2];view.getLocationInWindow(now);
                        if(current!=null && content.image().equals(current.image())
                                && now[0]==location[0] && now[1]==location[1]){
                            submitImage(context,state,image);
                            return;
                        }
                    }
                } catch(RuntimeException unavailable){
                    ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);
                }
                image.recycle();BUSY.set(false);
            },main);
        } catch(RuntimeException unavailable){
            image.recycle();
            throw unavailable;
        }
    }

    private static void submitText(android.content.Context context,Bundle state,String text,boolean selected) {
        state.putString("text",text);
        state.putBoolean("selected",selected);
        WORKER.execute(()->{
            try { submit(context,state); }
            finally { BUSY.set(false); }
        });
    }

    private static void submit(android.content.Context context,Bundle state) {
        try {
            Bundle result=context.getContentResolver().call(ContentInboxProvider.ROOT,"portal_view_offer",context.getPackageName(),state);
            ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,
                    result!=null && result.getBoolean("accepted")?ShareDiagnostics.Code.ACCEPTED:ShareDiagnostics.Code.REJECTED);
        } catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
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
        submitImage(context,state,image);
    }

    private static void submitImage(android.content.Context context,Bundle state,Bitmap captured) {
        WORKER.execute(()->{
            try {
                if(SystemExtractPayload.hasNoVisiblePixels(captured)){
                    ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.NO_CONTENT);
                    return;
                }
                state.putByteArray("image",CaptureImageEncoder.encode(captured));
                submit(context,state);
            } catch(RuntimeException unavailable){ShareDiagnostics.event(ShareDiagnostics.Stage.EXTRACT,ShareDiagnostics.Code.UNSUPPORTED);}
            finally{captured.recycle();BUSY.set(false);}
        });
    }
}
