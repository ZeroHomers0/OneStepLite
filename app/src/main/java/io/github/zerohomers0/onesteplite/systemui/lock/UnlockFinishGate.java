package io.github.zerohomers0.onesteplite;

/** Host drawing and native unlock cleanup are separate compositor submissions. */
final class UnlockFinishGate {
    private final java.util.Set<Long> pending = new java.util.HashSet<>();
    private long epoch, sequence;
    private Runnable waiting;

    synchronized void reset(long scene) {
        epoch = scene;
        pending.clear();
        waiting = null;
    }

    synchronized long begin(long scene) {
        if (scene != epoch) return 0;
        long ticket = ++sequence;
        pending.add(ticket);
        return ticket;
    }

    void afterHostFrame(long scene, Runnable ready) {
        synchronized (this) {
            if (scene != epoch) return;
            if (!pending.isEmpty()) { waiting = ready; return; }
        }
        ready.run();
    }

    void committed(java.util.List<Long> tickets) {
        Runnable ready;
        synchronized (this) {
            if (!pending.removeAll(tickets) || !pending.isEmpty()) return;
            ready = waiting;
            waiting = null;
        }
        if (ready != null) ready.run();
    }
}
