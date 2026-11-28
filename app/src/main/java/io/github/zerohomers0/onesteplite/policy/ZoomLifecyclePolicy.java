package io.github.zerohomers0.onesteplite;

/** Guards when zoom geometry may be reasserted and when a delayed write is still current. */
final class ZoomLifecyclePolicy {
    private ZoomLifecyclePolicy() {}

    static boolean shouldReassertGeometry(boolean active, boolean rootVisible, boolean screenOn) {
        return active && rootVisible && screenOn;
    }

    static boolean shouldApplyDelayedWrite(long scheduledGeneration, long currentGeneration,
            boolean requestedZoomed, boolean zoomed) {
        return scheduledGeneration == currentGeneration && requestedZoomed == zoomed;
    }
}
