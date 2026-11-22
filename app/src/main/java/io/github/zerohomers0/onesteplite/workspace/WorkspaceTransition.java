package io.github.zerohomers0.onesteplite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RectF;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Workspace entry/exit timeline and geometry; task ownership stays with the host and WM. */
final class WorkspaceTransition {
    private final TransitionSession session;
    private final ValueAnimator animator;

    WorkspaceTransition(float from,float to,BooleanSupplier current,Consumer<Float> frame,Runnable complete) {
        session=new TransitionSession(current);
        animator=ValueAnimator.ofFloat(from,to);
        animator.addUpdateListener(value->{if(session.active())frame.accept((Float)value.getAnimatedValue());});
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animation) {session.retire();}
            @Override public void onAnimationEnd(Animator animation) {
                boolean finish=session.active();session.retire();
                if(finish)complete.run();
            }
        });
    }

    ValueAnimator animator() {return animator;}
    void start(long start) {WorkspaceMotionSpec.start(animator,start,WorkspaceMotionSpec.DURATION_MS);}

    static RectF mainHole(float progress,int width,int height,float targetScale,
            float targetX,float targetY,float movingX,RectF out) {
        float scale=1f+(targetScale-1f)*progress;
        float x=Float.isNaN(movingX)?targetX*progress:movingX,y=targetY*progress;
        out.set(x,y,x+width*scale,y+height*scale);return out;
    }

    static float sideOffset(boolean mainOnRight,float targetX,float targetWidth,float left,float right) {
        return mainOnRight?left-targetX:right-(targetX+targetWidth);
    }
}
