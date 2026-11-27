package io.github.zerohomers0.onesteplite;

import android.os.Handler;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One live DisplayContent magnification target and its WM-serialized dispatch. */
final class ZoomTarget {
    final HostEntry owner;
    final Object display;
    final Object globalLock;
    final Handler handler;
    final ZoomDispatch dispatch;
    private final Supplier<Object> currentTarget;

    ZoomTarget(HostEntry owner, Object display, Object globalLock, Handler handler,
            Supplier<Object> currentTarget, Consumer<Throwable> failure) {
        this.owner = owner;
        this.display = display;
        this.globalLock = java.util.Objects.requireNonNull(globalLock);
        this.handler = handler;
        this.currentTarget = currentTarget;
        this.dispatch = new ZoomDispatch(handler::post, globalLock,
                (identity, scale, offsetY, offsetX) -> {
                    if (currentTarget.get() == identity) {
                        // Static state requests win over animation; cancel before applying so
                        // a finished animation cannot write the old target back.
                        owner.cancelZoomAnimation(this);
                        owner.applyDisplayZoomLocked(this, scale, offsetY, offsetX);
                    }
                }, failure);
    }

    /** Animation commands must enter the WM handler through the same serialized path. */
    void submitAnim(ZoomAnimCommand.Plan plan, String key) {
        handler.post(() -> {
            if (currentTarget.get() != this) return;
            owner.startZoomAnimation(this, plan, key);
        });
    }
}
