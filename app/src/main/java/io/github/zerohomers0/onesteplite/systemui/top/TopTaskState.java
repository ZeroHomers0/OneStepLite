package io.github.zerohomers0.onesteplite;

import java.util.Objects;

/** One real task owns the upper face. Async work must still hold its exact ticket. */
final class TopTaskState {
    static final class Key {
        final int taskId, userId;
        final Object token;
        Key(int taskId, int userId, Object token) {
            if (taskId < 0 || userId < 0 || token == null) throw new IllegalArgumentException("task identity");
            this.taskId = taskId; this.userId = userId; this.token = token;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return taskId == key.taskId && userId == key.userId && token.equals(key.token);
        }
        @Override public int hashCode() { return Objects.hash(taskId, userId, token); }
    }
    static final class Ticket {
        final Key key;
        final long scene, sequence;
        Ticket(Key key, long scene, long sequence) { this.key = key; this.scene = scene; this.sequence = sequence; }
    }
    private Key owner;
    private Ticket pending;
    private long sequence;
    Key owner() { return owner; }
    boolean busy() { return pending != null; }
    Ticket request(Key key, long scene, boolean workspaceActive, boolean manual) {
        if (!manual || !workspaceActive || key == null || pending != null || owner != null) return null;
        pending = new Ticket(key, scene, ++sequence);
        return pending;
    }
    boolean current(Ticket ticket, long scene) {
        return ticket != null && pending == ticket && ticket.sequence == sequence && ticket.scene == scene;
    }
    boolean commit(Ticket ticket, long scene) {
        if (!current(ticket, scene)) return false;
        owner = ticket.key; pending = null; return true;
    }
    void cancel(Ticket ticket) { if (pending == ticket) { pending = null; sequence++; } }
    void release(Key key) {
        if (key==null || !Objects.equals(owner, key)) return;
        owner = null; sequence++;
    }
    void sceneChanged() { pending = null; sequence++; }
}
