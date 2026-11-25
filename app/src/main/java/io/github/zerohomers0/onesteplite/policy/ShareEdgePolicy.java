package io.github.zerohomers0.onesteplite;

/** Edge dwell is independent of pointer-event frequency and stops immediately on exit. */
final class ShareEdgePolicy {
    private static final long DWELL_MS=150L;
    private int direction;
    private long entered;

    int update(float x, float left, float right, float edge, long now) {
        int next = !Float.isFinite(x) || right<=left || edge<=0 || x < left || x > right ? 0
                : x < left+edge ? -1 : x > right-edge ? 1 : 0;
        if (next != direction) { direction=next;entered=now; }
        return direction != 0 && now-entered >= DWELL_MS ? direction : 0;
    }

    int step(float x,float left,float right,float band,long now) {
        int sign=update(x,left,right,band,now);if(sign==0)return 0;
        float depth=Math.min(1,Math.max(0,sign<0?(left+band-x)/band:(x-right+band)/band));
        float ramp=Math.min(1,Math.max(0,(now-entered-DWELL_MS)/550f));
        return sign*Math.round(2+6*depth*ramp);
    }

    void reset() { direction=0;entered=0; }
}
