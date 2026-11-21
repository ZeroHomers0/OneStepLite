package io.github.zerohomers0.onesteplite;

/**
 * Chrome reveal progress for the workspace entry and exit.
 *
 * <p>The main task moves on the window manager thread while Host chrome is
 * excluded from display magnification, so chrome can only be revealed by
 * geometry. Both bands share one animation clock but must not advance at the
 * same rate: the top strip is wide and shallow, the side rail is narrow and
 * tall, and the window manager applies the published zoom a frame or two after
 * the Host starts.
 *
 * <p>A larger value means a smaller exclusion hole, and therefore more chrome.
 * The Host sits below the native freeform task, so chrome that is still
 * revealed under the application is simply covered by it, while chrome that is
 * clipped away before the application arrives leaves a band of wallpaper where
 * a task used to be. Leading is therefore always seamless; trailing is only
 * safe where the band it exposes is wallpaper the user expects to see.
 *
 * <p>The top strip always leads: it is opaque the moment the edge uncovers it
 * on the way in, and the growing application covers it before it is clipped on
 * the way out.
 *
 * <p>The side rail is the band that carries the rate difference, and its
 * direction flips with the animation. Entering, the cells trail the receding
 * edge, so their content slides in behind it and the wallpaper gap between the
 * two is the background being revealed. Exiting, the cells are live task
 * surfaces: a trailing crop would cut them away before the growing application
 * covers them and flash wallpaper where a task was, so they lead instead.
 */
final class WorkspaceRevealCurve {
    /** Top strip: leads the edge in both directions, so it is never clipped early. */
    static float top(float progress) {
        float p = clamp(progress);
        float rest = 1f - p;
        return 1f - rest * rest;
    }

    /** Side rail while entering: trails the receding edge, so cell content slides in behind it. */
    static float side(float progress) {
        return side(progress, false);
    }

    /** Side rail: trails the edge on entry, leads it on exit. */
    static float side(float progress, boolean exiting) {
        float p = clamp(progress);
        if (!exiting) return p * (float) Math.sqrt(p);
        // Leads by at most half a step, always behind the bar: the cells hold
        // their live surface until the growing main has almost covered them.
        return p * (1.5f - 0.5f * p);
    }

    private static float clamp(float value) {
        return value <= 0f ? 0f : Math.min(value, 1f);
    }
}
