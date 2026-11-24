package io.github.zerohomers0.onesteplite;

import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Non-touchable green feedback for a verified active editor, owned only by the drag session. */
final class EditorDragOutline implements AutoCloseable {
    private static WeakReference<EditorDragOutline> active=new WeakReference<>(null);
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final View owner;
    private final BooleanSupplier ready;
    private final java.util.function.Consumer<String> feedback;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Binder life=new Binder();
    private final Runnable refresh=this::read;
    private final Runnable expire=this::close;
    private String ticket;
    private RectF bounds;
    private View outline;
    private WindowManager windows;
    private int generation;
    private float x,y;
    private boolean dragging,pending,hasPointer;
    private ShareDiagnostics.Code availability=ShareDiagnostics.Code.NO_SESSION;
    private String lastFeedback;
    private long waitingSince;
    EditorDragOutline(View owner,BooleanSupplier ready,java.util.function.Consumer<String> feedback){this.owner=owner;this.ready=ready;this.feedback=feedback;}
    void start(ClipDescription types){
        close();EditorDragOutline previous=active.get();if(previous!=null && previous!=this)previous.close();
        active=new WeakReference<>(this);dragging=true;int token=generation;
        main.postDelayed(expire,60_000L);
        WORKER.execute(()->{
            String result=null;ShareDiagnostics.Code state=ShareDiagnostics.Code.FAILED;
            try{Bundle input=new Bundle();input.putParcelable("types",types);input.putBinder("owner",life);
                Bundle reply=owner.getContext().getContentResolver().call(ContentInboxProvider.ROOT,"editor_bounds_start",null,input);
                result=reply==null?null:reply.getString("ticket");state=state(reply);
            }catch(RuntimeException unavailable){/* Optional input-method scope may not be active. */}
            String issued=result;ShareDiagnostics.Code outcome=state;
            main.post(()->{if(token!=generation || !live()){end(issued);return;}
                availability=outcome;waitingSince=android.os.SystemClock.uptimeMillis();ticket=issued;render();if(ticket!=null)read();});
        });
    }
    static void motion(MotionEvent event){
        EditorDragOutline current=active.get();if(current==null)return;
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL){current.close();return;}
        if(event.getActionMasked()!=MotionEvent.ACTION_MOVE)return;
        current.x=event.getRawX();current.y=event.getRawY();current.hasPointer=true;current.render();
        if(current.ticket!=null && !current.pending && !current.main.hasCallbacks(current.refresh))current.main.post(current.refresh);
    }
    private boolean live(){return dragging && owner.isAttachedToWindow() && ready.getAsBoolean() && ContentAccessCompat.currentProcessUser();}
    private void read(){
        if(!live()){close();return;}if(ticket==null || pending)return;
        if(!hasPointer || !ContentDragMotion.pointsDown()){hide();return;}
        int token=generation;String id=ticket;pending=true;
        WORKER.execute(()->{
            RectF rect=null;ShareDiagnostics.Code state=ShareDiagnostics.Code.FAILED;
            try{Bundle input=new Bundle();input.putString("ticket",id);
                Bundle reply=owner.getContext().getContentResolver().call(ContentInboxProvider.ROOT,"editor_bounds_read",null,input);
                rect=reply==null?null:reply.getParcelable("bounds",RectF.class);
                state=state(reply);
            }catch(RuntimeException unavailable){/* No rectangle means no feedback. */}
            RectF result=rect;ShareDiagnostics.Code outcome=state;main.post(()->{
                if(token!=generation)return;pending=false;if(!live()){close();return;}
                bounds=result;availability=outcome;render();main.removeCallbacks(refresh);
                if(outcome==ShareDiagnostics.Code.NO_IME || outcome==ShareDiagnostics.Code.NO_SESSION || outcome==ShareDiagnostics.Code.TIMEOUT
                        || outcome==ShareDiagnostics.Code.NO_EDITOR || outcome==ShareDiagnostics.Code.STALE_TARGET){String old=ticket;ticket=null;end(old);}
                if(ticket!=null && hasPointer && ContentDragMotion.pointsDown())main.postDelayed(refresh,250L);
            });
        });
    }
    private void render(){
        if(!hasPointer || !ContentDragMotion.pointsDown()){lastFeedback=null;hide();return;}
        ShareDiagnostics.Code code=availability;
        if(code==ShareDiagnostics.Code.WAITING_BOUNDS && android.os.SystemClock.uptimeMillis()-waitingSince>1500)code=ShareDiagnostics.Code.UNSUPPORTED;
        int width=owner.getResources().getDisplayMetrics().widthPixels,height=owner.getResources().getDisplayMetrics().heightPixels;
        if(bounds!=null){RectF visible=new RectF(bounds);if(!visible.intersect(0,0,width,height)){showState(ShareDiagnostics.Code.UNSUPPORTED,false);hide();return;}bounds=visible;}
        boolean hit=bounds!=null && EditorHitPolicy.hit(live(),ContentDragMotion.pointsDown(),x,y-ContentDragMotion.aimOffset(),
                bounds.left,bounds.top,bounds.right,bounds.bottom,width,height);
        showState(code,hit);if(!hit){hide();return;}
        int pad=HyperOsStyle.dp(owner.getContext(),3),left=(int)Math.floor(bounds.left)-pad,top=(int)Math.floor(bounds.top)-pad;
        try{
            if(outline==null){Context context=owner.getContext().createDisplayContext(owner.getDisplay()).createWindowContext(WindowTypes.SYSTEM_UI_DIALOG,null);
                windows=context.getSystemService(WindowManager.class);outline=new Border(context);
                WindowManager.LayoutParams attrs=new WindowManager.LayoutParams((int)Math.ceil(bounds.width())+pad*2,(int)Math.ceil(bounds.height())+pad*2,
                        WindowTypes.SYSTEM_UI_DIALOG,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
                attrs.gravity=Gravity.TOP|Gravity.START;attrs.x=left;attrs.y=top;attrs.setFitInsetsTypes(0);attrs.setTitle("OneStepLiteContentPanel");
                HostWindowPolicy.configure(attrs);windows.addView(outline,attrs);
            }else{WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)outline.getLayoutParams();
                int w=(int)Math.ceil(bounds.width())+pad*2,h=(int)Math.ceil(bounds.height())+pad*2;
                if(attrs.x!=left || attrs.y!=top || attrs.width!=w || attrs.height!=h){attrs.x=left;attrs.y=top;attrs.width=w;attrs.height=h;windows.updateViewLayout(outline,attrs);}}
        }catch(RuntimeException unavailable){showState(ShareDiagnostics.Code.FAILED,false);hide();}
    }
    private static ShareDiagnostics.Code state(Bundle reply){
        try{return ShareDiagnostics.Code.valueOf(reply==null?"FAILED":reply.getString("state"));}
        catch(IllegalArgumentException | NullPointerException invalid){return ShareDiagnostics.Code.FAILED;}
    }
    private void showState(ShareDiagnostics.Code code,boolean hit){
        String key=code.name()+hit;if(key.equals(lastFeedback))return;lastFeedback=key;
        ShareDiagnostics.event(ShareDiagnostics.Stage.EDITOR,code);
        String text=switch(code){
            case READY->hit?"松开交给输入框":"将箭头拖入输入框";
            case NO_EDITOR,STALE_TARGET->"请先点击要接收内容的输入框";
            case NO_IME->"当前输入法不可用";
            case NO_TRANSFORM->"暂时无法确认输入框位置";
            case TYPE_UNSUPPORTED->"未确认输入框支持此内容";
            case WAITING_BOUNDS->"正在获取输入框边界…";
            case UNSUPPORTED->"未获得边界，请检查当前输入法作用域";
            default->"输入框检测暂不可用，请重新提起";
        };feedback.accept(text);
    }
    private void hide(){View old=outline;outline=null;if(old!=null)try{windows.removeViewImmediate(old);}catch(RuntimeException detached){/* Already gone. */}}
    private void end(String id){if(id==null)return;WORKER.execute(()->{try{Bundle input=new Bundle();input.putString("ticket",id);
        owner.getContext().getContentResolver().call(ContentInboxProvider.ROOT,"editor_bounds_end",null,input);}catch(RuntimeException ended){/* IME also has a bounded expiry. */}});}
    @Override public void close(){generation++;dragging=false;hasPointer=false;pending=false;bounds=null;main.removeCallbacks(refresh);main.removeCallbacks(expire);
        availability=ShareDiagnostics.Code.NO_SESSION;lastFeedback=null;waitingSince=0;
        hide();String old=ticket;ticket=null;end(old);if(active.get()==this)active.clear();}
    private static final class Border extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path=new android.graphics.Path();
        Border(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff35bd76);paint.setStrokeWidth(HyperOsStyle.dp(context,2));}
        @Override protected void onDraw(Canvas canvas){float pad=HyperOsStyle.dp(getContext(),3),radius=HyperOsStyle.dp(getContext(),6);
            path.reset();path.addRoundRect(pad,pad,getWidth()-pad,getHeight()-pad,radius,radius,android.graphics.Path.Direction.CW);
            canvas.drawPath(path,paint);}
    }
}
