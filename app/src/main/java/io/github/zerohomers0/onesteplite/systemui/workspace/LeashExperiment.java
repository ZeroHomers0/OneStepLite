package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.view.animation.PathInterpolator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns the diagnostic leash animation and its delayed restoration. */
final class LeashExperiment implements AutoCloseable {
    interface Transform { void apply(Object leash,float x,float y,float sx,float sy); }
    private final Handler handler;
    private final Transform transform;
    private final BiConsumer<Integer,Boolean> chrome;
    private final Consumer<String> log;
    private ValueAnimator animator;
    private Object leash;
    private int slot,task,frames;
    private final Runnable restore=this::close;

    LeashExperiment(Handler handler,Transform transform,BiConsumer<Integer,Boolean> chrome,Consumer<String> log) {
        this.handler=handler;this.transform=transform;this.chrome=chrome;this.log=log;
    }

    void start(Object leash,int slot,int task,float x,float y,float sx,float sy,long duration) {
        close();this.leash=leash;this.slot=slot;this.task=task;frames=0;
        chrome.accept(slot,true);
        ValueAnimator next=ValueAnimator.ofFloat(0f,1f);animator=next;
        next.setDuration(duration);next.setInterpolator(new PathInterpolator(.34f,.69f,.1f,1f));
        next.addUpdateListener(value->{
            if(animator!=next)return;
            float f=(float)value.getAnimatedValue();frames++;
            transform.apply(leash,x*f,y*f,1f+(sx-1f)*f,1f+(sy-1f)*f);
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(animator!=next)return;
                animator=null;transform.apply(leash,x,y,sx,sy);
                log.accept("leash anim POC finished task="+task+" frames="+frames);
                handler.postDelayed(restore,1200L);
            }
        });
        next.start();
    }

    @Override public void close() {
        handler.removeCallbacks(restore);
        ValueAnimator old=animator;animator=null;if(old!=null)old.cancel();
        Object source=leash;leash=null;
        if(source==null)return;
        transform.apply(source,0f,0f,1f,1f);chrome.accept(slot,false);
        log.accept("leash anim POC restored task="+task);
    }
}
