package io.github.zerohomers0.onesteplite;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.animation.AnimationUtils;
import android.view.animation.PathInterpolator;

/** Shared physical timeline for Host chrome and the published native WM command. */
final class WorkspaceMotionSpec {
    static final long DURATION_MS = 300L, LEAD_MS = 32L, CELL_EXIT_MS = 110L;
    private static final PathInterpolator CURVE=new PathInterpolator(.4f,0f,.2f,1f);
    static PathInterpolator interpolator() {return CURVE;}

    static float frameProgress(long start,long duration,float fallback) {
        if(start<=0 || duration<=0 || !ValueAnimator.areAnimatorsEnabled())return fallback;
        float scaledDuration=duration*ValueAnimator.getDurationScale();
        if(scaledDuration<=0)return fallback;
        // During a frame Android holds this clock at the display's vsync time.
        long elapsed=AnimationUtils.currentAnimationTimeMillis()-start;
        return CURVE.getInterpolation(Math.max(0f,Math.min(1f,elapsed/scaledDuration)));
    }
    static void start(ValueAnimator animator, long start, long duration) {
        animator.setDuration(duration);
        animator.setInterpolator(interpolator());
        animator.setStartDelay(Math.max(0L, start - SystemClock.uptimeMillis()));
        animator.start();
        // A busy main thread can miss the scheduled first frame. Catch up to WM,
        // rather than starting another complete animation after the native task.
        long elapsed = SystemClock.uptimeMillis() - start;
        if (start > 0L && elapsed > 0L) animator.setCurrentPlayTime(Math.min(duration, elapsed));
    }
    private WorkspaceMotionSpec() {}
}
