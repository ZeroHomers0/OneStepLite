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
    private static int pointerId=-1;
    private static long downTime=-1;
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
        int id=pointerId;long started=downTime;
        close();pointerId=id;downTime=started;
        source=new WeakReference<>(view);shadow=card;MAIN.postDelayed(expiry,60_000L);
    }
    static void motion(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN && geometry==null){
            pointerId=event.getPointerId(0);downTime=event.getDownTime();
            return;
        }
        if(geometry==null && (action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP)){
            close();return;
        }
        if(shadow==null && geometry==null || releasedAt>=0)return;
        if(geometry!=null && pointerId<0 && action==MotionEvent.ACTION_MOVE){
            pointerId=event.getPointerId(0);downTime=event.getDownTime();
        }
        if(geometry!=null && event.getDownTime()==downTime){
            int index=event.findPointerIndex(pointerId);
            boolean lift=index>=0 && (action==MotionEvent.ACTION_UP
                    || action==MotionEvent.ACTION_POINTER_UP && event.getActionIndex()==index);
            if(index>=0 && (lift || action==MotionEvent.ACTION_MOVE
                    || action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP)){
                rawX=event.getRawX(index);rawY=event.getRawY(index);sampled=true;
            }
            if(lift || action==MotionEvent.ACTION_CANCEL){
                releasedAt=android.os.SystemClock.uptimeMillis();pointerId=-1;downTime=-1;
                MAIN.removeCallbacks(expiry);MAIN.postDelayed(expiry,350);return;
            }
        }
        View view=source.get();if(view==null || !view.isAttachedToWindow())close();
    }
    static void close(){
        MAIN.removeCallbacks(expiry);source.clear();if(shadow!=null)shadow.recycleThumbnail();
        shadow=null;geometry=null;offset=0;sampled=false;releasedAt=-1;pointerId=-1;downTime=-1;
    }
}
