package io.github.zerohomers0.onesteplite;

/** Recovery metadata for one task whose native controller is owned by the Host. */
final class OwnedTask {
    final int id;
    final Object token;
    Object surface;
    Object recoveryController;
    int userId = -1;
    boolean recoveryCandidate;

    OwnedTask(int id, Object token) { this.id = id; this.token = token; }
}
