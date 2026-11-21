package io.github.zerohomers0.onesteplite;

/**
 * Whether a screen capture may include a window the platform marked secure. Off by default: the
 * preview is a real frame of the app, so enabling this can expose a protected screen.
 */
final class SecureCapturePolicy {
    private SecureCapturePolicy() {}

    static boolean allowCapture(boolean userEnabled, boolean captureInProgress, boolean ourWindow) {
        if (!userEnabled) return false;
        // Only the transition preview may look through the flag, never a background capture.
        return captureInProgress && !ourWindow;
    }
}
