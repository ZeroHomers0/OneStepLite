package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.OverScroller;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Owns side-row animation and fling callbacks; task layout stays with the host. */
final class SideScrollMotion {
    private ValueAnimator animator;
    private OverScroller scroller;
    private View flingHost;
    private Runnable flingFrame;

    boolean running() { return animator!=null || flingFrame!=null; }

    void cancel() {
        ValueAnimator old=animator;animator=null;if(old!=null)old.cancel();
        Runnable frame=flingFrame;flingFrame=null;
        if(frame!=null) {
            flingHost.removeCallbacks(frame);scroller.forceFinished(true);
        }
        flingHost=null;
    }

    void animate(float from,float to,long duration,TimeInterpolator curve,
            BooleanSupplier current,Consumer<Float> update,Runnable done) {
        ValueAnimator next=ValueAnimator.ofFloat(from,to);animator=next;
        next.setDuration(duration);next.setInterpolator(curve);
        next.addUpdateListener(value->{
            if(animator==next && current.getAsBoolean())update.accept((float)value.getAnimatedValue());
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(animator!=next)return;
                animator=null;done.run();
            }
        });
        next.start();
    }

    int fling(View host,int start,int end,int velocity,BooleanSupplier current,
            IntConsumer update,Runnable invalid,Runnable done) {
        if(scroller==null)scroller=new OverScroller(host.getContext());
        scroller.fling(0,start,0,velocity,0,0,0,end);
        flingHost=host;
        flingFrame=new Runnable() {
            @Override public void run() {
                if(flingFrame!=this)return;
                if(!current.getAsBoolean()) { invalid.run();return; }
                if(scroller.computeScrollOffset()) {
                    update.accept(scroller.getCurrY());host.postOnAnimation(this);
                } else {
                    flingFrame=null;flingHost=null;done.run();
                }
            }
        };
        host.postOnAnimation(flingFrame);
        return scroller.getFinalY();
    }
}
