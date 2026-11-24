package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.lang.ref.WeakReference;

/** Reuses the active workspace pointer observer; tracks the fixed arrow in physical screen coordinates. */
final class ContentDragMotion {
    private static WeakReference<View> source=new WeakReference<>(null);
    private static ContentDragShadow shadow;
    private static String geometry;
    private static int offset;
    private static long releasedAt=-1;
    private static boolean sampled;
    private static float rawX,rawY;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Runnable expiry=ContentDragMotion::close;
    private ContentDragMotion(){}
    static void managed(View owner,android.content.ClipDescription description){
        android.os.PersistableBundle data=description==null?null:description.getExtras();
        String id=data==null?null:data.getString("onesteplite_geometry_id");if(id==null)return;
        float touch=data.getInt("onesteplite_shadow_touch_y",0);
        int declared=ContentDragShadow.offset(description);
        float density=(touch-declared)/3f;if(density<.5f || density>8 || touch<=0)return;
        if(id.equals(geometry))return;
        geometry=id;sampled=false;releasedAt=-1;offset=declared;
        if(source.get()==null)source=new WeakReference<>(owner);
        MAIN.removeCallbacks(expiry);MAIN.postDelayed(expiry,60_000L);
    }
    static boolean matches(android.content.ClipDescription description){
        return geometry!=null && description!=null && description.getExtras()!=null
                && geometry.equals(description.getExtras().getString("onesteplite_geometry_id"));
    }
    static boolean physicalTip(android.content.ClipDescription description,float[] point){
        if(!matches(description) || !sampled
                || releasedAt>=0 && android.os.SystemClock.uptimeMillis()-releasedAt>350)return false;
        point[0]=rawX;point[1]=rawY-offset;return true;
    }

    static void stage(View view,ContentDragShadow card){
        close();source=new WeakReference<>(view);shadow=card;MAIN.postDelayed(expiry,60_000L);
    }
    static void motion(MotionEvent event){
        if(shadow==null && geometry==null)return;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_CANCEL){close();return;}
        if(action==MotionEvent.ACTION_UP && geometry==null){close();return;}
        if(geometry!=null && event.getPointerCount()==1 && (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP)){
            rawX=event.getRawX();rawY=event.getRawY();sampled=true;
            if(action==MotionEvent.ACTION_UP){releasedAt=android.os.SystemClock.uptimeMillis();
                MAIN.removeCallbacks(expiry);MAIN.postDelayed(expiry,350);return;}
        }
        View view=source.get();if(view==null || !view.isAttachedToWindow())close();
    }
    static void close(){MAIN.removeCallbacks(expiry);source.clear();if(shadow!=null)shadow.recycleThumbnail();shadow=null;geometry=null;offset=0;sampled=false;releasedAt=-1;}
}
