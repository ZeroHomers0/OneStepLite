package io.github.zerohomers0.onesteplite;

/** Passive scene transition validator used by diagnostics without changing Host behavior. */
final class WorkspaceSceneTracker {
    static final int ACTIVE = 0, SUSPENDING = 1, SUSPENDED = 2, RESUMING = 3;
    private int observed = -1;
    private String lastViolation;

    synchronized String observe(int next) {
        String violation = null;
        if (observed >= 0 && !allowed(observed, next)) {
            violation = "unexpected workspace scene transition " + name(observed) + " -> " + name(next);
            lastViolation = violation;
        }
        observed = next;
        return violation;
    }

    synchronized String snapshot() {
        return "current=" + name(observed)
                + " violation=" + (lastViolation == null ? "none" : lastViolation);
    }

    static boolean allowed(int from, int to) {
        if (from < 0 || to < 0 || from == to) return true;
        switch (from) {
            case ACTIVE: return to == SUSPENDING || to == RESUMING;
            case SUSPENDING: return to == ACTIVE || to == SUSPENDED || to == RESUMING;
            case SUSPENDED: return to == ACTIVE || to == RESUMING;
            case RESUMING: return to == ACTIVE || to == SUSPENDING || to == SUSPENDED;
            default: return false;
        }
    }

    static String name(int scene) {
        switch (scene) {
            case ACTIVE: return "ACTIVE";
            case SUSPENDING: return "SUSPENDING";
            case SUSPENDED: return "SUSPENDED";
            case RESUMING: return "RESUMING";
            case -1: return "UNSEEN";
            default: return "UNKNOWN(" + scene + ")";
        }
    }
}
