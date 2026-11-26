package io.github.zerohomers0.onesteplite;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** One task-promotion transaction and every visual/native resource it owns. */
final class Promotion {
    final Slot slot;
    final int taskId;
    final Object token;
    final List<Object> otherSideTokens = new ArrayList<>();
    volatile boolean cancelled;
    volatile boolean handoffAttempted;
    volatile boolean handoffComplete;
    boolean background;
    boolean externalLaunch;
    boolean nativeSwap;
    boolean nativeFlip;
    boolean nativeHome;
    boolean nativeCollect;
    Runnable notificationLaunch;
    boolean dragHover;
    Runnable collectionLaunch;
    long liveZoomGuardUntil;
    WorkspaceLayout previousLayout;
    WorkspaceLayout targetLayout;
    boolean candidateRecovery;
    boolean liveMainReveal;
    boolean restoreMainOpacityAfterHandoff;
    Object liveRevealSurface;
    volatile boolean nativeSubmitted;
    volatile boolean cancelRequested;
    volatile boolean nativeCompleting;
    boolean liveMainFocusPrepared;
    int liveMainUserId = -1;
    FocusResult liveMainFocusResult;
    LiveSwapVisualLease liveVisual;
    LiveBackdrop liveBackdrop;
    final List<LiveSwapVisualLease> peerVisuals = new ArrayList<>();
    final List<LivePeerBinding> livePeers = new ArrayList<>();
    OwnedTask previousMainCandidate;
    LiveTask previousMain;
    LiveTask homeBeforeCover;
    LiveTask collectionHome;
    boolean previousMainOnRight;
    boolean targetMainOnRight;
    boolean keepTopBarAppearance;
    /** 交换时主任务（previousMain）的真实 leash：桥上给它挖洞做 view 级缩小动画。 */
    Object mainLeash;
    Bitmap compactSnapshot;
    Rect compactSnapshotRect;
    long sceneEpoch;
    long bindingEpoch;

    Promotion(Slot slot, Slot[] slots) {
        this.slot = slot;
        this.taskId = slot.taskId;
        this.token = slot.taskToken;
        for (Slot other : slots) {
            // A parked/frozen or already hidden cell is not a live peer that can
            // block promotion. Requiring it visible caused spurious rollbacks
            // after the user returned from Home with a stale side binding.
            if (other != slot && (other.sideRow < 0 || other.sideShown)
                    && other.occupied && !other.parked && !other.frozen
                    && other.taskVisible && other.taskToken != null) {
                otherSideTokens.add(other.taskToken);
            }
        }
    }
}
