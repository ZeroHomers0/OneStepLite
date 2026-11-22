package io.github.zerohomers0.onesteplite;

import java.util.function.BooleanSupplier;

/** A transition may write only while its original scene is current and it has not retired. */
final class TransitionSession {
    private final BooleanSupplier current;
    private volatile boolean retired;

    TransitionSession(BooleanSupplier current) { this.current=current; }
    boolean active() { return !retired && current.getAsBoolean(); }
    boolean retire() {
        if(retired)return false;
        retired=true;return true;
    }
}
