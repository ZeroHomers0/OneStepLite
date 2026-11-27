package io.github.zerohomers0.onesteplite;

import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

/** Owns the transition touch gate and its sibling input window. */
final class WorkspaceTransitionWindow {
    private final WorkspaceHost host;
    private TransitionInput transitionInput;
    private View transitionInputWindow;
    private long transitionInputStartedAt, transitionInputReleaseRequestedAt;

    WorkspaceTransitionWindow(WorkspaceHost host) {
        this.host = host;
    }

    void attach() {
        transitionInput = new TransitionInput(host.root, this::acquireTransitionInputWindow,
                this::releaseTransitionInputWindow, blocked -> {
                    host.liveSwapInputBlocked = blocked;
                    for (Slot slot : host.slots) {
                        if (slot != null && slot.index > 0) host.chrome.updateTouchRegion(slot);
                    }
                    if (host.root != null) {
                        host.root.requestLayout();
                        host.root.invalidate();
                    }
                }, WorkspaceHost::log);
    }

    boolean consume(int action) {
        return transitionInput != null && transitionInput.consume(action);
    }

    boolean setLiveSwapInputBlocked(boolean on) {
        return setLiveSwapInputBlocked(on,false);
    }

    boolean setLiveSwapInputBlocked(boolean on,boolean immediate) {
        if (transitionInput == null) return !on;
        if (on) {
            boolean acquired = transitionInput.block();
            if (acquired && transitionInputStartedAt == 0L) {
                transitionInputStartedAt = SystemClock.uptimeMillis();
                WorkspaceHost.log("live input acquired uptime=" + transitionInputStartedAt);
            }
            return acquired;
        }
        transitionInputReleaseRequestedAt = SystemClock.uptimeMillis();
        WorkspaceHost.log("live input final frame requested uptime=" + transitionInputReleaseRequestedAt);
        if(immediate)transitionInput.releaseNow();
        else transitionInput.releaseAfterFrame();
        return true;
    }

    private boolean onTransitionContentDrag(View blocker, android.view.DragEvent event) {
        return blocker == transitionInputWindow && host.contentDrag.onTransitionDrop(event);
    }

    private void acquireTransitionInputWindow() {
        if (transitionInputWindow != null) return;
        View blocker = new View(host.ctx);
        blocker.setOnTouchListener((view, event) -> true);
        blocker.setOnDragListener(this::onTransitionContentDrag);
        blocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(host.lp);
        params.setTitle("TransitionInput");
        // A sibling window sits above the borrowed app surfaces under Host's root.
        // Merely widening Host's own touch region cannot cover its higher children.
        host.wm.addView(blocker, params);
        transitionInputWindow = blocker;
    }

    private void releaseTransitionInputWindow() {
        View blocker = transitionInputWindow;
        transitionInputWindow = null;
        if (blocker == null) return;
        blocker.setVisibility(View.GONE);
        try { host.wm.removeViewImmediate(blocker); }
        catch (Throwable error) { WorkspaceHost.log("live input window release: " + error); }
        if (transitionInputStartedAt != 0L) {
            long now = SystemClock.uptimeMillis();
            WorkspaceHost.log("live input released uptime=" + now + " heldMs=" + (now - transitionInputStartedAt)
                    + " finalFrameMs=" + (transitionInputReleaseRequestedAt == 0L
                            ? -1L : now - transitionInputReleaseRequestedAt));
            transitionInputStartedAt = transitionInputReleaseRequestedAt = 0L;
        }
    }
}
