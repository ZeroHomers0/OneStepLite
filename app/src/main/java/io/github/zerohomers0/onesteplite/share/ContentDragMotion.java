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
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Runnable expiry=ContentDragMotion::close;
    private ContentDragMotion(){}
    static boolean pointsDown(){return shadow!=null && shadow.pointsDown();}
    static void stage(View view,ContentDragShadow card){
        close();source=new WeakReference<>(view);shadow=card;MAIN.postDelayed(expiry,60_000L);
    }
    static void motion(MotionEvent event){
        if(shadow==null)return;
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL){close();return;}
        View view=source.get();if(view==null || !view.isAttachedToWindow()){close();return;}
        if(event.getPointerCount()!=1 || event.getActionMasked()!=MotionEvent.ACTION_MOVE)return;
        float density=view.getResources().getDisplayMetrics().density;
        float bottom=view.getResources().getDisplayMetrics().heightPixels;
        android.view.WindowInsets insets=view.getRootWindowInsets();
        if(insets!=null && insets.isVisible(android.view.WindowInsets.Type.ime()))bottom-=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        boolean down=event.getRawY()>bottom-(shadow.pointsDown()?200:140)*density;
        if(!shadow.pointDown(down))return;
        try{view.updateDragShadow(shadow);}catch(RuntimeException ended){close();}
    }
    static void close(){MAIN.removeCallbacks(expiry);source.clear();if(shadow!=null)shadow.recycleThumbnail();shadow=null;}
}
