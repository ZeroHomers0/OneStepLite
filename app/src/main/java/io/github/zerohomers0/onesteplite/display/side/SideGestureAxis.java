package io.github.zerohomers0.onesteplite;

/** A diagonal gesture stays undecided until one axis clearly owns it. */
final class SideGestureAxis {
    static final int UNDECIDED = 0, SCROLL = 1, DISMISS = 2, INWARD = 3;
    static int resolve(boolean sideOnLeft, float dx, float dy, int slop) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) return UNDECIDED;
        float x = Math.abs(dx), y = Math.abs(dy);
        if (y > slop && y > x * 1.5f) return SCROLL;
        if (x > slop && x > y * 1.5f)
            return (sideOnLeft ? -dx : dx) > 0 ? DISMISS : INWARD;
        return UNDECIDED;
    }
    private SideGestureAxis() {}
}
