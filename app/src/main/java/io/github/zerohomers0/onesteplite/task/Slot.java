package io.github.zerohomers0.onesteplite;

import android.view.MotionEvent;
import android.view.View;

/** One Host-owned main or side cell and the native state bound to it. */
final class Slot implements SlotPositions.Entry {
    int index;
    View view;
    View hint;
    Object controller;
    int taskId = -1;
    Object taskToken;
    int[] rect;
    int sideRow = -1;
    volatile boolean sideShown, sidePrepared;
    Object sideViewportParent, sideViewportSurface;
    int windowMode = -1;
    TaskWindowState windowState;
    boolean occupied;
    boolean taskVisible;
    boolean reassertPending;
    Runnable scaleRepair;
    boolean scaleRepairRunning;
    boolean frameRateHinted;
    float frameRateApplied = -1f;
    boolean taskFocused;
    long modeGuardUntil;
    int modeRepairAttempts;
    boolean modeAdmissionPending;
    final VisibilityRepair visibilityRepair = new VisibilityRepair();
    // v0.9 缩放渲染：任务按满屏排版，靠矩阵缩到格子里
    Object scaleSurface;
    Object cellBackground;
    Object cellBackgroundParent;
    int cellBackgroundWidth;
    int cellBackgroundHeight;
    boolean scaleOnLeash;
    int scaleTaskId = Integer.MIN_VALUE;
    float scaleApplied = -1f;
    int scaleAttempts;
    int taskUid;
    String pkg;
    // A tap swaps; an outward swipe releases this exact task to the background.
    MotionEvent bufferedDown;
    long touchTaskEpoch, touchSceneEpoch;
    boolean injecting;
    int touchAxis;
    volatile boolean promoting;
    boolean launching;
    int retiredTaskId = -1;
    long taskEpoch;
    int removalCheckTask = -1;
    volatile boolean parked;
    volatile boolean frozen;
    volatile boolean closing;
    volatile boolean nativeReleasePending;
    // fullscreen-only 应用的 mirror 承载：任务仍是满屏前台，画面被移出屏幕，
    // 副本镜像显示在格子里；点击格子把原任务移回主区。
    int carrierTaskId = -1;
    Object carrierLeash;
    Object carrierMirror;
    View carrierTapView;
    boolean carrier;
    PendingSidePromotion pendingSidePromotion;

    Slot(int index) {
        this.index = index;
    }

    public int position() { return index; }
    public void position(int value) { index = value; }
}
