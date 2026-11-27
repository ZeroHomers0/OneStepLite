package io.github.zerohomers0.onesteplite;

import java.util.Objects;

/** Compares a side task binding against the one a promotion was started for. */
final class SidePromotionPolicy {
    private SidePromotionPolicy() {}

    static boolean sameBinding(int expectedTaskId, Object expectedToken,
            long expectedScene, long expectedBinding, String expectedPackage,
            int taskId, Object token, long scene, long binding, String pkg) {
        return expectedTaskId == taskId && Objects.equals(expectedToken, token)
                && expectedScene == scene && expectedBinding == binding
                && Objects.equals(expectedPackage, pkg);
    }

    static long retryDelay(long now, long deadline, long guardUntil, boolean busy) {
        if (now >= deadline) return -1L;
        long guarded = Math.max(0L, guardUntil - now);
        if (!busy && guarded == 0L) return 0L;
        return guarded > 0L ? Math.min(200L, Math.max(40L, guarded)) : 120L;
    }
}
