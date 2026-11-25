package io.github.zerohomers0.onesteplite;

/** Exact task/surface binding captured for one retained render transaction. */
final class RenderBinding {
    final Slot slot;
    final int id;
    final Object token, leash, parent;
    final long scene, binding;

    RenderBinding(Slot slot, long scene, Object leash, Object parent) {
        this.slot = slot;
        id = slot.taskId;
        token = slot.taskToken;
        this.scene = scene;
        binding = slot.taskEpoch;
        this.leash = leash;
        this.parent = parent;
    }
}
