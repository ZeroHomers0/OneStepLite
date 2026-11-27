package io.github.zerohomers0.onesteplite;

/** Centralizes steady workspace lease and repair cadence. */
final class WorkspaceHeartbeatPolicy {
    static final long LEASED_HEARTBEAT_MS = 60_000L;
    static final long FALLBACK_HEARTBEAT_MS = 8_000L;
    static final long LEASED_REPAIR_MS = 60_000L;
    static final long FALLBACK_REPAIR_MS = 30_000L;

    private WorkspaceHeartbeatPolicy() {}

    static long heartbeatDelay(boolean leaseAcked) {
        return leaseAcked ? LEASED_HEARTBEAT_MS : FALLBACK_HEARTBEAT_MS;
    }

    static long repairInterval(boolean leaseAcked) {
        return leaseAcked ? LEASED_REPAIR_MS : FALLBACK_REPAIR_MS;
    }

    static boolean repairDue(long now, long lastRepair, boolean leaseAcked) {
        return lastRepair <= 0L || now < lastRepair
                || now - lastRepair >= repairInterval(leaseAcked);
    }
}
