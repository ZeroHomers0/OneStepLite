package io.github.zerohomers0.onesteplite;

/**
 * Chrome reveal progress for the workspace entry and exit.
 *
 * <p>The main task shrinks on the window manager thread while Host chrome is
 * excluded from display magnification, so chrome can only be revealed by
 * geometry. Both bands share one animation clock but must not advance at the
 * same rate: the top strip is wide and shallow, the side rail is narrow and
 * tall, and the window manager applies the published zoom a frame or two after
 * the Host starts.
 *
 * <p>The top strip always leads the main edge. A leading hole is smaller than
 * the real one, so the bar already extends under the application and is opaque
 * the moment the edge uncovers it; a trailing hole would instead leave a strip
 * of wallpaper between the bar and the application.
 *
 * <p>The side rail is the band that carries the rate difference, and its
 * direction flips with the animation. Entering, the cells trail the receding
 * edge so their content slides in behind it. Exiting, they must lead it: a
 * trailing hole cuts the cells away before the growing main reaches them, which
 * flashes wallpaper between the two.
 */
final class WorkspaceRevealCurve {
    /** Top strip: ease-out, so the bar is opaque as soon as the edge passes it. */
    static float top(float progress) {
        float p = clamp(progress);
        float rest = 1f - p;
        return 1f - rest * rest;
    }

    /** Side rail while entering: ease-in, so cell content trails the receding edge. */
    static float side(float progress) {
        return side(progress, false);
    }

    /** Side rail: trails the edge on entry, leads it on exit. */
    static float side(float progress, boolean exiting) {
        float p = clamp(progress);
        if (!exiting) return p * (float) Math.sqrt(p);
        float root = (float) Math.cbrt(p);
        return root * root;
    }

    private static float clamp(float value) {
        return value <= 0f ? 0f : Math.min(value, 1f);
    }
}
