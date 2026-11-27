package io.github.zerohomers0.onesteplite;

/** Coalesces task bursts while limiting top-app status queries to one per second. */
final class OpenedAppsRefreshPolicy {
    static final long BURST_COALESCE_MS = 100L;
    static final long MIN_INTERVAL_MS = 1000L;

    private OpenedAppsRefreshPolicy() {}

    static long delay(long now, long refreshedAt) {
        if (refreshedAt <= 0L || now < refreshedAt) return BURST_COALESCE_MS;
        long elapsed = now - refreshedAt;
        if (elapsed >= MIN_INTERVAL_MS) return BURST_COALESCE_MS;
        return Math.max(BURST_COALESCE_MS, MIN_INTERVAL_MS - elapsed);
    }
}
