package io.github.zerohomers0.onesteplite;

/** Versioned command envelope for protected workspace control broadcasts. */
record WorkspaceControlRequest(String command, int protocolVersion, long requestId, String reason) {
    static final String TOGGLE = "toggle";
    static final String DEBUG = "debug";

    WorkspaceControlRequest {
        command = command == null ? "" : command.trim();
        reason = reason == null ? "" : reason;
        if (requestId < 0L || reason.length() > 256) {
            throw new IllegalArgumentException("invalid workspace control request");
        }
    }

    boolean supported() {
        return ProtocolVersion.compatible(protocolVersion)
                && (TOGGLE.equals(command) || DEBUG.equals(command));
    }
}