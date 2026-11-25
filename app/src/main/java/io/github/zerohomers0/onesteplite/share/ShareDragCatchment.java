package io.github.zerohomers0.onesteplite;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;

/** Transparent, drag-only reach below the visible rail keeps the arrow as its aiming point. */
final class ShareDragCatchment implements AutoCloseable {
    private final ShareWorkspace owner;
    private final BooleanSupplier live;
    private int offset;
    private FrameLayout plate;
    private final Runnable show=this::show;
    ShareDragCatchment(ShareWorkspace owner,int offset,BooleanSupplier live){this.owner=owner;this.offset=offset;this.live=live;}
    void start(){owner.postOnAnimation(show);}
    void extend(int next){
        if(next<=offset)return;offset=next;if(plate==null)return;
        WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)plate.getLayoutParams();attrs.height=owner.getHeight()+offset;
        try{plate.getContext().getSystemService(WindowManager.class).updateViewLayout(plate,attrs);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"share arrow reach resize failed",unavailable);}
    }
    @android.annotation.SuppressLint("RtlHardcoded") // Window coordinates are physical screen coordinates.
    private void show() {
        if(plate!=null || !live.getAsBoolean() || !owner.isAttachedToWindow() || owner.getWidth()<=0 || owner.getHeight()<=0)return;
        try {
        Context context=owner.getContext().createDisplayContext(owner.getDisplay()).createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
        FrameLayout view=new FrameLayout(context);view.setOnDragListener((ignored,event)->owner.onTipDrag(view,event));
        android.graphics.Rect screen=new android.graphics.Rect();
        if(!ViewScreenBounds.read(owner,screen))return;
        WindowManager.LayoutParams attrs=new WindowManager.LayoutParams(screen.width(),screen.height()+offset,
                WindowTypes.SYSTEM_UI_DIALOG,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        attrs.gravity=Gravity.TOP|Gravity.LEFT;attrs.x=screen.left;attrs.y=screen.top;attrs.setFitInsetsTypes(0);
        attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setTitle(WindowIdentity.SHARE_RECEIVER_TITLE);HostWindowPolicy.configure(attrs);
        context.getSystemService(WindowManager.class).addView(view,attrs);plate=view;
        }
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"share arrow reach unavailable",unavailable);}
    }
    @Override public void close() {
        owner.removeCallbacks(show);FrameLayout old=plate;plate=null;
        if(old!=null)try{old.getContext().getSystemService(WindowManager.class).removeViewImmediate(old);}
        catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"share arrow reach release failed",unavailable);}
    }
}
