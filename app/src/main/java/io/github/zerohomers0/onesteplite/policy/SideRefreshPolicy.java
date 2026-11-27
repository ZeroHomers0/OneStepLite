package io.github.zerohomers0.onesteplite;

/** Focused or transitioning side panes keep motion smooth; idle panes use a 30fps hint. */
final class SideRefreshPolicy {
    static final float IDLE_FPS = 30f;
    static final float ACTIVE_FPS = 60f;

    private SideRefreshPolicy() {}

    static float target(boolean top, boolean focused, boolean transitioning) {
        return top || focused || transitioning ? ACTIVE_FPS : IDLE_FPS;
    }
}
