package io.github.zerohomers0.onesteplite;

/** Rate limits identical routine diagnostics; errors and lifecycle messages always pass. */
final class RoutineLog {
    private static final java.util.LinkedHashMap<String,Long> LAST = new java.util.LinkedHashMap<>();
    private RoutineLog() {}
    static boolean allow(String message) { return allow(message, System.nanoTime() / 1000000L); }
    static synchronized boolean allow(String message, long now) {
        if (!(message.startsWith("zoom trigger written:") || message.startsWith("task profile scope current=")
                || message.startsWith("overlay refreshed for transition")
                || message.startsWith("final transaction retained own side surfaces=")
                || message.startsWith("WM zoom applied scale=") || message.startsWith("gesture geometry scale=")
                || message.startsWith("task window state task="))) return true;
        Long previous = LAST.get(message);
        if (previous != null && now >= previous && now - previous < 30000L) return false;
        LAST.put(message, now);
        if (LAST.size() > 128) LAST.remove(LAST.keySet().iterator().next());
        return true;
    }
}
