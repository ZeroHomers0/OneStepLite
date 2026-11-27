package io.github.zerohomers0.onesteplite;

/** Shared durations by interaction; native workspace geometry keeps its own physical timeline. */
final class OverlayMotionSpec {
    static final long TOP_FADE_MS = 200L, PANEL_MS = 180L, PAGE_MS = 160L;
    static final long SHARE_RAIL_MS = 480L;
    static final long PRESS_MS = 80L, RELEASE_MS = 120L, CONTROLS_IDLE_MS = 3000L;
    private OverlayMotionSpec() {}
}
