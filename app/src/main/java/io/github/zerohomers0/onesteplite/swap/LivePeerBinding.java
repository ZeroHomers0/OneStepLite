package io.github.zerohomers0.onesteplite;

/** Exact side-task binding captured at live-swap start. */
final class LivePeerBinding {
    final Slot slot;
    final long epoch;
    final int id;
    final Object token, controller;

    LivePeerBinding(Slot slot) {
        this.slot = slot;
        epoch = slot.taskEpoch;
        id = slot.taskId;
        token = slot.taskToken;
        controller = slot.controller;
    }
}
