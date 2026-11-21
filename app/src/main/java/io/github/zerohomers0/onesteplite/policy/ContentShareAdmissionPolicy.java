package io.github.zerohomers0.onesteplite;

/**
 * Admission for a content drop. The hard conditions mean the workspace cannot receive anything at
 * all; the remaining flags are transient animation states that resolve on their own, so a drop
 * that is already in flight waits them out instead of being refused mid-gesture.
 */
final class ContentShareAdmissionPolicy {
    static final int ADMIT = 0, DEFER = 1, REJECT = 2;

    private ContentShareAdmissionPolicy() {}

    static int evaluate(boolean hostReady, boolean sceneActive, boolean screenOn,
            boolean keyguardLocked, boolean suspendRequested, boolean workspaceEntering,
            boolean liveSwapInputBlocked, boolean taskCellsCovered, boolean promoting,
            boolean transferBusy, boolean cellMotion, boolean sideDismiss,
            boolean sideMotionRunning, boolean mainSideAnimating, boolean sideCaptured,
            boolean liveFlipZoom) {
        if (!hostReady || !sceneActive || !screenOn || keyguardLocked || suspendRequested
                || workspaceEntering || liveSwapInputBlocked) return REJECT;
        if (taskCellsCovered || promoting || transferBusy || cellMotion || sideDismiss
                || sideMotionRunning || mainSideAnimating || sideCaptured || liveFlipZoom)
            return DEFER;
        return ADMIT;
    }

    /** A deferred drop is still admitted: the exchange polls until the animation settles. */
    static boolean admits(int decision) {
        return decision != REJECT;
    }
}
