package io.github.zerohomers0.onesteplite;

/** Result returned by an authenticated workspace control operation. */
record WorkspaceControlResult(long requestId, Status status, String detail) {
    enum Status { ACCEPTED, REJECTED, UNSUPPORTED, STALE }

    WorkspaceControlResult {
        detail = detail == null ? "" : detail;
        if (requestId < 0L || detail.length() > 512) {
            throw new IllegalArgumentException("invalid workspace control result");
        }
    }
}