package io.github.zerohomers0.onesteplite;

/** One deferred side promotion tied to an exact cell binding and scene epoch. */
final class PendingSidePromotion {
    final Slot slot;
    final int taskId;
    final Object token;
    final long sceneEpoch;
    final long bindingEpoch;
    final long deadline;
    final String pkg;
    boolean desktopLaunch;
    int attempts;

    PendingSidePromotion(Slot slot, long sceneEpoch, boolean desktopLaunch, long deadline) {
        this.slot = slot;
        taskId = slot.taskId;
        token = slot.taskToken;
        this.sceneEpoch = sceneEpoch;
        bindingEpoch = slot.taskEpoch;
        pkg = slot.pkg;
        this.desktopLaunch = desktopLaunch;
        this.deadline = deadline;
    }
}
