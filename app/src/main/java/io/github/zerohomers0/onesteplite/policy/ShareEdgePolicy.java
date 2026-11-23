package io.github.zerohomers0.onesteplite;

/** Edge dwell is independent of pointer-event frequency and stops immediately on exit. */
final class ShareEdgePolicy {
    private int direction;
    private long entered;

    int update(float x, float left, float right, float edge, long now) {
        int next = x < left || x > right ? 0 : x < left+edge ? -1 : x > right-edge ? 1 : 0;
        if (next != direction) { direction=next;entered=now; }
        return direction != 0 && now-entered >= 350L ? direction : 0;
    }

    void reset() { direction=0;entered=0; }
}
