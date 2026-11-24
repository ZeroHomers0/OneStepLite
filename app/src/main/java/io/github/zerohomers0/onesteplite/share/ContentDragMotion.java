package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.lang.ref.WeakReference;

/** Reuses the active workspace pointer observer; redraws an owned shadow only when its arrow changes side. */
final class ContentDragMotion {
    private static WeakReference<View> source=new WeakReference<>(null);
    private static ContentDragShadow shadow;
    private static String geometry;
    private static boolean managedDown;
    private static float unit,normalTouch,fromTouch,toTouch;
    private static long changed;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Runnable expiry=ContentDragMotion::close;
    private ContentDragMotion(){}
    static boolean pointsDown(){return geometry!=null?managedDown:shadow!=null && shadow.pointsDown();}
    static void managed(View owner,android.content.ClipDescription description){
        android.os.PersistableBundle data=description==null?null:description.getExtras();
        String id=data==null?null:data.getString("onesteplite_geometry_id");if(id==null)return;
        float touch=data.getInt("onesteplite_shadow_touch_y",0),offset=ContentDragShadow.offset(description);
        float density=(touch-offset)/3f;if(density<.5f || density>8 || touch<=0)return;
        geometry=id;managedDown=false;unit=density;normalTouch=touch;fromTouch=toTouch=touch;changed=0;
        if(source.get()==null)source=new WeakReference<>(owner);
        MAIN.removeCallbacks(expiry);MAIN.postDelayed(expiry,60_000L);
    }
    static int aimOffset(){
        if(geometry==null)return shadow!=null && !shadow.pointsDown()?Math.round(normalTouch-3*unit):0;
        float progress=android.animation.ValueAnimator.areAnimatorsEnabled()?Math.min(1,(android.os.SystemClock.uptimeMillis()-changed)/180f):1;
        float eased=(float)(.5-Math.cos(Math.max(0,progress)*Math.PI)/2);
        return Math.round(fromTouch+(toTouch-fromTouch)*eased-(managedDown?normalTouch:3*unit));
    }
    static boolean matches(android.content.ClipDescription description){
        return geometry!=null && description!=null && description.getExtras()!=null
                && geometry.equals(description.getExtras().getString("onesteplite_geometry_id"));
    }
    static void stage(View view,ContentDragShadow card){
        close();source=new WeakReference<>(view);shadow=card;MAIN.postDelayed(expiry,60_000L);
    }
    static void motion(MotionEvent event){
        if(shadow==null && geometry==null)return;
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL){close();return;}
        View view=source.get();if(view==null || !view.isAttachedToWindow()){close();return;}
        if(event.getPointerCount()!=1 || event.getActionMasked()!=MotionEvent.ACTION_MOVE)return;
        float density=view.getResources().getDisplayMetrics().density;
        float bottom=view.getResources().getDisplayMetrics().heightPixels;
        android.view.WindowInsets insets=view.getRootWindowInsets();
        if(insets!=null && insets.isVisible(android.view.WindowInsets.Type.ime()))bottom-=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        boolean down=event.getRawY()>bottom-(pointsDown()?200:140)*density;
        if(geometry!=null){
            if(down!=managedDown){float tip=managedDown?normalTouch:3*unit;
                fromTouch=aimOffset()+tip;managedDown=down;toTouch=down?3*unit:normalTouch;changed=android.os.SystemClock.uptimeMillis();}
            return;
        }
        if(!shadow.pointDown(down))return;
        try{view.updateDragShadow(shadow);}catch(RuntimeException ended){close();}
    }
    static void close(){MAIN.removeCallbacks(expiry);source.clear();if(shadow!=null)shadow.recycleThumbnail();shadow=null;geometry=null;managedDown=false;}
}
