package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Fades real task surfaces across native ownership changes without changing geometry. */
final class TopTaskTransition {
    private final FrameLayout host;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Face face;
    private final TransitionSession session;
    private final Runnable handoff;
    private final Consumer<TopTaskTransition> finished;
    private final Consumer<String> log;
    private volatile Object liveSurface;
    private Object outgoingSurface;
    private BooleanSupplier sourceCurrent, outgoingCurrent;
    private View destination;
    private Object destinationSurface;
    private View companion;
    private float destinationAlpha;
    private Runnable nativeRestore;
    private ValueAnimator animator;
    private volatile float opacity;
    private boolean attached, firstDraw, prepared, started, handedOff, admitted;
    private volatile boolean revealing;
    private volatile boolean closed;
    private final Runnable timeout=this::close;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {close();}
    };

    TopTaskTransition(FrameLayout host,int width,int height,BooleanSupplier current,Runnable handoff,
            Consumer<TopTaskTransition> finished,Consumer<String> log) throws Throwable {
        this.host=host;this.session=new TransitionSession(current);this.handoff=handoff;this.finished=finished;this.log=log;
        face=new Face();
        windows=(WindowManager)host.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.windowAnimations=0;
        params.setTitle("OneStepLiteUpperTransition");
        HostWindowPolicy.configure(params);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        face.setOnTouchListener((view,event)->true);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void start() {
        if(closed || attached)return;
        if(!session.active()) {close();return;}
        windows.addView(face,params);attached=true;
        host.addOnAttachStateChangeListener(attachment);
        host.postDelayed(timeout,5000L);
    }

    void collect(Object surface,BooleanSupplier sourceCurrent,View destination) {
        liveSurface=surface;this.sourceCurrent=sourceCurrent;this.destination=destination;
        destinationAlpha=destination.getAlpha();
        destination.setAlpha(0f);
    }

    void holdDestination(Object parent) {
        destinationSurface=parent;
        if(parent!=null)writeAlpha(parent,0f);
    }

    /** Appended last to native transactions, including late TaskView open/finish writes. */
    void appendDestination(Object tx,Object parent,Object leash) {
        if(closed || !session.active() || destination==null)return;
        if(parent!=null)XposedHelpers.callMethod(tx,"setAlpha",parent,revealing?1f:0f);
        if(leash!=null)XposedHelpers.callMethod(tx,"setAlpha",leash,revealing?opacity:0f);
    }

    /** Native admission has replaced fullscreen ownership; retire its old predicate. */
    void setLiveSurface(Object surface) {
        liveSurface=surface;sourceCurrent=null;admitted=destination!=null;
        // The Shell callback can arrive after the watchdog retired this bridge.
        // Its native handoff starts at alpha zero, so release that late surface too.
        if(closed)writeAlpha(surface,1f);
        else if(admitted)writeAlpha(surface,0f);
    }

    void setNativeRestore(Runnable restore) {
        if(closed)restore.run();else nativeRestore=restore;
    }

    boolean collectionReady() {
        if(closed || destination==null)return false;
        if(!session.active() || !validLiveSurface()) {close();return false;}
        prepared=true;startCollectionIfReady();return true;
    }

    private void startCollectionIfReady() {
        if(closed || !firstDraw || !prepared || started)return;
        started=true;
        face.postOnAnimation(()->{
            if(closed)return;
            if(!session.active() || !validLiveSurface()) {close();return;}
            animate(1f,0f,this::handoff);
        });
    }

    private void handoff() {
        if(closed || handedOff)return;
        handedOff=true;
        if(!session.active()) {close();return;}
        if(destination!=null)destination.setAlpha(0f);
        if(destinationSurface!=null)writeAlpha(destinationSurface,0f);
        try {handoff.run();}
        catch(Throwable error) {log.accept("upper handoff: "+error);close();}
    }

    /** The outgoing strip shares the bridge's scene guard and cancellation. */
    void fadeOut(Object surface,BooleanSupplier sourceCurrent,Runnable after) {
        if(closed)return;
        outgoingSurface=surface;outgoingCurrent=sourceCurrent;
        liveSurface=surface;this.sourceCurrent=sourceCurrent;
        animate(1f,0f,after);
    }

    /** The exit plate follows the outgoing frame and is hidden before native return. */
    void followOpacity(View view) {companion=view;}

    boolean ownsTopOpacity() {return !closed && (destination!=null || outgoingSurface!=null && !revealing);}

    /** Keep the collected canonical task transparent while native admission is pending. */
    boolean ownsTaskOpacity() {return !closed && destination!=null && handedOff;}

    void abortAdmission() {
        admitted=false;destinationAlpha=0f;close();
    }

    void reveal() {
        if(closed || !handedOff || revealing)return;
        opacity=0f;revealing=true;
        if(destinationSurface!=null)writeAlpha(destinationSurface,1f);
        if(destination!=null)destination.setAlpha(1f);
        if(!validLiveSurface()) {close();return;}
        animate(0f,1f,this::close);
    }

    private void animate(float from,float to,Runnable end) {
        if(closed)return;
        ValueAnimator next=ValueAnimator.ofFloat(from,to);animator=next;
        next.setDuration(OverlayMotionSpec.TOP_FADE_MS);
        next.setInterpolator(new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value->{
            if(closed || animator!=next)return;
            opacity=(Float)value.getAnimatedValue();
            if(!session.active() || !validLiveSurface()
                    || !writeAlpha(liveSurface,(Float)value.getAnimatedValue())) {close();return;}
            if(companion!=null && !revealing)companion.setAlpha((Float)value.getAnimatedValue());
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(closed || animator!=animation)return;
                if(companion!=null && !revealing && to==0f)companion.setVisibility(View.INVISIBLE);
                animator=null;end.run();
            }
        });
        next.start();
    }

    void close() {
        if(closed)return;
        closed=true;
        session.retire();
        ValueAnimator old=animator;animator=null;
        if(old!=null)old.cancel();
        if(validLiveSurface())writeAlpha(liveSurface,1f);
        if(outgoingSurface!=null && outgoingCurrent!=null && outgoingCurrent.getAsBoolean())
            writeAlpha(outgoingSurface,1f);
        if(destination!=null)destination.setAlpha(admitted?1f:destinationAlpha);
        if(destinationSurface!=null)writeAlpha(destinationSurface,admitted?1f:destinationAlpha);
        if(companion!=null && outgoingCurrent!=null && outgoingCurrent.getAsBoolean()) {
            companion.setAlpha(1f);companion.setVisibility(View.VISIBLE);
        }
        Runnable restore=nativeRestore;nativeRestore=null;
        if(restore!=null)try {restore.run();}
        catch(Throwable error) {log.accept("upper native restore: "+error);}
        host.removeCallbacks(timeout);host.removeOnAttachStateChangeListener(attachment);
        if(attached) {
            face.setVisibility(View.GONE);
            try {windows.removeViewImmediate(face);}
            catch(Throwable error) {log.accept("upper transition removal: "+error);}
            attached=false;
        }
        finished.accept(this);
    }

    private boolean validLiveSurface() {
        try {
            return (sourceCurrent==null || sourceCurrent.getAsBoolean()) && liveSurface!=null
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(liveSurface,"isValid"));
        } catch(Throwable ignored) {return false;}
    }

    private boolean writeAlpha(Object surface,float alpha) {
        Object transaction=null;
        try {
            if(!Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid")))return false;
            transaction=TransactionCompat.newSurfaceTransaction();
            XposedHelpers.callMethod(transaction,"setAlpha",surface,alpha);
            XposedHelpers.callMethod(transaction,"apply");return true;
        } catch(Throwable error) {log.accept("upper fade: "+error);return false;}
        finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    private final class Face extends View {
        Face() {super(host.getContext());}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(firstDraw)return;
            firstDraw=true;
            if(destination!=null)startCollectionIfReady();
            else postOnAnimation(()->{if(!closed)handoff();});
        }
    }
}
