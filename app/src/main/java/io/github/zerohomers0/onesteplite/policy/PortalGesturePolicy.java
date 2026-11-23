package io.github.zerohomers0.onesteplite;

/** Bounds every source handoff to one live pointer and one workspace snapshot. */
final class PortalGesturePolicy {
    static final long OFFER_MS=1200,MAX_HOLD_MS=15_000;
    private PortalGesturePolicy(){}
    static boolean live(boolean active,boolean attached,int pointers,long down,long now) {
        return active && attached && pointers>=1 && pointers<=2 && down>0 && now>=down && now-down<=MAX_HOLD_MS;
    }
    static boolean offer(long expected,long actual,long issued,long now) {
        return expected>0 && expected==actual && issued>0 && now>=issued && now-issued<=OFFER_MS;
    }
}
