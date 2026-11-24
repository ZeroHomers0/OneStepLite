package io.github.zerohomers0.onesteplite;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A drag-only share window above occupied video; never reparents a task or video surface. */
final class ShareRailOverlay implements AutoCloseable {
    private final View owner;
    private final BooleanSupplier ready;
    private final BiConsumer<ComponentName,Runnable> dispatch;
    private final Consumer<String> hint;
    private final Supplier<Rect> bounds;
    private WindowManager windows;
    private FrameLayout plate;
    private ShareWorkspace rail;
    private ModalIslandLease island;
    private java.util.function.Function<String,String> hoverIdentity=value->value;
    private Consumer<String> hoverActivate=value->{};
    void setWindowHover(java.util.function.Function<String,String> identity,Consumer<String> activate) {
        hoverIdentity=identity;hoverActivate=activate;if(rail!=null)rail.setWindowHover(identity,activate);
    }

    ShareRailOverlay(View owner,BooleanSupplier ready,BiConsumer<ComponentName,Runnable> dispatch,
            Consumer<String> hint,Supplier<Rect> bounds) {
        this.owner=owner;this.ready=ready;this.dispatch=dispatch;this.hint=hint;this.bounds=bounds;
    }

    boolean isOpen(){return plate!=null;}
    void setMainOnRight(boolean right){if(rail!=null)rail.setMainOnRight(right);}

    boolean onDrag(DragEvent event,String targetPackage) {
        if(event.getAction()==DragEvent.ACTION_DRAG_STARTED && !isOpen()) {
            close();if(!ready.getAsBoolean() || !show())return false;
        }
        ShareWorkspace current=rail;if(current==null)return false;
        boolean accepted=current.onWindowDrag(event,targetPackage,false);
        if(event.getAction()==DragEvent.ACTION_DRAG_STARTED && !accepted){hide();return false;}
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)hide();
        return accepted;
    }

    private boolean show() {
        Rect main=bounds.get();if(main==null || main.isEmpty())return false;
        try {
            Context context=owner.getContext().createDisplayContext(owner.getDisplay())
                    .createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
            windows=context.getSystemService(WindowManager.class);
            plate=new FrameLayout(context);View normal=new View(context);plate.addView(normal,new FrameLayout.LayoutParams(-1,-1));
            rail=new ShareWorkspace(context,normal,ready,dispatch,hint,bounds,true);
            rail.setWindowHover(hoverIdentity,hoverActivate);
            if(owner instanceof ShareWorkspace)rail.setResultSink(((ShareWorkspace)owner)::onDeliveryResult);
            plate.addView(rail,new FrameLayout.LayoutParams(-1,-1));
            int height=HyperOsStyle.dp(context,50);
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(
                    owner.getResources().getDisplayMetrics().widthPixels,height,WindowTypes.SYSTEM_UI_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.START;params.y=Math.max(0,main.top-height);
            params.setTitle("OneStepLiteContentPanel");params.setFitInsetsTypes(0);HostWindowPolicy.configure(params);
            windows.addView(plate,params);island=new ModalIslandLease(plate);island.start();return true;
        } catch(RuntimeException unavailable){close();android.util.Log.w(Tags.PROBE,"share overlay unavailable",unavailable);return false;}
    }

    private void hide() {
        ShareWorkspace current=rail;
        boolean pending=current!=null && current.retainDelivery(()->{if(plate==null && rail==current)rail=null;});
        FrameLayout old=plate;plate=null;
        if(island!=null){island.close();island=null;}
        if(old!=null)try{windows.removeViewImmediate(old);}catch(RuntimeException unavailable){android.util.Log.w(Tags.PROBE,"share overlay release",unavailable);}
        if(!pending){rail=null;if(current!=null)current.close();}
    }

    @Override public void close() {
        ShareWorkspace current=rail;rail=null;if(current!=null)current.close();hide();
    }
}
